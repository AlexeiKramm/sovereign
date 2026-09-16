package fi.ak.smartvoice.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press word hit-testing is easy to get subtly wrong (it slices a string by hand) and
 * awkward to verify by pressing words on a phone, so it is pinned here instead.
 */
class WordFixTest {

    // --- wordAt: boundaries ----------------------------------------------------

    @Test
    fun `selects the whole word under an offset inside it`() {
        val text = "hello world"
        val at = text.indexOf("world") + 2
        assertEquals(WordSelection("world", 6, 11), WordFix.wordAt(text, at))
    }

    @Test
    fun `strips punctuation around the word`() {
        val text = "Hello, world!"
        val at = text.indexOf("world") + 1
        assertEquals(WordSelection("world", 7, 12), WordFix.wordAt(text, at))
    }

    @Test
    fun `keeps an internal apostrophe`() {
        val text = "I don't know"
        val at = text.indexOf("don't") + 2
        assertEquals(WordSelection("don't", 2, 7), WordFix.wordAt(text, at))
    }

    @Test
    fun `keeps an internal unicode apostrophe`() {
        val text = "I don\u2019t know"
        val at = text.indexOf("don\u2019t") + 2
        assertEquals(WordSelection("don\u2019t", 2, 7), WordFix.wordAt(text, at))
    }

    @Test
    fun `keeps an internal hyphen`() {
        val text = "a well-known thing"
        val at = text.indexOf("well-known") + 3
        assertEquals(WordSelection("well-known", 2, 12), WordFix.wordAt(text, at))
    }

    @Test
    fun `a long-press on the closing punctuation selects the word it ends`() {
        val text = "(hello) world"
        val at = text.indexOf(')')
        assertEquals(WordSelection("hello", 1, 6), WordFix.wordAt(text, at))
    }

    @Test
    fun `a long-press on a space selects the word before it`() {
        val text = "hello world"
        assertEquals(WordSelection("hello", 0, 5), WordFix.wordAt(text, 5))
    }

    @Test
    fun `a long-press on leading whitespace selects nothing`() {
        val text = "   hello"
        assertNull(WordFix.wordAt(text, 0))
        assertNull(WordFix.wordAt(text, 1))
    }

    @Test
    fun `out-of-range offsets are clamped to the nearest end`() {
        val text = "hi"
        assertEquals(WordSelection("hi", 0, 2), WordFix.wordAt(text, 999))
        assertEquals(WordSelection("hi", 0, 2), WordFix.wordAt(text, -5))
    }

    @Test
    fun `empty text selects nothing`() {
        assertNull(WordFix.wordAt("", 0))
    }

    @Test
    fun `digits count as part of a word`() {
        val text = "at 3pm sharp"
        val at = text.indexOf("3pm") + 1
        assertEquals(WordSelection("3pm", 3, 6), WordFix.wordAt(text, at))
    }

    // --- correction: what to store --------------------------------------------

    @Test
    fun `correction maps heard to incorrect and the typed word to correct`() {
        val fix = WordFix.correction(heard = "gee mail", replacement = "Gmail")
        assertEquals(DictionaryFix(correct = "Gmail", incorrect = "gee mail"), fix)
    }

    @Test
    fun `correction trims both sides`() {
        val fix = WordFix.correction(heard = "  nemotron ", replacement = " Nemotron ")
        assertEquals(DictionaryFix(correct = "Nemotron", incorrect = "nemotron"), fix)
    }

    @Test
    fun `correction is null when the replacement is blank`() {
        assertNull(WordFix.correction(heard = "hello", replacement = "   "))
        assertNull(WordFix.correction(heard = "hello", replacement = ""))
    }

    @Test
    fun `correction is null when nothing changed exactly`() {
        assertNull(WordFix.correction(heard = "hello", replacement = "hello"))
        assertNull(WordFix.correction(heard = " hello ", replacement = "hello"))
    }

    @Test
    fun `a case-only fix is kept, so the word can be made to always spell that way`() {
        // The store drops the "heard" side when it only differs by case, leaving an
        // "always spell it exactly" term — which is the useful outcome here.
        assertEquals(
            DictionaryFix(correct = "Gmail", incorrect = "gmail"),
            WordFix.correction(heard = "gmail", replacement = "Gmail"),
        )
    }

    // --- The action catalogue --------------------------------------------------

    @Test
    fun `every action carries a label, an id and a form title`() {
        // Guards the whole point of the catalogue: a new action that forgets its UI strings fails
        // here rather than rendering an empty button in the pill.
        assertTrue(WordAction.entries.isNotEmpty())
        WordAction.entries.forEach { action ->
            assertTrue("id for $action", action.id.isNotBlank())
            assertTrue("label for $action", action.label.isNotBlank())
            assertTrue("field label for $action", action.fieldLabel.isNotBlank())
            assertTrue("form title for $action", action.formTitle("word").contains("word"))
        }
    }
}
