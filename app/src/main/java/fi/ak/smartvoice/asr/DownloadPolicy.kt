package fi.ak.smartvoice.asr

/**
 * The two decisions a resumable download has to make, isolated so they can be tested without a
 * network, a phone, or 681 MB of model files sitting on disk.
 */
object DownloadPolicy {

    /**
     * Offset to request with a `Range` header. Zero means "start from the beginning", which is also
     * what an already-complete or nonsensical partial file must produce — a partial file larger
     * than the artifact (a swapped artifact, a corrupted length) is not evidence to resume from.
     */
    fun resumeOffset(partialBytes: Long, expectedBytes: Long): Long =
        if (partialBytes in 1 until expectedBytes) partialBytes else 0L

    /**
     * A file is accepted only when the length **and** the digest match. Length alone catches a
     * truncated download; the digest catches everything else, including the case that matters most
     * here: the artifact at the pinned revision having changed upstream.
     */
    fun accepted(sizeBytes: Long, expectedBytes: Long, actualSha256: String, expectedSha256: String): Boolean =
        sizeBytes == expectedBytes && actualSha256.equals(expectedSha256, ignoreCase = true)

    /** 0.0…1.0 across the whole artifact, not per file. */
    fun overallProgress(completedBytes: Long, currentFileBytes: Long, totalBytes: Long): Float =
        if (totalBytes <= 0) 0f else ((completedBytes + currentFileBytes).toDouble() / totalBytes)
            .coerceIn(0.0, 1.0)
            .toFloat()
}

/** Where a download attempt ended. Cancelled keeps the `.part` files, so resuming is free. */
sealed interface ModelDownloadResult {
    data object Complete : ModelDownloadResult
    data class Cancelled(val downloadedBytes: Long) : ModelDownloadResult
    data class Failed(val message: String) : ModelDownloadResult
}

data class ModelDownloadProgress(
    val overall: Float,
    val fileIndex: Int,
    val fileName: String,
    val completedFiles: Int,
    val totalFiles: Int,
)
