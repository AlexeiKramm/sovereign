package io.github.alexeikramm.sovereign.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one model download as foreground work, so it survives the app being backgrounded, swiped
 * away, or killed. All of the transfer logic lives in [ArtifactDownloader]; this class is only the
 * foreground/notification/retry policy around it.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val request = DownloadRequest.from(inputData) ?: return Result.failure()
        val plan = DownloadPlan.resolve(applicationContext, request) ?: return Result.failure()

        val notifier = DownloadNotifications(applicationContext)

        // Android 12+ forbids starting a foreground service from the background. That is exactly
        // what a restart after process death looks like: WorkManager re-runs this worker from a
        // background process and the promotion throws. Failing the work there would throw away a
        // resumable download, so the transfer continues without the foreground service instead —
        // the `.part` file means another kill costs at most the bytes in flight.
        foregroundAllowed = tryForeground(notifier.foregroundInfo(id, plan.label, progress = null))

        val latest = Channel<DownloadProgress>(Channel.CONFLATED)
        var lastPublishedAt = 0L

        // The downloader reports progress from a blocking loop, so the publishing is a second
        // coroutine: the callback sends into a conflated channel and this drains it, one WorkManager
        // update a second rather than hundreds of rows for one file. The channel is closed after the
        // walk and joined, so the final stage's update is never lost to a cancellation race.
        val outcome = coroutineScope {
            val publisher = launch {
                for (published in latest) {
                    lastPublished = published
                    setProgress(published.toData())
                    if (foregroundAllowed) {
                        foregroundAllowed =
                            tryForeground(notifier.foregroundInfo(id, plan.label, published))
                    }
                }
            }
            val result = withContext(Dispatchers.IO) {
                // Stages run in order and their bytes add up, so a preset's bar climbs once across
                // both models instead of jumping back to zero for the cleanup half. A stage that
                // fails or is cancelled stops the walk; anything finished before it is already on
                // disk, so a retry skips it via the downloader's own length check.
                var completedBytes = 0L
                var outcome: DownloadOutcome = DownloadOutcome.Complete
                for (stage in plan.stages) {
                    val stageOutcome = ArtifactDownloader(
                        directory = stage.directory,
                        files = stage.files,
                        onProgress = { progress ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastPublishedAt >= PROGRESS_INTERVAL_MS || progress.overall >= 1f) {
                                lastPublishedAt = now
                                latest.trySend(progress.acrossPlan(stage, completedBytes, plan.totalBytes))
                            }
                        },
                    ).download(isCancelled = { isStopped })
                    if (stageOutcome !is DownloadOutcome.Complete) {
                        outcome = stageOutcome
                        break
                    }
                    completedBytes += stage.totalBytes
                }
                outcome
            }
            latest.close()
            publisher.join()
            result
        }

        return when (outcome) {
            DownloadOutcome.Complete -> {
                notifier.notifyComplete(plan.label)
                Result.success()
            }

            // Cancelled by the user: end the work, say nothing. The `.part` files remain.
            is DownloadOutcome.Cancelled -> Result.failure()

            is DownloadOutcome.Failed ->
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    notifier.notifyFailed(plan.label, outcome.reason)
                    Result.failure(downloadFailureData(outcome.reason))
                }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val plan = DownloadRequest.from(inputData)?.let { DownloadPlan.resolve(applicationContext, it) }
        return DownloadNotifications(applicationContext).foregroundInfo(
            workId = id,
            label = plan?.label ?: "Voice model",
            progress = lastPublished,
        )
    }

    /**
     * Promotes the work to a foreground service, or reports that it could not be. A refusal is not
     * an error the work should die from: the notification simply does not appear, and the download
     * carries on as ordinary background work.
     */
    private suspend fun tryForeground(info: ForegroundInfo): Boolean = try {
        setForeground(info)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "foreground service refused; downloading without it", e)
        false
    }

    /**
     * The most recent progress published. `ListenableWorker` has no read access to the progress it
     * has written, and [getForegroundInfo] may be called before the first report, so the worker
     * keeps the last value itself.
     */
    @Volatile
    private var lastPublished: DownloadProgress? = null

    /** Whether the foreground service could be started; if not, promotion is not attempted again. */
    @Volatile
    private var foregroundAllowed = false

    private companion object {
        const val TAG = "ModelDownloadWorker"
        const val PROGRESS_INTERVAL_MS = 1_000L

        /** One initial attempt plus three automatic retries before the user is told it failed. */
        const val MAX_ATTEMPTS = 3
    }
}
