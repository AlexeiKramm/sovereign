package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause boundary (D-032, D-038): when a unit ends, from the recogniser's endpoint detector and from a
 * fallback that needs **quiet audio as well as stale text**.
 *
 * The fallback's second condition is the fix for a bug reported from the phone: a recogniser that lags
 * behind its input stops producing text while the speaker keeps talking, so a rule that cut on "text
 * unchanged for N seconds" chopped sentences in half mid-word.
 *
 * Frames are one second (16 000 samples) for readability.
 */
class UnitBoundaryTest {

    @Test
    fun `text that keeps growing never cuts`() {
        val boundary = UnitBoundary()
        assertFalse(boundary.onFrame(SECOND, "one", false, QUIET))
        assertFalse(boundary.onFrame(SECOND, "one two", false, QUIET))
        assertFalse(boundary.onFrame(SECOND, "one two three", false, QUIET))
        assertEquals(0, boundary.cuts)
    }

    @Test
    fun `stale text with someone still talking is not a pause`() {
        // The bug: the recogniser fell behind, the text stopped growing, and the unit was cut mid-sentence.
        // The audio says the speaker never stopped, so no cut may happen however stale the text is.
        val boundary = UnitBoundary(stableSeconds = 1.8)
        boundary.onFrame(SECOND, "one two", false, LOUD)
        assertFalse(boundary.onFrame(SECOND, "one two", false, LOUD))   // 2 s stale, audio loud
        assertFalse(boundary.onFrame(SECOND, "one two", false, LOUD))   // 3 s stale, audio loud
        assertFalse(boundary.onFrame(SECOND, "one two", false, LOUD))   // 4 s stale, audio loud
        assertEquals(0, boundary.cuts)
    }

    @Test
    fun `stale text and quiet audio cuts once the fallback threshold passes`() {
        val boundary = UnitBoundary(stableSeconds = 1.8)
        boundary.onFrame(SECOND, "one two", false, LOUD)    // t = 1.0, the text arrived
        assertFalse(boundary.onFrame(SECOND, "one two", false, 1.0))
        assertFalse(boundary.onFrame(SECOND, "one two", false, 1.0))
        assertTrue(boundary.onFrame(SECOND, "one two", false, 2.0))
        assertEquals(1, boundary.cutsByTextStability)
        assertEquals(0, boundary.cutsByModel)
        assertEquals("the quiet time at the cut is recorded", 2.0, boundary.quietAtLastCut, 0.0001)
    }

    @Test
    fun `the recogniser's endpoint cuts immediately, however fresh the text is`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "hello there", false, QUIET)
        assertTrue(boundary.onFrame(SECOND, "hello there", true, 0.3))
        assertEquals(1, boundary.cutsByModel)
        assertEquals(0, boundary.cutsByTextStability)
        assertEquals(0.3, boundary.quietAtLastCut, 0.0001)
    }

    @Test
    fun `silence before the speaker starts never produces an empty unit`() {
        val boundary = UnitBoundary()
        repeat(4) { assertFalse(boundary.onFrame(SECOND, "", false, it + 1.0)) }
        assertEquals(0, boundary.cuts)
    }

    @Test
    fun `one pause cannot cut twice, because the caller restarts the timer`() {
        val boundary = UnitBoundary(stableSeconds = 1.8)
        boundary.onFrame(SECOND, "one two", false, QUIET)
        boundary.onFrame(SECOND, "one two", false, QUIET)
        assertTrue(boundary.onFrame(SECOND, "one two", false, QUIET))
        boundary.markCut()
        assertFalse("the timer restarted, so no second cut", boundary.onFrame(SECOND, "one two", false, QUIET))
        assertEquals(1, boundary.cuts)
    }

    @Test
    fun `the text clock counts from the last speech, and a cut does not restart it`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one two", false, LOUD)          // t = 1.0, speech at 1.0
        assertTrue(boundary.onFrame(SECOND, "one two", true, LOUD)) // t = 2.0, recogniser endpoint
        assertEquals(1.0, boundary.silenceFor, 0.0001)
        boundary.markCut()
        assertEquals("markCut must not move the speech clock", 1.0, boundary.silenceFor, 0.0001)

        // The stream was reset, so the next frame carries no text at all — still not a text change.
        assertFalse(boundary.onFrame(SECOND, "", false, LOUD))     // t = 3.0
        assertEquals(2.0, boundary.silenceFor, 0.0001)
        assertTrue(boundary.hasSpeech)
    }

    @Test
    fun `silence before the speaker starts reports no speech`() {
        val boundary = UnitBoundary()
        repeat(5) { assertFalse(boundary.onFrame(SECOND, "", false, it + 1.0)) }
        assertFalse("nothing was said, so there is nothing to stop after", boundary.hasSpeech)
        assertEquals(0.0, boundary.silenceFor, 0.0001)
        assertEquals(5.0, boundary.seconds, 0.0001)
    }

    @Test
    fun `the fallback threshold is configurable between utterances`() {
        val boundary = UnitBoundary(stableSeconds = 1.8)
        boundary.configureFallback(0.5)
        boundary.onFrame(SECOND, "one", false, QUIET)             // t = 1.0
        assertTrue(boundary.onFrame(SECOND, "one", false, QUIET))  // t = 2.0, stable 1.0 >= 0.5
        assertEquals(1, boundary.cutsByTextStability)
    }

    @Test
    fun `the boundary cuts on the threshold the timings declare`() {
        // Pins the wiring, not the number: if the constant is raised and the boundary keeps cutting after
        // 3 s, this fails here instead of arriving as "it cuts too early" from the phone.
        val threshold = ListenerTimings.DEFAULT_FALLBACK_QUIET_SECONDS
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one two", false, QUIET)          // t = 1.0: the text arrived

        var seconds = 1.0
        var cutAt: Double? = null
        while (seconds < threshold + 2.0) {
            boundary.onFrame(SECOND, "one two", false, QUIET)
            seconds += 1.0
            if (boundary.cutsByTextStability == 1) {
                cutAt = seconds
                break
            }
        }
        assertNotNull("the fallback must cut once its threshold has passed", cutAt)
        // The cut lands on the first frame where the text has been steady for the whole threshold, and
        // frames are one second, so the steady time can be up to a frame longer than the threshold.
        assertEquals(threshold, cutAt!! - 1.0, 0.5)
    }

    @Test
    fun `the snapshot reports what the status line shows`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one", false, 0.0)
        boundary.onFrame(SECOND, "one", true, 0.2)
        val snapshot = boundary.snapshot(audioQuietFor = 0.4, heardAudio = true)
        assertEquals(1, snapshot.cutsByModel)
        assertEquals(1, snapshot.cuts)
        assertEquals(2.0, snapshot.seconds, 0.0001)
        assertEquals(0.4, snapshot.quietFor, 0.0001)
        assertTrue(snapshot.heardAudio)
        assertEquals(0.2, snapshot.quietAtLastCut, 0.0001)
    }

    private companion object {
        const val SECOND = 16_000

        /** Audio quiet for long enough that only the text can be the limiting condition. */
        const val QUIET = 10.0

        /** Audio loud: the speaker is talking. */
        const val LOUD = 0.0
    }
}
