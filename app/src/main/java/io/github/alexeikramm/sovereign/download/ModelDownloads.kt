package io.github.alexeikramm.sovereign.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * The one place model downloads are enqueued, cancelled and observed.
 *
 * Everything goes into a single chained unique work, so downloads are serialised app-wide: a second
 * request is appended and runs once the first finishes. The policy is `APPEND_OR_REPLACE` rather
 * than plain `APPEND` so that a download which *failed* does not take the request queued behind it
 * down with it.
 */
object ModelDownloads {

    const val UNIQUE_WORK_NAME = "model-download"

    /** Enqueues [request] behind whatever is already downloading. */
    fun enqueue(context: Context, request: DownloadRequest, allowMetered: Boolean = false) {
        val work = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(request.toData())
            .addTag(request.tag())
            .setConstraints(networkConstraints(allowMetered))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            .enqueue()
    }

    /** Cancel means "stop downloading": the running transfer and everything queued behind it. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun observe(context: Context): Flow<List<DownloadSnapshot>> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { infos -> infos.mapNotNull { it.toDownloadSnapshot() } }

    /**
     * Lifts the Wi-Fi-only constraint on everything still waiting, so a user who started on Wi-Fi
     * and then left it can finish without paying for the bytes twice: the `.part` files are still
     * there, so each transfer resumes where it stopped.
     *
     * Only work that has not started is touched. Cancelling a running transfer to restart it would
     * throw away the bytes in flight for no gain, so this is a no-op once something is downloading.
     */
    suspend fun allowMetered(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val infos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
        if (infos.any { it.state == WorkInfo.State.RUNNING }) return

        val waiting = infos
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .mapNotNull { info -> info.tags.firstNotNullOfOrNull(DownloadRequest::fromTag) }
        if (waiting.isEmpty()) return

        workManager.cancelUniqueWork(UNIQUE_WORK_NAME).await()
        waiting.forEach { enqueue(context, it, allowMetered = true) }
    }

    private fun networkConstraints(allowMetered: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .build()

    private const val BACKOFF_SECONDS = 30L
}
