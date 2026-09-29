package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAsrEngineConstantsTest {

    @Test
    fun `streaming tail padding is strictly greater than largest transducer chunk duration`() {
        // Nemotron 560ms chunk duration is 0.56s, 320ms chunk duration is 0.32s
        val maxChunkDurationSeconds = 0.56f
        assertTrue(
            "Tail padding duration must be strictly greater than max streaming chunk size to guarantee final chunk decode",
            LocalAsrEngine.STREAMING_TAIL_PADDING_SECONDS > maxChunkDurationSeconds
        )
    }

    @Test
    fun `streaming tail padding samples calculation at 16kHz`() {
        val expectedSamples = (16_000 * 0.8f).toInt()
        assertEquals(12_800, expectedSamples)
        val actualSamples = (AsrModels.SAMPLE_RATE * LocalAsrEngine.STREAMING_TAIL_PADDING_SECONDS).toInt()
        assertEquals(expectedSamples, actualSamples)
    }
}
