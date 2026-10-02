package io.github.alexeikramm.sovereign.download

/**
 * The decisions a resumable download has to make, isolated so they can be tested without a
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

    /**
     * Whether an HTTP status is worth another attempt. A 5xx is the server having a bad moment.
     * A 4xx is this request being wrong — a moved artifact, a bad URL — and retrying only delays
     * the inevitable; 408 (request timeout) and 429 (too many requests) are the two transient ones.
     */
    fun httpIsRetryable(code: Int): Boolean = code >= 500 || code == 408 || code == 429
}
