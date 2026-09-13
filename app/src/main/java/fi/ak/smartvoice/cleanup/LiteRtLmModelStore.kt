package fi.ak.smartvoice.cleanup

import android.content.Context
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.DownloadPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class LiteRtLmDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Float,
)

sealed interface LiteRtLmDownloadResult {
    data object Complete : LiteRtLmDownloadResult
    data object Cancelled : LiteRtLmDownloadResult
    data class Failed(val reason: String) : LiteRtLmDownloadResult
}

object LiteRtLmModelStore {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 64 * 1024

    fun getDirectory(context: Context): File {
        val dir = File(context.filesDir, "models/litertlm")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(context: Context, spec: LiteRtLmModelSpec): File {
        return File(getDirectory(context), spec.fileName)
    }

    fun isInstalled(context: Context, spec: LiteRtLmModelSpec): Boolean {
        val file = getModelFile(context, spec)
        return file.isFile && file.length() == spec.approximateBytes
    }

    fun installedBytes(context: Context, spec: LiteRtLmModelSpec): Long {
        val file = getModelFile(context, spec)
        return if (file.isFile) file.length() else 0L
    }

    suspend fun remove(context: Context, spec: LiteRtLmModelSpec): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = getModelFile(context, spec)
                val part = File(getDirectory(context), "${spec.fileName}.part")
                if (file.exists()) file.delete()
                if (part.exists()) part.delete()
                Unit
            }
        }

    fun downloadFlow(
        context: Context,
        spec: LiteRtLmModelSpec,
        isCancelled: () -> Boolean = { false },
    ): Flow<Pair<LiteRtLmDownloadProgress, LiteRtLmDownloadResult?>> = flow {
        val dir = getDirectory(context)
        val target = File(dir, spec.fileName)
        val part = File(dir, "${spec.fileName}.part")

        if (target.isFile && target.length() == spec.approximateBytes) {
            emit(
                LiteRtLmDownloadProgress(spec.approximateBytes, spec.approximateBytes, 1.0f) to
                    LiteRtLmDownloadResult.Complete
            )
            return@flow
        }

        val resumeOffset = DownloadPolicy.resumeOffset(part.length(), spec.approximateBytes)
        val conn = (URL(spec.downloadUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (resumeOffset > 0L) {
                setRequestProperty("Range", "bytes=$resumeOffset-")
            }
        }

        try {
            val responseCode = conn.responseCode
            val append = when (responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> {
                    if (part.exists()) part.delete()
                    false
                }
                else -> {
                    emit(
                        LiteRtLmDownloadProgress(0, spec.approximateBytes, 0f) to
                            LiteRtLmDownloadResult.Failed("HTTP $responseCode from server")
                    )
                    return@flow
                }
            }

            val startingBytes = if (append) resumeOffset else 0L
            var currentBytes = startingBytes

            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var read: Int
                    var lastEmittedBytes = currentBytes

                    while (input.read(buffer).also { read = it } != -1) {
                        if (isCancelled()) {
                            emit(
                                LiteRtLmDownloadProgress(currentBytes, spec.approximateBytes, currentBytes.toFloat() / spec.approximateBytes) to
                                    LiteRtLmDownloadResult.Cancelled
                            )
                            return@flow
                        }
                        output.write(buffer, 0, read)
                        currentBytes += read
                        if (currentBytes - lastEmittedBytes > 512 * 1024 || currentBytes == spec.approximateBytes) {
                            val pct = if (spec.approximateBytes > 0) currentBytes.toFloat() / spec.approximateBytes else 0f
                            emit(
                                LiteRtLmDownloadProgress(currentBytes, spec.approximateBytes, pct) to null
                            )
                            lastEmittedBytes = currentBytes
                        }
                    }
                    output.flush()
                }
            }

            if (part.length() != spec.approximateBytes) {
                emit(
                    LiteRtLmDownloadProgress(part.length(), spec.approximateBytes, 0f) to
                        LiteRtLmDownloadResult.Failed("size mismatch: got ${part.length()} expected ${spec.approximateBytes}")
                )
                return@flow
            }

            val digest = AsrModels.sha256(part)
            if (digest != spec.sha256) {
                emit(
                    LiteRtLmDownloadProgress(part.length(), spec.approximateBytes, 0f) to
                        LiteRtLmDownloadResult.Failed("digest mismatch: got $digest expected ${spec.sha256}")
                )
                return@flow
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                emit(
                    LiteRtLmDownloadProgress(part.length(), spec.approximateBytes, 0f) to
                        LiteRtLmDownloadResult.Failed("failed to rename .part to destination")
                )
                return@flow
            }

            emit(
                LiteRtLmDownloadProgress(spec.approximateBytes, spec.approximateBytes, 1.0f) to
                    LiteRtLmDownloadResult.Complete
            )
        } catch (e: Exception) {
            emit(
                LiteRtLmDownloadProgress(downloadedBytes = 0, totalBytes = spec.approximateBytes, percent = 0f) to
                    LiteRtLmDownloadResult.Failed(e.message ?: "network error")
            )
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
