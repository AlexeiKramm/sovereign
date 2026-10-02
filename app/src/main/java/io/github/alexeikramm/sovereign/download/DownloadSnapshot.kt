package io.github.alexeikramm.sovereign.download

import androidx.work.NetworkType
import androidx.work.WorkInfo

/** The lifecycle of one download request, flattened away from WorkManager's own type. */
enum class DownloadRunState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/**
 * One download request as the UI needs to see it. Deliberately not a `WorkInfo`: everything above
 * this line is WorkManager's business, and the mapping from [WorkInfo] is the only place that knows
 * it, so the queue logic can be unit-tested without the framework.
 */
data class DownloadSnapshot(
    val request: DownloadRequest,
    val state: DownloadRunState,
    val progress: DownloadProgress?,
    /**
     * True when the work is held back by its own Wi-Fi-only constraint. A request sitting behind a
     * *running* download is queued, not waiting for a network, so callers must check for a running
     * item before believing this.
     */
    val waitingForNetwork: Boolean,
    val failureReason: String?,
) {
    val isActive: Boolean
        get() = state == DownloadRunState.QUEUED || state == DownloadRunState.RUNNING
}

fun WorkInfo.toDownloadSnapshot(): DownloadSnapshot? {
    // Tags, not input data: WorkInfo does not expose the input Data it was created with.
    val request = tags.firstNotNullOfOrNull(DownloadRequest::fromTag) ?: return null
    return DownloadSnapshot(
        request = request,
        state = state.toRunState(),
        progress = downloadProgressFrom(progress),
        waitingForNetwork = state == WorkInfo.State.ENQUEUED &&
            constraints.requiredNetworkType == NetworkType.UNMETERED,
        failureReason = downloadFailureReason(outputData),
    )
}

private fun WorkInfo.State.toRunState(): DownloadRunState = when (this) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadRunState.QUEUED
    WorkInfo.State.RUNNING -> DownloadRunState.RUNNING
    WorkInfo.State.SUCCEEDED -> DownloadRunState.SUCCEEDED
    WorkInfo.State.FAILED -> DownloadRunState.FAILED
    WorkInfo.State.CANCELLED -> DownloadRunState.CANCELLED
}
