package fi.ak.smartvoice.net

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Minimal `multipart/form-data` writer (RFC 7578) for the one request v1 makes.
 *
 * Hand-rolled rather than pulled from a client library for two reasons: it keeps the app free of
 * an HTTP dependency whose Android artifact demands a newer compileSdk than the project is pinned
 * to, and it makes the exact bytes on the wire assertable in a JVM unit test — the server parses
 * the `audio` field by name, so a header typo would look like a model problem instead of a
 * transport bug.
 *
 * Header values are written as ASCII; only the payload may be binary.
 */
class MultipartForm(
    private val parts: List<Part>,
    private val boundary: String = "----SmartVoiceBoundary" + UUID.randomUUID().toString().replace("-", ""),
) {

    class Part private constructor(
        val name: String,
        val value: String?,
        val bytes: ByteArray?,
        val filename: String?,
        val contentType: String?,
    ) {
        companion object {
            fun field(name: String, value: String) = Part(name, value, null, null, null)

            fun file(name: String, filename: String, contentType: String, bytes: ByteArray) =
                Part(name, null, bytes, filename, contentType)
        }
    }

    val contentTypeHeader: String get() = "multipart/form-data; boundary=$boundary"

    fun toByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii("Content-Disposition: form-data; name=\"${part.name}\"")
            part.filename?.let { out.writeAscii("; filename=\"$it\"") }
            out.writeAscii("\r\n")
            part.contentType?.let { out.writeAscii("Content-Type: $it\r\n") }
            out.writeAscii("\r\n")
            if (part.bytes != null) out.write(part.bytes) else out.writeAscii(part.value.orEmpty())
            out.writeAscii("\r\n")
        }
        out.writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    companion object {
        const val AUDIO_FIELD = "audio"
        const val AUDIO_FILENAME = "clip.wav"
        const val WAV_CONTENT_TYPE = "audio/wav"

        /** The exact body `/v1/transcribe` expects: audio file plus its optional form fields. */
        fun transcribeRequest(
            wav: ByteArray,
            cleanup: String,
            language: String,
            hotwords: List<String>,
        ): MultipartForm = MultipartForm(
            buildList {
                add(Part.file(AUDIO_FIELD, AUDIO_FILENAME, WAV_CONTENT_TYPE, wav))
                add(Part.field("cleanup", cleanup))
                add(Part.field("language", language))
                hotwords.forEach { add(Part.field("hotwords", it)) }
            },
        )
    }
}
