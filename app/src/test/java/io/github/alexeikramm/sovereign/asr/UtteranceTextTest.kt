package io.github.alexeikramm.sovereign.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join rule for pause-delimited units (D-032). It decides the spacing of everything committed at
 * the cursor, and it is the part of the unit pipeline that can be checked without a phone.
 */
class UtteranceTextTest {

    @Test
    fun `units are joined by a single space`() {
        val text = UtteranceText()
        text.append("okay so i think we should move the meeting")
        text.append("and also book the room")
        assertEquals("okay so i think we should move the meeting and also book the room", text.toString())
    }

    @Test
    fun `a blank unit contributes nothing and does not add a space`() {
        val text = UtteranceText()
        assertFalse(text.append("   "))
        assertFalse(text.append(""))
        assertTrue(text.append("first unit"))
        assertTrue(text.append("second unit"))
        assertEquals("first unit second unit", text.toString())
    }

    @Test
    fun `units are trimmed, so a trailing space from the recogniser cannot double up`() {
        val text = UtteranceText()
        text.append("  hello there ")
        text.append(" general kenobi  ")
        assertEquals("hello there general kenobi", text.toString())
    }

    @Test
    fun `an utterance with no units is empty rather than a single space`() {
        val text = UtteranceText()
        assertTrue(text.isEmpty)
        text.append(" ")
        assertTrue("a blank unit must not make the utterance non-empty", text.isEmpty)
        assertEquals("", text.toString())
    }

    @Test
    fun `clearing starts a new utterance`() {
        val text = UtteranceText()
        text.append("previous utterance")
        text.clear()
        assertTrue(text.isEmpty)
        text.append("next one")
        assertEquals("next one", text.toString())
    }
}
