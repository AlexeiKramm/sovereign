package fi.ak.smartvoice.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text-shaping rules that decide how a new unit of speech joins the text already committed:
 * spacing, and newlines before list items.
 */
class PanelTextTest {

    @Test
    fun `shouldPrependSpace inserts space after non-whitespace and avoids double space`() {
        assertTrue("space after dot", PanelText.shouldPrependSpace('.'))
        assertTrue("space after letter", PanelText.shouldPrependSpace('a'))
        assertTrue("space after question mark", PanelText.shouldPrependSpace('?'))
        assertFalse("no space after space", PanelText.shouldPrependSpace(' '))
        assertFalse("no space after newline", PanelText.shouldPrependSpace('\n'))
        assertFalse("no space after null", PanelText.shouldPrependSpace(null))
    }

    @Test
    fun `isListItem detects bullets and numbered items correctly`() {
        assertTrue(PanelText.isListItem("- Chicken"))
        assertTrue(PanelText.isListItem("* Chicken"))
        assertTrue(PanelText.isListItem("• Chicken"))
        assertTrue(PanelText.isListItem("1. Chicken"))
        assertTrue(PanelText.isListItem("1) Chicken"))
        assertTrue(PanelText.isListItem("  - Indented item"))

        assertFalse(PanelText.isListItem("Normal sentence."))
        assertFalse(PanelText.isListItem("10:20 AM"))
        assertFalse(PanelText.isListItem("112"))
        assertFalse(PanelText.isListItem("3.5%"))
    }

    @Test
    fun `unitPrefix joins list items with newlines and sentences with spaces`() {
        // List continuation
        assertEquals("\n", PanelText.unitPrefix("Shopping list:", "- Chicken"))
        assertEquals("\n", PanelText.unitPrefix("Shopping list:\n- Chicken", "- Milk"))
        assertEquals("\n", PanelText.unitPrefix("Shopping list:", "1. Chicken"))

        // Already has trailing newline
        assertEquals("", PanelText.unitPrefix("Shopping list:\n", "- Chicken"))

        // Normal sentence continuation
        assertEquals(" ", PanelText.unitPrefix("Hello there.", "How are you?"))
        assertEquals(" ", PanelText.unitPrefix("Hello there", "friend"))

        // Explicit leading newline
        assertEquals("\n", PanelText.unitPrefix("Some text.", "\nNext paragraph."))

        // Empty preceding text
        assertEquals("", PanelText.unitPrefix("", "- First item", isFirstUnit = true))
        assertEquals("", PanelText.unitPrefix(null, "Hello"))
    }
}
