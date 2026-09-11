package fi.ak.smartvoice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The WAV header is the one thing about the audio path that can be verified without a device,
 * and it is also where a silent bug would cost the most: a wrong header means the server either
 * rejects the upload or misreads the sample rate, and the symptom (garbage text) looks like a
 * model problem.
 */
class WavWriterTest {

    @Test
    fun `header is 44 bytes and declares riff wave pcm`() {
        val out = WavWriter.build(ByteArray(8), sampleRate = 16_000)

        assertEquals(44 + 8, out.size)
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(out, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(out, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun `16 kHz mono s16le headers match the values the server expects`() {
        val out = WavWriter.build(ByteArray(3_200), sampleRate = 16_000)

        assertEquals(3_200 + 36, le32(out, 4))            // RIFF chunk size
        assertEquals(16, le32(out, 16))                   // PCM fmt chunk size
        assertEquals(1, le16(out, 20))                    // audio format = PCM
        assertEquals(1, le16(out, 22))                    // channels = mono
        assertEquals(16_000, le32(out, 24))               // sample rate
        assertEquals(32_000, le32(out, 28))               // byte rate = 16000 * 1 * 2
        assertEquals(2, le16(out, 32))                    // block align
        assertEquals(16, le16(out, 34))                   // bits per sample
        assertEquals(3_200, le32(out, 40))                // data chunk size
    }

    @Test
    fun `pcm payload is copied through untouched`() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val out = WavWriter.build(pcm, sampleRate = 16_000)

        assertArrayEquals(pcm, out.copyOfRange(44, out.size))
    }

    @Test
    fun `stereo doubles byte rate and block align`() {
        val out = WavWriter.build(ByteArray(4), sampleRate = 16_000, channels = 2)

        assertEquals(2, le16(out, 22))
        assertEquals(64_000, le32(out, 28))
        assertEquals(4, le16(out, 32))
    }

    @Test
    fun `one second of mono audio is 32000 bytes of payload`() {
        // Guards the arithmetic the recorder's capturedSeconds derives from.
        val oneSecond = ByteArray(WavRecorder.SAMPLE_RATE * 2)
        val out = WavWriter.build(oneSecond, sampleRate = WavRecorder.SAMPLE_RATE)

        assertEquals(32_000, le32(out, 40))
    }

    private fun le16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, offset: Int): Int =
        le16(b, offset) or (le16(b, offset + 2) shl 16)
}
