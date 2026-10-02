package io.github.alexeikramm.sovereign.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loudness and quiet time. This is the measurement that tells a pause apart from a slow decoder, and the
 * bug it exists for was reported from the phone: "even if I'm talking, sometimes the segment just gets
 * chopped" (D-038).
 *
 * Frames are one second (16 000 samples) for readability.
 */
class AudioLevelTest {

    private fun frame(amplitude: Float) = FloatArray(SECOND) { amplitude }
    private fun alternating(amplitude: Float) = FloatArray(SECOND) { if (it % 2 == 0) amplitude else -amplitude }

    @Test
    fun `rms is the level of the frame`() {
        assertEquals(0.5, AudioLevel.rms(frame(0.5f)), 0.0001)
        assertEquals(0.25, AudioLevel.rms(alternating(0.25f)), 0.0001)
        assertEquals(0.0, AudioLevel.rms(FloatArray(0)), 0.0001)
    }

    @Test
    fun `quiet time accumulates over silent frames and resets on speech`() {
        val level = AudioLevel()
        level.onFrame(frame(0.05f))                       // speech: sets the reference
        assertEquals("speech is not quiet", 0.0, level.quietFor, 0.0001)
        level.onFrame(frame(0.0f))
        assertEquals(1.0, level.quietFor, 0.0001)
        level.onFrame(frame(0.0f))
        assertEquals(2.0, level.quietFor, 0.0001)
        level.onFrame(frame(0.05f))                       // talking again
        assertEquals("quiet time restarts", 0.0, level.quietFor, 0.0001)
    }

    @Test
    fun `a loud frame after a quiet spell is speech, however brief`() {
        val level = AudioLevel()
        level.onFrame(frame(0.0f))
        level.onFrame(frame(0.0f))
        assertEquals(2.0, level.quietFor, 0.0001)
        level.onFrame(frame(0.2f))
        assertTrue(level.heardSpeech)
        assertEquals(0.0, level.quietFor, 0.0001)
    }

    @Test
    fun `someone still talking is never reported as quiet — the bug this class exists for`() {
        // A stalled decoder looks identical to silence if you only watch the text. The audio does not:
        // as long as the speaker keeps talking, quiet time stays at zero and the fallback cannot cut.
        val level = AudioLevel()
        repeat(6) { level.onFrame(frame(0.08f)) }
        assertEquals(0.0, level.quietFor, 0.0001)
        assertTrue(level.heardSpeech)
    }

    @Test
    fun `the reference decays, so a quiet passage later is still judged against speech`() {
        val level = AudioLevel()
        level.onFrame(frame(0.3f))                        // one loud syllable sets the reference
        val before = level.level
        repeat(20) { level.onFrame(frame(0.001f)) }       // 20 s of near-silence
        assertTrue("the reference decays", level.level < before)
        assertTrue("but keeps a floor", level.level >= AudioLevel.MIN_REFERENCE - 0.0001)
    }

    @Test
    fun `a silent room has a floor, so speech in it is still speech`() {
        val level = AudioLevel()
        level.onFrame(frame(0.0005f))                     // room tone only
        assertTrue("room tone is quiet", level.quietFor > 0.0)
        assertFalse("and nothing loud has been heard", level.heardSpeech)

        level.onFrame(frame(0.02f))                       // a soft voice in that room
        assertEquals("a soft voice is not quiet", 0.0, level.quietFor, 0.0001)
        assertTrue(level.heardSpeech)
    }

    private companion object {
        const val SECOND = 16_000
    }
}
