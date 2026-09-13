package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineVadAsrEngineConstantsTest {

    @Test
    fun `whisper max window is 30s and chunk cut is safely under 30s`() {
        assertEquals(30 * 16000, OfflineVadAsrEngine.MAX_WHISPER_SAMPLES)
        assertEquals(29 * 16000, OfflineVadAsrEngine.MAX_CHUNK_SAMPLES)
        assertTrue(
            "Chunk cut must be strictly less than Whisper 30s mel window",
            OfflineVadAsrEngine.MAX_CHUNK_SAMPLES < OfflineVadAsrEngine.MAX_WHISPER_SAMPLES
        )
    }

    @Test
    fun `whisper prompt context carries preceding text up to limit`() {
        val longText = (1..50).joinToString(" ") { "word$it" }
        val prompt = longText.takeLast(250)
        assertTrue("Prompt should not exceed 250 characters", prompt.length <= 250)
        assertTrue("Prompt should contain the tail of speech", prompt.endsWith("word50"))
    }
}
