package fi.ak.smartvoice.download

import androidx.work.Data

/**
 * What the user asked to download. A sealed type rather than a bundle of strings so the worker
 * cannot be handed a request it does not understand: an unknown kind decodes to null and the work
 * fails visibly instead of guessing.
 */
sealed interface DownloadRequest {

    /** Stable identity, used for de-duplication and for the work's `Data`. */
    val modelId: String

    val kind: String

    data class Asr(override val modelId: String) : DownloadRequest {
        override val kind: String get() = KIND_ASR
    }

    data class LiteRt(override val modelId: String) : DownloadRequest {
        override val kind: String get() = KIND_LITERT
    }

    /**
     * One wizard preset: the speech model, then the cleanup model, as a single work item. The id is
     * the preset id, not a model id — the worker resolves both halves from it.
     */
    data class Preset(override val modelId: String) : DownloadRequest {
        override val kind: String get() = KIND_PRESET
    }

    fun toData(): Data = Data.Builder()
        .putString(DownloadKeys.KIND, kind)
        .putString(DownloadKeys.REQUEST_ID, modelId)
        .build()

    /**
     * The request as a work tag. `WorkInfo` exposes its `tags` but **not** its input data, so the
     * tag is the only way the UI can tell which request a queued item is — the input `Data` is
     * readable only inside the worker.
     */
    fun tag(): String = "$TAG_PREFIX$kind:$modelId"

    companion object {
        const val KIND_ASR = "asr"
        const val KIND_LITERT = "litert"
        const val KIND_PRESET = "preset"
        const val TAG_PREFIX = "download:"

        fun from(data: Data): DownloadRequest? = when (data.getString(DownloadKeys.KIND)) {
            KIND_ASR -> data.getString(DownloadKeys.REQUEST_ID)?.let(::Asr)
            KIND_LITERT -> data.getString(DownloadKeys.REQUEST_ID)?.let(::LiteRt)
            KIND_PRESET -> data.getString(DownloadKeys.REQUEST_ID)?.let(::Preset)
            else -> null
        }

        fun fromTag(tag: String): DownloadRequest? {
            if (!tag.startsWith(TAG_PREFIX)) return null
            val rest = tag.removePrefix(TAG_PREFIX)
            val separator = rest.indexOf(':')
            if (separator <= 0 || separator == rest.length - 1) return null
            val id = rest.substring(separator + 1)
            return when (rest.substring(0, separator)) {
                KIND_ASR -> Asr(id)
                KIND_LITERT -> LiteRt(id)
                KIND_PRESET -> Preset(id)
                else -> null
            }
        }
    }
}
