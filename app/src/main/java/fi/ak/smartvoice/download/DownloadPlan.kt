package fi.ak.smartvoice.download

import android.content.Context
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.cleanup.LiteRtLmModelStore
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import fi.ak.smartvoice.ui.onboarding.OnboardingModelPresets
import java.io.File

/**
 * Human labels for a preset's two halves. The wizard's `OnboardingDownload.Stage` reuses them, so
 * the string the worker writes into progress is the same one the screen displays.
 */
object PresetStage {
    const val SPEECH = "speech model"
    const val CLEANUP = "cleanup model"
}

/**
 * One directory full of files to fetch, with the stage name shown while it runs (null for a
 * single-request download, where there is only one thing happening).
 */
data class DownloadStage(
    val label: String?,
    val directory: File,
    val files: List<RemoteFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

/**
 * What a [DownloadRequest] resolves to on this device: a human label and one or more stages.
 * A speech or cleanup model is a single stage; a wizard preset is the speech model followed by the
 * cleanup model, which is why the worker walks a list rather than a single directory.
 */
data class DownloadPlan(
    val label: String,
    val stages: List<DownloadStage>,
) {
    /** Never zero: it is a divisor, and an empty plan should fail before it gets here. */
    val totalBytes: Long get() = stages.sumOf { it.totalBytes }.coerceAtLeast(1L)

    companion object {
        fun resolve(context: Context, request: DownloadRequest): DownloadPlan? = when (request) {
            is DownloadRequest.Asr -> AsrModels.byId(request.modelId).let { spec ->
                DownloadPlan(
                    label = spec.label,
                    stages = listOf(
                        DownloadStage(
                            label = null,
                            directory = spec.directory(context.filesDir),
                            files = spec.remoteFiles(),
                        ),
                    ),
                )
            }

            is DownloadRequest.LiteRt -> LiteRtLmModels.byId(request.modelId).let { spec ->
                DownloadPlan(
                    label = spec.label,
                    stages = listOf(
                        DownloadStage(
                            label = null,
                            directory = LiteRtLmModelStore.getDirectory(context),
                            files = spec.remoteFiles(),
                        ),
                    ),
                )
            }

            is DownloadRequest.Preset -> OnboardingModelPresets.byId(request.modelId).let { preset ->
                val stages = mutableListOf(
                    DownloadStage(
                        label = PresetStage.SPEECH,
                        directory = preset.asrModel.directory(context.filesDir),
                        files = preset.asrModel.remoteFiles(),
                    ),
                )
                preset.llmModel?.let { llm ->
                    stages.add(
                        DownloadStage(
                            label = PresetStage.CLEANUP,
                            directory = LiteRtLmModelStore.getDirectory(context),
                            files = llm.remoteFiles(),
                        ),
                    )
                }
                DownloadPlan(
                    label = preset.title,
                    stages = stages,
                )
            }
        }
    }
}

/**
 * Rewrites a stage-local progress into one across the whole plan: [completedBytes] is what earlier
 * stages already finished, and [stage]'s own `downloadedBytes`/`overall` are relative to its files.
 * Pure, so the arithmetic that makes a two-stage bar honest can be tested without a worker.
 */
internal fun DownloadProgress.acrossPlan(
    stage: DownloadStage,
    completedBytes: Long,
    planTotalBytes: Long,
): DownloadProgress {
    val downloaded = completedBytes + downloadedBytes
    return copy(
        overall = (downloaded.toFloat() / planTotalBytes).coerceIn(0f, 1f),
        downloadedBytes = downloaded,
        totalBytes = planTotalBytes,
        stage = stage.label,
    )
}
