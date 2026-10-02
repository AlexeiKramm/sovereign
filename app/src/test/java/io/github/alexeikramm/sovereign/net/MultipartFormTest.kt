package io.github.alexeikramm.sovereign.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multipart body is the one part of the transport that can be pinned without a server, and a
 * mistake there fails in a way that looks like a model problem (the server never sees an `audio`
 * field, or sees a truncated one). RFC 7578 CRLF discipline is asserted explicitly.
 */
class MultipartFormTest {

    private val wav = ByteArray(64) { it.toByte() } // includes 0x00 and 0x3F: binary must survive

    @Test
    fun `content type header carries the boundary`() {
        val form = MultipartForm.transcribeRequest(wav, "full", "auto", emptyList())

        assertTrue(form.contentTypeHeader.startsWith("multipart/form-data; boundary="))
        val boundary = form.contentTypeHeader.substringAfter("boundary=")

        val body = String(form.toByteArray(), Charsets.ISO_8859_1)
        assertTrue("body must open with the boundary", body.startsWith("--$boundary\r\n"))
        assertTrue("body must close with the terminator", body.endsWith("--$boundary--\r\n"))
        assertEquals("one boundary per part plus the terminator", 4, Regex(Regex.escape("--$boundary")).findAll(body).count())
    }

    @Test
    fun `audio part is named audio and typed as a wav file`() {
        val form = MultipartForm.transcribeRequest(wav, "off", "auto", emptyList())
        val body = String(form.toByteArray(), Charsets.ISO_8859_1)

        assertTrue(
            body.contains(
                "Content-Disposition: form-data; name=\"audio\"; filename=\"clip.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n",
            ),
        )
    }

    @Test
    fun `form fields match the contract's parameter names`() {
        val form = MultipartForm.transcribeRequest(wav, "full", "fi", listOf("Umacut", "Parakeet"))
        val body = String(form.toByteArray(), Charsets.ISO_8859_1)

        assertTrue(body.contains("name=\"cleanup\"\r\n\r\nfull\r\n"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nfi\r\n"))
        assertEquals("hotwords repeat as separate parts", 2, Regex("name=\"hotwords\"").findAll(body).count())
        assertTrue(body.contains("name=\"hotwords\"\r\n\r\nUmacut\r\n"))
    }

    @Test
    fun `payload bytes are copied verbatim`() {
        val form = MultipartForm.transcribeRequest(wav, "full", "auto", emptyList())
        val bytes = form.toByteArray()

        val start = bytes.indexOfSubArray(wav)
        assertTrue("payload must appear in the body", start > 0)
        assertArrayEquals(wav, bytes.copyOfRange(start, start + wav.size))
    }

    @Test
    fun `payload is followed by crlf before the closing boundary`() {
        val form = MultipartForm.transcribeRequest(byteArrayOf(1, 2, 3), "full", "auto", emptyList())
        val bytes = form.toByteArray()
        val payloadEnd = bytes.indexOfSubArray(byteArrayOf(1, 2, 3)) + 3

        assertEquals("\r\n", String(bytes.copyOfRange(payloadEnd, payloadEnd + 2), Charsets.US_ASCII))
    }

    @Test
    fun `a part without a filename carries no content type`() {
        val form = MultipartForm(listOf(MultipartForm.Part.field("cleanup", "off")), boundary = "B")
        val body = String(form.toByteArray(), Charsets.ISO_8859_1)

        assertEquals("--B\r\nContent-Disposition: form-data; name=\"cleanup\"\r\n\r\noff\r\n--B--\r\n", body)
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
