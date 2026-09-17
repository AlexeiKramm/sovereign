package fi.ak.smartvoice.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One file to fetch: where from, how big it must be, and the digest that proves it. */
data class RemoteFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
)

/**
 * Progress across the whole artifact, not one file: a 657 MB encoder followed by a 131 KB
 * `tokens.txt` must not make the bar jump.
 */
data class DownloadProgress(
    val overall: Float,
    val fileName: String,
    val fileIndex: Int,
    val completedFiles: Int,
    val totalFiles: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** Which stage of a multi-stage request this is, or null for a single-stage download. */
    val stage: String? = null,
) {
    companion object {
        /** A zeroed bar for an artifact nobody has fetched a byte of yet. */
        fun initial(files: List<RemoteFile>): DownloadProgress = DownloadProgress(
            overall = 0f,
            fileName = files.firstOrNull()?.name.orEmpty(),
            fileIndex = 0,
            completedFiles = 0,
            totalFiles = files.size,
            downloadedBytes = 0L,
            totalBytes = files.sumOf { it.bytes },
        )
    }
}

/** Where a download attempt ended. */
sealed interface DownloadOutcome {
    data object Complete : DownloadOutcome

    /** Cancelled keeps the `.part` files, so resuming is free. */
    data class Cancelled(val downloadedBytes: Long) : DownloadOutcome

    /**
     * [retryable] is the downloader's own judgement, made where the failure actually happened: a
     * dropped stream is worth another attempt, a digest mismatch is not.
     */
    data class Failed(val reason: String, val retryable: Boolean) : DownloadOutcome
}

/**
 * Downloads one artifact — one or more pinned files — into app-private storage, resumably and
 * verifiably. This is the single implementation behind both the speech and the cleanup models.
 *
 * Design points that are deliberate:
 * - **It writes to `<name>.part` and renames on success.** A half-written `encoder.int8.onnx`
 *   would otherwise pass the length check of a later run at exactly the wrong moment.
 * - **Resume is a `Range` request, and a server that ignores it is handled, not trusted.** If the
 *   response is 200 rather than 206 the partial file is thrown away and the download restarts;
 *   appending a full-body response to a partial file would corrupt it silently.
 * - **Cancelling keeps the parts.** The user cancelling a 681 MB download on mobile data is a
 *   normal thing to do, and paying for the same bytes twice is not.
 * - **A short stream is transient, a bad digest is not.** A connection that drops mid-file leaves
 *   the `.part` in place for the next attempt; a file whose length is right but whose content is
 *   not means the pinned artifact changed upstream, so the bad bytes are deleted rather than
 *   retried forever.
 * - **No exception escapes.** A network error is a `Failed(retryable = true)`, not a crash: this
 *   runs inside a worker where an uncaught `IOException` would take the process with it.
 *
 * The artifact directory is app-private `filesDir`; no storage permission is involved anywhere.
 */
class ArtifactDownloader(
    private val directory: File,
    private val files: List<RemoteFile>,
    private val onProgress: (DownloadProgress) -> Unit = {},
    private val progressStepBytes: Long = DEFAULT_PROGRESS_STEP_BYTES,
) {

    fun download(isCancelled: () -> Boolean = { false }): DownloadOutcome {
        if (!directory.isDirectory && !directory.mkdirs()) {
            return DownloadOutcome.Failed("cannot create ${directory.absolutePath}", retryable = false)
        }

        val totalBytes = files.sumOf { it.bytes }
        var completedBytes = 0L

        files.forEachIndexed { index, file ->
            val target = File(directory, file.name)

            // Already there and the right length: a re-run after an interrupted download must not
            // re-fetch a 657 MB encoder because a 131 KB tokens.txt was missing.
            if (target.isFile && target.length() == file.bytes) {
                completedBytes += file.bytes
                report(index, file, completedBytes, 0L, totalBytes)
                return@forEachIndexed
            }

            val part = File(directory, "${file.name}.part")
            when (val result = fetch(file, part, target, index, completedBytes, totalBytes, isCancelled)) {
                is DownloadOutcome.Failed -> return result
                is DownloadOutcome.Cancelled -> return result
                DownloadOutcome.Complete -> completedBytes += file.bytes
            }
            report(index, file, completedBytes, 0L, totalBytes)
        }

        val incomplete = files.filter { file ->
            val target = File(directory, file.name)
            !(target.isFile && target.length() == file.bytes)
        }
        return if (incomplete.isEmpty()) {
            DownloadOutcome.Complete
        } else {
            DownloadOutcome.Failed(
                "download finished but ${incomplete.joinToString { it.name }} is missing",
                retryable = true,
            )
        }
    }

    private fun fetch(
        file: RemoteFile,
        part: File,
        target: File,
        index: Int,
        completedBytes: Long,
        totalBytes: Long,
        isCancelled: () -> Boolean,
    ): DownloadOutcome {
        val offset = DownloadPolicy.resumeOffset(part.length(), file.bytes)
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }

            val code = connection.responseCode
            val resuming = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return DownloadOutcome.Failed(
                    "HTTP $code while fetching ${file.name}",
                    retryable = DownloadPolicy.httpIsRetryable(code),
                )
            }
            // A 200 answer to a Range request means the server sent the whole file: start over.
            if (!resuming) part.delete()

            var written = if (resuming) part.length() else 0L
            FileOutputStream(part, resuming).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var sinceReport = 0L
                    while (true) {
                        if (isCancelled()) {
                            return DownloadOutcome.Cancelled(completedBytes + written)
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        sinceReport += read
                        if (sinceReport >= progressStepBytes) {
                            sinceReport = 0
                            report(index, file, completedBytes, written, totalBytes)
                        }
                    }
                }
            }

            if (written != file.bytes) {
                // Keep the part: a short stream is almost always a dropped connection, and the
                // next attempt resumes from exactly here.
                return DownloadOutcome.Failed(
                    "${file.name}: got $written bytes, expected ${file.bytes} — will resume on retry",
                    retryable = true,
                )
            }

            val digest = sha256(part)
            if (!DownloadPolicy.accepted(written, file.bytes, digest, file.sha256)) {
                part.delete()
                return DownloadOutcome.Failed(
                    "${file.name}: sha256 mismatch (got ${digest.take(12)}…, expected " +
                        "${file.sha256.take(12)}…) — the pinned artifact changed upstream",
                    retryable = false,
                )
            }

            if (!part.renameTo(target)) {
                return DownloadOutcome.Failed("could not move ${file.name} into place", retryable = false)
            }
            DownloadOutcome.Complete
        } catch (e: Exception) {
            // A dropped connection, a read timeout, a DNS failure: all worth another attempt once
            // the `.part` file is there to resume from. Anything else is reported as terminal so it
            // cannot loop, and nothing escapes to crash the process.
            DownloadOutcome.Failed(
                "${file.name}: ${e.message ?: e::class.java.simpleName}",
                retryable = e is IOException,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun report(
        index: Int,
        file: RemoteFile,
        completedBytes: Long,
        currentFileBytes: Long,
        totalBytes: Long,
    ) {
        onProgress(
            DownloadProgress(
                overall = DownloadPolicy.overallProgress(completedBytes, currentFileBytes, totalBytes),
                fileName = file.name,
                fileIndex = index,
                completedFiles = files.count { remote ->
                    val target = File(directory, remote.name)
                    target.isFile && target.length() == remote.bytes
                },
                totalFiles = files.size,
                downloadedBytes = completedBytes + currentFileBytes,
                totalBytes = totalBytes,
            ),
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 1 shl 20
        private const val DEFAULT_PROGRESS_STEP_BYTES = 4L shl 20
    }
}
