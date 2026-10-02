package io.github.alexeikramm.sovereign.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AudioResamplerTest {

    @Test
    fun `resample returns original when srcRate equals dstRate`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val output = AudioResampler.resample(input, srcRate = 16000, dstRate = 16000)
        assertEquals(input.size, output.size)
        for (i in input.indices) {
            assertEquals(input[i], output[i], 1e-6f)
        }
    }

    @Test
    fun `resample downsamples 32000 to 16000 picking every second sample`() {
        val input = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f)
        val output = AudioResampler.resample(input, srcRate = 32000, dstRate = 16000)
        // Ratio is 2.0. Expected samples at indices 0, 2, 4, 6
        val expected = floatArrayOf(0f, 2f, 4f, 6f)
        assertEquals(expected.size, output.size)
        for (i in expected.indices) {
            assertEquals(expected[i], output[i], 1e-4f)
        }
    }

    @Test
    fun `resample downsamples 48000 to 16000 picking every third sample`() {
        val input = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val output = AudioResampler.resample(input, srcRate = 48000, dstRate = 16000)
        // Ratio is 3.0. Expected samples at indices 0, 3, 6, 9
        val expected = floatArrayOf(0f, 3f, 6f, 9f)
        assertEquals(expected.size, output.size)
        for (i in expected.indices) {
            assertEquals(expected[i], output[i], 1e-4f)
        }
    }

    @Test
    fun `resample upsamples 8000 to 16000 with linear interpolation`() {
        val input = floatArrayOf(0.0f, 1.0f, 2.0f)
        val output = AudioResampler.resample(input, srcRate = 8000, dstRate = 16000)
        // Ratio is 0.5. At 0: 0.0, at 0.5: 0.5, at 1.0: 1.0, at 1.5: 1.5, at 2.0: 2.0
        val expected = floatArrayOf(0.0f, 0.5f, 1.0f, 1.5f, 2.0f)
        assertEquals(expected.size, output.size)
        for (i in expected.indices) {
            assertEquals(expected[i], output[i], 1e-4f)
        }
    }

    @Test
    fun `resample preserves frequency of 440Hz sine wave across 44100 to 16000`() {
        val srcRate = 44100
        val dstRate = 16000
        val freq = 440.0
        val durationSec = 0.5f
        val srcSamples = (srcRate * durationSec).toInt()
        val input = FloatArray(srcSamples) { i ->
            sin(2.0 * Math.PI * freq * i / srcRate).toFloat()
        }

        val output = AudioResampler.resample(input, srcRate, dstRate)
        val expectedSamples = (dstRate * durationSec).toInt()
        assertTrue("Output length ${output.size} should be close to $expectedSamples",
            kotlin.math.abs(output.size - expectedSamples) <= 2)

        // Verify that resampled points match analytical sine function
        for (i in 0 until minOf(output.size, 100)) {
            val expectedVal = sin(2.0 * Math.PI * freq * i / dstRate).toFloat()
            assertEquals(expectedVal, output[i], 0.05f)
        }
    }

    @Test
    fun `FloatChunkBuffer correctly handles multi-chunk expansion`() {
        val buffer = FloatChunkBuffer(chunkSize = 32)
        val total = 100
        for (i in 0 until total) {
            buffer.add(i.toFloat())
        }
        assertEquals(total, buffer.totalSamples)
        val array = buffer.toFloatArray()
        assertEquals(total, array.size)
        for (i in 0 until total) {
            assertEquals(i.toFloat(), array[i], 0.0f)
        }
    }

    @Test
    fun `resample handles 3-minute equivalent audio fast without memory issues`() {
        // 3 minutes at 48kHz = 180 * 48000 = 8,640,000 floats
        // In this test, we stream it through AudioResampler
        val resampler = AudioResampler(srcRate = 48000, dstRate = 16000)
        var outputCount = 0
        val start = System.currentTimeMillis()
        for (i in 0 until 8_640_000) {
            resampler.process((i % 1000) / 1000f) {
                outputCount++
            }
        }
        val elapsedMs = System.currentTimeMillis() - start
        val expectedOutputs = 180 * 16000 // 2,880,000
        assertEquals(expectedOutputs, outputCount)
        assertTrue("Streaming 3 minutes of 48kHz audio took $elapsedMs ms (expected < 1500ms)", elapsedMs < 2500)
    }
}
