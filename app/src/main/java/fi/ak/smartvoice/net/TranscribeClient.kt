package fi.ak.smartvoice.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Anything the server answered that was not a 2xx, carrying the contract's own error code. */
class StackHttpException(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception(message)

/** No response at all: the tier is unreachable rather than returning an error. */
class StackUnreachableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin client for `local-voice-stack`. Two calls only in v1: a version probe and a transcribe.
 *
 * Audio is always multipart WAV 16 kHz mono s16le — the one format the server passes through
 * without an ffmpeg step, so upload cost never depends on a normalisation fallback.
 *
 * Uses `HttpURLConnection` (platform HTTP) rather than a client library: the one dependency worth
 * having here would force a newer compileSdk than the project is pinned to, and v1 makes two
 * requests to a LAN host.
 */
class TranscribeClient(private val baseUrl: String) {

    private val root: String = baseUrl.trim().trimEnd('/')

    init {
        require(root.startsWith("http://") || root.startsWith("https://")) {
            "Server URL must start with http:// or https:// (was \"$baseUrl\")"
        }
    }

    suspend fun meta(): MetaResponse = withContext(Dispatchers.IO) {
        val (status, body) = call("GET", "$root/v1/meta", contentType = null, body = null)
        if (status !in 200..299) throw errorFor(status, body)
        StackJson.decodeFromString(MetaResponse.serializer(), body)
    }

    /** Returns the parsed response and the wall-clock round trip in milliseconds. */
    suspend fun transcribe(
        wav: ByteArray,
        cleanup: String = CLEANUP_FULL,
        language: String = "auto",
        hotwords: List<String> = emptyList(),
    ): Pair<TranscribeResponse, Long> = withContext(Dispatchers.IO) {
        val form = MultipartForm.transcribeRequest(wav, cleanup, language, hotwords)
        val payload = form.toByteArray()

        val startedAt = System.nanoTime()
        val (status, body) = call("POST", "$root/v1/transcribe", form.contentTypeHeader, payload)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        if (status !in 200..299) throw errorFor(status, body)
        StackJson.decodeFromString(TranscribeResponse.serializer(), body) to elapsedMs
    }

    private fun call(
        method: String,
        url: String,
        contentType: String?,
        body: ByteArray?,
    ): Pair<Int, String> {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw StackUnreachableException("Not a usable server URL: \"$url\"", e)
        }

        return try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            contentType?.let { connection.setRequestProperty("Content-Type", it) }

            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            status to text
        } catch (e: IOException) {
            // A refused connection and a bad URL are the same user-visible fact: no server there.
            throw StackUnreachableException("Cannot reach $url — ${e.message ?: "no response"}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorFor(status: Int, body: String): StackHttpException {
        val envelope = runCatching {
            StackJson.decodeFromString(ApiErrorEnvelope.serializer(), body)
        }.getOrNull()

        val code = envelope?.error?.code
            ?: if (status == 422) "schema_validation" else "http_$status"
        val raw = envelope?.error?.message
            ?: envelope?.detail?.toString()
            ?: body.take(200).ifBlank { "no response body" }

        return StackHttpException(status, code, humanMessage(code, raw))
    }

    companion object {
        const val CLEANUP_FULL = "full"
        const val CLEANUP_OFF = "off"

        private const val CONNECT_TIMEOUT_MS = 5_000
        // Generous on purpose: the server's own target is up to ~30 s for a 5-minute clip with
        // cleanup on, so a timeout here should mean "the server is not there", not "it is thinking".
        private const val READ_TIMEOUT_MS = 120_000
    }
}
