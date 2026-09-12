package fi.ak.smartvoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's wording, and the split between what a person reads and what a diagnostics reader needs
 * (D-041).
 */
class PanelTextTest {

    @Test
    fun `the listening line carries the elapsed time`() {
        assertEquals("Listening — 0.0 s", PanelText.listeningLine(0.0, PanelState.Level.UNKNOWN))
        assertEquals("Listening — 12.4 s", PanelText.listeningLine(12.4, PanelState.Level.UNKNOWN))
    }

    @Test
    fun `the level is a word, because the frames are of the order of a second`() {
        assertEquals(
            "Listening — 3.0 s · hearing you",
            PanelText.listeningLine(3.0, PanelState.Level.SPEECH),
        )
        assertEquals("Listening — 4.5 s · quiet", PanelText.listeningLine(4.5, PanelState.Level.QUIET))
        assertNull("before any audio, say nothing rather than guess", PanelText.levelWord(PanelState.Level.UNKNOWN))
    }

    @Test
    fun `a cleanup note rides along, and no note means no separator`() {
        assertEquals(
            "Listening — 1.0 s · quiet · cleaning first units raw",
            PanelText.listeningLine(1.0, PanelState.Level.QUIET, "cleaning first units raw"),
        )
        assertFalse(PanelText.listeningLine(1.0, PanelState.Level.QUIET, null).endsWith("·"))
    }

    @Test
    fun `the preview keeps the words you just said`() {
        val long = "one " + "word ".repeat(60) + "last words here"
        val clamped = PanelText.previewLine(long, maxChars = 40)
        assertTrue("clamped", clamped.length <= 41)
        assertTrue("marked as clamped", clamped.startsWith("…"))
        assertTrue("the tail is what is kept", clamped.endsWith("last words here"))
    }

    @Test
    fun `a short preview is shown as it is, and a blank one is blank`() {
        assertEquals("moi tässä puhun", PanelText.previewLine("  moi tässä puhun  "))
        assertEquals("", PanelText.previewLine("   "))
        assertEquals("", PanelText.previewLine(""))
    }

    @Test
    fun `the level comes from the audio, coarsely and on purpose`() {
        assertEquals(PanelState.Level.SPEECH, PanelText.levelFor(0.0))
        assertEquals(PanelState.Level.SPEECH, PanelText.levelFor(0.4))
        assertEquals(PanelState.Level.QUIET, PanelText.levelFor(0.5))
        assertEquals(PanelState.Level.QUIET, PanelText.levelFor(9.0))
    }

    @Test
    fun `the headline carries a marker only when there are details to open`() {
        val withDetails = PanelState(headline = "Listening — 1.0 s", details = "engine: local")
        assertEquals("Listening — 1.0 s  ▾", PanelText.headlineWithMarker(withDetails, collapsed = true))
        assertEquals("Listening — 1.0 s  ▴", PanelText.headlineWithMarker(withDetails, collapsed = false))

        val withoutDetails = PanelState(headline = "Listening — 1.0 s")
        assertEquals(
            "nothing to open, so nothing offering to open it",
            "Listening — 1.0 s",
            PanelText.headlineWithMarker(withoutDetails, collapsed = true),
        )
    }

    @Test
    fun `the state knows when there is nothing worth previewing`() {
        assertFalse(PanelState(headline = "Ready").hasPreview)
        assertFalse(PanelState(headline = "Listening", preview = "   ").hasPreview)
        assertTrue(PanelState(headline = "Listening", preview = "moi").hasPreview)
    }

    @Test
    fun `shouldPrependSpace inserts space after non-whitespace and avoids double space`() {
        assertTrue("space after dot", PanelText.shouldPrependSpace('.'))
        assertTrue("space after letter", PanelText.shouldPrependSpace('a'))
        assertTrue("space after question mark", PanelText.shouldPrependSpace('?'))
        assertFalse("no space after space", PanelText.shouldPrependSpace(' '))
        assertFalse("no space after newline", PanelText.shouldPrependSpace('\n'))
        assertFalse("no space after null", PanelText.shouldPrependSpace(null))
    }
}
