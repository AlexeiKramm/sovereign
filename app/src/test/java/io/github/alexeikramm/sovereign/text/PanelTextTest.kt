package io.github.alexeikramm.sovereign.text

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

    /**
     * The pause's punctuation lands at the start of the next unit, so the rule must not push it away
     * from the word it punctuates. Regression for "word , and more" in the live and committed text.
     */
    @Test
    fun `no space is inserted before leading punctuation`() {
        assertFalse(PanelText.shouldPrependSpace('d', ", and more"))
        assertFalse(PanelText.shouldPrependSpace('d', "."))
        assertFalse(PanelText.shouldPrependSpace('d', "?"))
        assertFalse(PanelText.shouldPrependSpace('d', ")"))
        assertFalse(PanelText.shouldPrependSpace('d', " — dash"))
        // A word, or an opening quote/bracket, still takes the space.
        assertTrue(PanelText.shouldPrependSpace('d', "next"))
        assertTrue(PanelText.shouldPrependSpace('d', "\"quoted\""))
        assertTrue(PanelText.shouldPrependSpace('d', "(parenthetical)"))
        // An incoming unit that already carries its own boundary is never double-spaced.
        assertFalse(PanelText.shouldPrependSpace('d', " next"))

        assertEquals("", PanelText.joinSeparator("I think", ", and more"))
        assertEquals("I think, and more", "I think" + PanelText.joinSeparator("I think", ", and more") + ", and more")
        assertEquals(" ", PanelText.joinSeparator("I think", "and more"))
    }

    @Test
    fun `unitPrefix does not push leading punctuation off the preceding word`() {
        assertEquals("", PanelText.unitPrefix("I think", ", and more"))
        assertEquals("", PanelText.unitPrefix("Hello there.", ". Next"))
        assertEquals(" ", PanelText.unitPrefix("I think", "and more"))
    }

    @Test
    fun `joinUnits separates words but not punctuation`() {
        assertEquals("hello world", PanelText.joinUnits(listOf("hello", "world")))
        assertEquals("hello, world", PanelText.joinUnits(listOf("hello", ", world")))
        assertEquals("", PanelText.joinUnits(emptyList()))
        assertEquals("solo", PanelText.joinUnits(listOf("", "solo")))
    }
}
