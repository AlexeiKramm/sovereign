package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause boundary (D-032) — and specifically why there are two signals.
 *
 * A device run on 2026-09-12 dictated with deliberate pauses and produced **no** boundary at all: text
 * landed only when the user tapped stop. From inside the app, "your pause was shorter than the rule's
 * trailing silence" and "this artifact's endpoint detection does not fire" are indistinguishable, so
 * the fallback exists and the counters say which one fired.
 *
 * Frames are one second (16 000 samples) for readability.
 */
class UnitBoundaryTest {

    @Test
    fun `text that keeps growing never cuts`() {
        val boundary = UnitBoundary()
        assertFalse(boundary.onFrame(SECOND, "one", false))
        assertFalse(boundary.onFrame(SECOND, "one two", false))
        assertFalse(boundary.onFrame(SECOND, "one two three", false))
        assertEquals(0, boundary.cuts)
    }

    @Test
    fun `text that stops changing cuts a unit once the fallback threshold passes`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one two", false)              // t=1.0, text changed
        assertFalse(boundary.onFrame(SECOND, "one two", false)) // t=2.0, stable 1.0 < 1.8
        assertTrue(boundary.onFrame(SECOND, "one two", false))  // t=3.0, stable 2.0 >= 1.8
        assertEquals(1, boundary.cutsByTextStability)
        assertEquals(0, boundary.cutsByModel)
    }

    @Test
    fun `the recogniser's endpoint cuts immediately, however fresh the text is`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "hello there", false)
        assertTrue(boundary.onFrame(SECOND, "hello there", true))
        assertEquals(1, boundary.cutsByModel)
        assertEquals(0, boundary.cutsByTextStability)
    }

    @Test
    fun `silence before the speaker starts never produces an empty unit`() {
        val boundary = UnitBoundary()
        repeat(4) { assertFalse(boundary.onFrame(SECOND, "", false)) }
        assertEquals(0, boundary.cuts)
    }

    @Test
    fun `one pause cannot cut twice, because the caller restarts the timer`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one two", false)
        boundary.onFrame(SECOND, "one two", false)
        assertTrue(boundary.onFrame(SECOND, "one two", false))
        boundary.markCut()
        assertFalse("the timer restarted, so no second cut", boundary.onFrame(SECOND, "one two", false))
        assertEquals(1, boundary.cuts)
    }

    @Test
    fun `reset clears the counters and the clock for the next utterance`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "x", true)
        assertEquals(1, boundary.cuts)
        boundary.reset()
        assertEquals(0, boundary.cuts)
        assertEquals(0.0, boundary.seconds, 0.0001)
        assertEquals(0.0, boundary.stableFor, 0.0001)
    }

    @Test
    fun `the silence timer counts from the last speech, and a cut does not restart it`() {
        // The distinction that matters for the auto-stop (D-035): a cut means "speech was heard a
        // moment ago", so sharing one timer between the cut and the silence measurement would make the
        // recording wait for the pause threshold *plus* the auto-stop, i.e. feel wrong by a second.
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one two", false)                 // t=1.0, speech at 1.0
        assertTrue(boundary.onFrame(SECOND, "one two", true))      // t=2.0, recogniser endpoint
        assertEquals(1.0, boundary.silenceFor, 0.0001)
        boundary.markCut()
        assertEquals("markCut must not move the speech clock", 1.0, boundary.silenceFor, 0.0001)

        // The stream was reset, so the next frame carries no text at all — still not a text change.
        assertFalse(boundary.onFrame(SECOND, "", false))           // t=3.0
        assertEquals(2.0, boundary.silenceFor, 0.0001)
        assertTrue(boundary.hasSpeech)
    }

    @Test
    fun `silence before the speaker starts reports no speech, so an auto-stop cannot fire`() {
        val boundary = UnitBoundary()
        repeat(5) { assertFalse(boundary.onFrame(SECOND, "", false)) }
        assertFalse("nothing was said, so there is nothing to stop after", boundary.hasSpeech)
        assertEquals(0.0, boundary.silenceFor, 0.0001)
        assertEquals(5.0, boundary.seconds, 0.0001)
    }

    @Test
    fun `the stability threshold is configurable between utterances`() {
        val boundary = UnitBoundary(stableSeconds = 1.8)
        boundary.configureStability(0.5)
        boundary.onFrame(SECOND, "one", false)                      // t=1.0
        assertTrue(boundary.onFrame(SECOND, "one", false))          // t=2.0, stable 1.0 >= 0.5
        assertEquals(1, boundary.cutsByTextStability)
    }

    @Test
    fun `the snapshot reports what the status line shows`() {
        val boundary = UnitBoundary()
        boundary.onFrame(SECOND, "one", false)
        boundary.onFrame(SECOND, "one", true)
        val snapshot = boundary.snapshot()
        assertEquals(1, snapshot.cutsByModel)
        assertEquals(1, snapshot.cuts)
        assertEquals(2.0, snapshot.seconds, 0.0001)
        assertTrue(snapshot.hasSpeech)
    }

    private companion object {
        const val SECOND = 16_000
    }
}
