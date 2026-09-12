package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversion from `AudioRecord`'s little-endian s16 bytes to the floats the on-device runtime
 * wants. Tested at the byte level because the failure mode is silent: swapped byte order does not
 * crash anything, it just makes the model transcribe noise.
 */
class AudioFramesTest {

    @Test
    fun `little endian sample is read low byte first`() {
        // 0x0100 = 256 as little-endian: 0x00 then 0x01. Read the other way it would be 1.
        val out = FloatArray(1)
        val written = AudioFrames.toFloat(byteArrayOf(0x00, 0x01), 2, out)

        assertEquals(1, written)
        assertEquals(256f / 32768f, out[0], 1e-7f)
    }

    @Test
    fun `full scale is the short range, not an unsigned one`() {
        val out = FloatArray(2)
        // -32768 → -1.0, +32767 → just under 1.0
        AudioFrames.toFloat(byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F), 4, out)

        assertEquals(-1.0f, out[0], 1e-7f)
        assertEquals(32767f / 32768f, out[1], 1e-7f)
        assertTrue("samples must stay inside [-1, 1)", out.all { it >= -1f && it < 1f })
    }

    @Test
    fun `a trailing odd byte is ignored rather than half-converted`() {
        val out = FloatArray(2)
        val written = AudioFrames.toFloat(byteArrayOf(0x00, 0x01, 0x7F), 3, out)

        assertEquals(1, written)
    }

    @Test
    fun `count larger than the array is clamped, and out never overflows`() {
        val out = FloatArray(1)
        val written = AudioFrames.toFloat(byteArrayOf(0x01, 0x00, 0x02, 0x00), 4, out)

        assertEquals(1, written)
        assertEquals(1f / 32768f, out[0], 1e-7f)
    }

    @Test
    fun `convenience overload sizes the result from the byte count`() {
        val frames = AudioFrames.toFloat(byteArrayOf(0, 0, 0, 0), 4)
        assertEquals(2, frames.size)
    }

    @Test
    fun `odd byte count sizes the convenience result to whole samples`() {
        val frames = AudioFrames.toFloat(byteArrayOf(0, 0, 0), 3)
        assertEquals(1, frames.size)
    }
}
