package fi.ak.smartvoice.asr

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the pinned ASR artifact into app-private storage, resumably and verifiably.
 *
 * Design points that are deliberate:
 * - **It writes to `<name>.part` and renames on success.** A half-written `encoder.int8.onnx`
 *   would otherwise pass the length check of a later run at exactly the wrong moment.
 * - **Resume is a `Range` request, and a server that ignores it is handled, not trusted.** If the
 *   response is 200 rather than 206 the partial file is thrown away and the download restarts;
 *   appending a full-body response to a partial file would corrupt it silently.
 * - **Cancelling keeps the parts.** The user cancelling a 681 MB download on mobile data is a
 *   normal thing to do, and paying for the same bytes twice is not.
 * - **Verification is size first, digest second.** The size gate is free and catches the common
 *   failure (a truncated stream); the digest then catches the one that matters (upstream artifact
 *   changed at the pinned revision).
 *
 * The model directory is app-private `filesDir`; no storage permission is involved anywhere.
 */
class ModelDownloader(
    private val directory: File,
    private val onProgress: (ModelDownloadProgress) -> Unit = {},
) {

    fun download(isCancelled: () -> Boolean = { false }): ModelDownloadResult {
        if (!directory.isDirectory && !directory.mkdirs()) {
            return ModelDownloadResult.Failed("cannot create ${directory.absolutePath}")
        }

        var completedBytes = 0L
        AsrModels.FILES.forEachIndexed { index, file ->
            val target = File(directory, file.name)

            // Already there and the right length: a re-run after an interrupted download must not
            // re-fetch a 657 MB encoder because a 131 KB tokens.txt was missing.
            if (target.isFile && target.length() == file.bytes) {
                completedBytes += file.bytes
                report(completedBytes, 0, index, file)
                return@forEachIndexed
            }

            val part = File(directory, "${file.name}.part")
            when (val result = fetch(file, part, target, index, completedBytes, isCancelled)) {
                is ModelDownloadResult.Failed -> return result
                is ModelDownloadResult.Cancelled -> return result
                ModelDownloadResult.Complete -> completedBytes += file.bytes
            }
            report(completedBytes, 0, index, file)
        }

        val incomplete = AsrModels.incomplete(directory)
        return if (incomplete.isEmpty()) {
            ModelDownloadResult.Complete
        } else {
            ModelDownloadResult.Failed("download finished but ${incomplete.joinToString { it.name }} is missing")
        }
    }

    private fun fetch(
        file: AsrModelFile,
        part: File,
        target: File,
        index: Int,
        completedBytes: Long,
        isCancelled: () -> Boolean,
    ): ModelDownloadResult {
        val offset = DownloadPolicy.resumeOffset(part.length(), file.bytes)
        val connection = (URL(AsrModels.downloadUrl(file)).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

        return try {
            connection.connect()
            val code = connection.responseCode
            val resuming = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return ModelDownloadResult.Failed("HTTP $code while fetching ${file.name}")
            }
            // A 200 answer to a Range request means the server sent the whole file: start over.
            if (!resuming) part.delete()

            var written = if (resuming) part.length() else 0L
            FileOutputStream(part, resuming).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var sinceReport = 0L
                    while (true) {
                        if (isCancelled()) return ModelDownloadResult.Cancelled(written)
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        sinceReport += read
                        if (sinceReport >= PROGRESS_STEP_BYTES) {
                            sinceReport = 0
                            report(completedBytes, written, index, file)
                        }
                    }
                }
            }

            if (written != file.bytes) {
                // Keep the part: a short stream is almost always a dropped connection, and the
                // next attempt resumes from exactly here.
                return ModelDownloadResult.Failed(
                    "${file.name}: got $written bytes, expected ${file.bytes} — will resume on retry",
                )
            }

            val digest = AsrModels.sha256(part)
            if (!DownloadPolicy.accepted(written, file.bytes, digest, file.sha256)) {
                part.delete()
                return ModelDownloadResult.Failed(
                    "${file.name}: sha256 mismatch (got ${digest.take(12)}…, expected " +
                        "${file.sha256.take(12)}…) — the pinned artifact changed upstream",
                )
            }

            if (!part.renameTo(target)) {
                return ModelDownloadResult.Failed("could not move ${file.name} into place")
            }
            ModelDownloadResult.Complete
        } finally {
            connection.disconnect()
        }
    }

    private fun report(completedBytes: Long, currentFileBytes: Long, index: Int, file: AsrModelFile) {
        onProgress(
            ModelDownloadProgress(
                overall = DownloadPolicy.overallProgress(completedBytes, currentFileBytes, AsrModels.TOTAL_BYTES),
                fileIndex = index,
                fileName = file.name,
                completedFiles = AsrModels.FILES.count { f -> File(directory, f.name).length() == f.bytes },
                totalFiles = AsrModels.FILES.size,
            ),
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 1 shl 20
        private const val PROGRESS_STEP_BYTES = 4L shl 20
    }
}
