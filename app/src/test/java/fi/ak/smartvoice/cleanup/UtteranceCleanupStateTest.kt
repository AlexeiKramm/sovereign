package fi.ak.smartvoice.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what reaches the cursor and what the model is given as context next. They are
 * pure, so they are checked here — a mistake in either direction shows up as missing or garbled text in
 * someone's note, which is the most expensive place to find it.
 */
class UtteranceCleanupStateTest {

    private fun cleaned(unit: String, text: String) =
        CleanupPass(unit = unit, text = text, cleaned = true, note = "cleaned")

    private fun failed(unit: String, note: String = "the model returned nothing") =
        CleanupPass.failed(unit, note)

    @Test
    fun `a cleaned unit is what reaches the cursor, and it becomes the tail`() {
        val state = UtteranceCleanupState()
        val commit = state.onPass(cleaned("okay so i think we should move it", "Okay, so I think we should move it."))
        assertEquals("Okay, so I think we should move it.", commit.text)
        assertTrue(commit.cleaned)
        assertEquals("Okay, so I think we should move it.", state.carriedTail)
        assertEquals(1, state.units)
        assertEquals(1, state.cleanedUnits)
        assertEquals(0, state.failedUnits)
    }

    @Test
    fun `a failed pass commits the recogniser's own unit, verbatim`() {
        val state = UtteranceCleanupState()
        val raw = "and also we need to book the room by wednesday"
        val commit = state.onPass(failed(raw, "hit the 512-token guard"))
        assertEquals("fail closed: the unit stands", raw, commit.text)
        assertFalse(commit.cleaned)
        assertEquals(1, state.units)
        assertEquals(0, state.cleanedUnits)
        assertEquals(1, state.failedUnits)
    }

    @Test
    fun `the tail is what was committed, so a failed unit is part of the context too`() {
        // The tail has to match what the user is looking at; feeding only successful passes back would
        // leave the model's context out of step with the screen.
        val state = UtteranceCleanupState()
        state.onPass(cleaned("first unit", "First unit."))
        state.onPass(failed("second unit"))
        state.onPass(cleaned("third unit", "Third unit."))
        assertEquals("First unit. second unit Third unit.", state.carriedTail)
        assertEquals(3, state.units)
        assertEquals(2, state.cleanedUnits)
        assertEquals(1, state.failedUnits)
    }

    @Test
    fun `blank text never adds a stray space to the tail`() {
        val state = UtteranceCleanupState()
        state.onPass(cleaned("first", "First."))
        state.onPass(cleaned("empty", "   "))
        state.onPass(failed("  "))
        assertEquals("First.", state.carriedTail)
        assertEquals("units are still counted", 3, state.units)
    }

    @Test
    fun `seeding starts from text that is already on screen`() {
        // Cleanup can engage mid-utterance, once the model finishes loading; the units that went out raw
        // before that are still the context for the first cleaned one (D-037).
        val state = UtteranceCleanupState()
        state.seed("text already committed at the cursor")
        assertEquals("text already committed at the cursor", state.carriedTail)
        assertEquals("seeding is not a unit", 0, state.units)
        state.onPass(cleaned("next unit", "Next unit."))
        assertEquals("text already committed at the cursor Next unit.", state.carriedTail)
    }

    @Test
    fun `consecutive list items are joined with newlines in carriedTail`() {
        val state = UtteranceCleanupState()
        val c1 = state.onPass(cleaned("shopping list", "Shopping list:"))
        val c2 = state.onPass(cleaned("chicken", "- Chicken"))
        val c3 = state.onPass(cleaned("milk", "- Milk"))

        assertEquals("", c1.prefix)
        assertEquals("\n", c2.prefix)
        assertEquals("\n", c3.prefix)
        assertEquals("Shopping list:\n- Chicken\n- Milk", state.carriedTail)
    }

    @Test
    fun `reset clears the tail and the counters for the next utterance`() {
        val state = UtteranceCleanupState()
        state.onPass(cleaned("first", "First."))
        state.reset()
        assertEquals("", state.carriedTail)
        assertEquals(0, state.units)
        assertEquals(0, state.failedUnits)
    }
}
