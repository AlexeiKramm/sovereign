package io.github.alexeikramm.sovereign.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the exact bytes the app would upload to `build/wire/`, so they can be parsed by the
 * server's own multipart implementation (`tools/verify_multipart.py`, which uses FastAPI /
 * python-multipart — the library the real server runs).
 *
 * The unit tests in MultipartFormTest assert the format against RFC 7578 as we understand it.
 * This artifact exists because our understanding is not the oracle: the server's parser is, and a
 * body it rejects would surface on the phone as "server returns bad_input", which reads like a
 * model or audio problem rather than a transport bug.
 */
class MultipartWireDumpTest {

    @Test
    fun `dumps the production request body for cross-implementation verification`() {
        // A payload with bytes that stress the framing: NUL, CR, LF and a CRLF pair.
        val wav = ByteArray(512) { (it % 256).toByte() }
        wav[10] = 0x0D
        wav[11] = 0x0A
        wav[20] = 0x00

        val form = MultipartForm.transcribeRequest(
            wav = wav,
            cleanup = "full",
            language = "auto",
            hotwords = listOf("Umacut", "Parakeet"),
        )

        val outDir = File("build/wire").apply { mkdirs() }
        File(outDir, "transcribe-request.bin").writeBytes(form.toByteArray())
        File(outDir, "expected-audio.bin").writeBytes(wav)
        File(outDir, "content-type.txt").writeText(form.contentTypeHeader)

        val written = File(outDir, "transcribe-request.bin")
        assertTrue("wire artifact must be non-empty", written.length() > wav.size.toLong())
    }
}
