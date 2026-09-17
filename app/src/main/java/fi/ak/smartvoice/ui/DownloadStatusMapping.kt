package fi.ak.smartvoice.ui

import fi.ak.smartvoice.download.DownloadRequest
import fi.ak.smartvoice.download.DownloadRunState
import fi.ak.smartvoice.download.DownloadSnapshot

/**
 * The bridge from the download queue to the two questions the UI asks: "what is the selected
 * model's state?" and "which models have a live download?".
 *
 * Pure on purpose. The interesting cases — queued behind another download, waiting for Wi-Fi,
 * failed with a reason, finished while the app was closed — are decided here and can be tested
 * without WorkManager, a device, or a 700 MB download.
 */
internal fun modelStatusFor(
    installed: Boolean,
    modelId: String,
    snapshots: List<DownloadSnapshot>,
): ModelStatus {
    val forModel = snapshots.filter { it.isAsrRequestFor(modelId) }
    // Global, not per-model: whether this request is "queued behind another download" or "waiting
    // for Wi-Fi" depends on whether *anything* in the shared queue is running.
    val running = snapshots.any { it.state == DownloadRunState.RUNNING }
    val active = forModel.firstOrNull { it.state == DownloadRunState.RUNNING }
        ?: forModel.firstOrNull { it.state == DownloadRunState.QUEUED }

    if (active != null) {
        return ModelStatus.Downloading(
            progress = active.progress,
            queued = active.state == DownloadRunState.QUEUED && running,
            waitingForWifi = active.state == DownloadRunState.QUEUED && !running && active.waitingForNetwork,
        )
    }

    // Disk wins over a stale job: a download that finished while the app was closed shows as
    // installed, and an installed model is never reported as a failure.
    if (installed) return ModelStatus.Installed

    val lastStopped = forModel.lastOrNull {
        it.state == DownloadRunState.FAILED || it.state == DownloadRunState.CANCELLED
    } ?: return ModelStatus.Missing

    return if (lastStopped.state == DownloadRunState.CANCELLED) {
        ModelStatus.Failed("cancelled — tap download to resume")
    } else {
        ModelStatus.Failed(lastStopped.failureReason ?: "download failed")
    }
}

/** The live download for each ASR model, keyed by model id, for the Models list. */
internal fun asrDownloadStates(
    snapshots: List<DownloadSnapshot>,
): Map<String, ModelStatus.Downloading> =
    downloadStatesByModel(snapshots) { it is DownloadRequest.Asr }

/**
 * The live download for each LiteRT cleanup model, keyed by model id. The cleanup list shows the
 * same "queued" and "waiting for Wi-Fi" states the speech list does, because both kinds share one
 * serial queue.
 */
internal fun litertDownloadStates(
    snapshots: List<DownloadSnapshot>,
): Map<String, ModelStatus.Downloading> =
    downloadStatesByModel(snapshots) { it is DownloadRequest.LiteRt }

/**
 * Flattens the active requests of one kind into the state a single model's card needs. `queued` and
 * `waitingForWifi` are both ENQUEUED; which one it is depends on whether *anything* in the shared
 * queue is running, so that is decided once, globally, not per model.
 */
private fun downloadStatesByModel(
    snapshots: List<DownloadSnapshot>,
    matches: (DownloadRequest) -> Boolean,
): Map<String, ModelStatus.Downloading> {
    val running = snapshots.any { it.state == DownloadRunState.RUNNING }
    return snapshots
        .filter { matches(it.request) && it.isActive }
        .associate { snapshot ->
            snapshot.request.modelId to ModelStatus.Downloading(
                progress = snapshot.progress,
                queued = snapshot.state == DownloadRunState.QUEUED && running,
                waitingForWifi = snapshot.state == DownloadRunState.QUEUED && !running && snapshot.waitingForNetwork,
            )
        }
}

private fun DownloadSnapshot.isAsrRequestFor(modelId: String): Boolean =
    request is DownloadRequest.Asr && request.modelId == modelId
