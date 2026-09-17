package fi.ak.smartvoice.download

import android.content.Context
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.cleanup.LiteRtLmModelStore
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import java.io.File

/**
 * What a [DownloadRequest] resolves to on this device: a human label, the directory the artifact
 * lives in, and the files to fetch. Resolving through the catalogs keeps the worker ignorant of
 * both model families.
 */
data class DownloadPlan(
    val label: String,
    val directory: File,
    val files: List<RemoteFile>,
) {
    companion object {
        fun resolve(context: Context, request: DownloadRequest): DownloadPlan? = when (request) {
            is DownloadRequest.Asr -> AsrModels.byId(request.modelId).let { spec ->
                DownloadPlan(
                    label = spec.label,
                    directory = spec.directory(context.filesDir),
                    files = spec.remoteFiles(),
                )
            }

            is DownloadRequest.LiteRt -> LiteRtLmModels.byId(request.modelId).let { spec ->
                DownloadPlan(
                    label = spec.label,
                    directory = LiteRtLmModelStore.getDirectory(context),
                    files = spec.remoteFiles(),
                )
            }
        }
    }
}
