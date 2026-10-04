package io.github.alexeikramm.sovereign.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldPreambleTest {

    @Test
    fun `null text returns null`() {
        assertNull(FieldPreamble.extract(null, cursor = 10, sentences = 2))
    }

    @Test
    fun `empty text returns null`() {
        assertNull(FieldPreamble.extract("", cursor = 0, sentences = 2))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(FieldPreamble.extract("   \n\t  ", cursor = 7, sentences = 2))
    }

    @Test
    fun `non-positive sentence count returns null`() {
        assertNull(FieldPreamble.extract("One. Two.", cursor = 9, sentences = 0))
        assertNull(FieldPreamble.extract("One. Two.", cursor = 9, sentences = -1))
    }

    @Test
    fun `cursor at zero returns null`() {
        assertNull(FieldPreamble.extract("One. Two.", cursor = 0, sentences = 2))
    }

    @Test
    fun `fewer sentences than requested returns everything before the cursor`() {
        val text = "Only one sentence here."

        assertEquals(text, FieldPreamble.extract(text, cursor = text.length, sentences = 3))
        assertEquals(text, FieldPreamble.extract(text, cursor = text.length, sentences = 1))
    }

    @Test
    fun `exactly N sentences returns all of them`() {
        val text = "Sentence one. Sentence two."

        assertEquals(text, FieldPreamble.extract(text, cursor = text.length, sentences = 2))
    }

    @Test
    fun `more sentences than N returns the last N`() {
        val text = "Sentence one. Sentence two. Sentence three."

        assertEquals(
            "Sentence two. Sentence three.",
            FieldPreamble.extract(text, cursor = text.length, sentences = 2),
        )
        assertEquals(
            "Sentence three.",
            FieldPreamble.extract(text, cursor = text.length, sentences = 1),
        )
    }

    @Test
    fun `a trailing partial sentence is included`() {
        // The cursor sits at the end of an unfinished sentence: exactly the case this exists for.
        val text = "Sentence one. Sentence two. A partial"

        assertEquals(
            "Sentence two. A partial",
            FieldPreamble.extract(text, cursor = text.length, sentences = 2),
        )
        assertEquals(
            "A partial",
            FieldPreamble.extract(text, cursor = text.length, sentences = 1),
        )
    }

    @Test
    fun `cursor mid-text ignores everything after it`() {
        val text = "First sentence. Second sentence."

        // Cursor two characters into the second sentence ("Se"): text past the cursor is ignored,
        // but the partial sentence before it is still the trailing one when N allows it.
        assertEquals("First sentence. Se", FieldPreamble.extract(text, cursor = 18, sentences = 2))
        assertEquals("Se", FieldPreamble.extract(text, cursor = 18, sentences = 1))
    }

    @Test
    fun `cursor at the start of a sentence excludes that sentence`() {
        val text = "First sentence. Second sentence."

        // Index 16 is the "S" of "Second"; the text before the cursor ends at the first sentence.
        assertEquals("First sentence.", FieldPreamble.extract(text, cursor = 16, sentences = 2))
    }

    @Test
    fun `trailing punctuation of the preamble is preserved (unlike the seam overlap)`() {
        // The preamble is shown to the model as-is, so a sentence-final period stays.
        assertEquals("Hello world.", FieldPreamble.extract("Hello world.", cursor = 12, sentences = 1))
    }

    @Test
    fun `trailing whitespace is trimmed`() {
        assertEquals(
            "Hello world.",
            FieldPreamble.extract("Hello world.   \n", cursor = 16, sentences = 1),
        )
    }

    @Test
    fun `newlines act as sentence boundaries`() {
        val text = "Line one\nLine two\nLine three"

        assertEquals("Line two\nLine three", FieldPreamble.extract(text, cursor = text.length, sentences = 2))
    }

    @Test
    fun `abbreviations are not treated as sentence boundaries`() {
        val text = "I met Dr. Smith today."

        assertEquals(text, FieldPreamble.extract(text, cursor = text.length, sentences = 1))
    }

    @Test
    fun `a cursor beyond the text end is clamped to the whole text`() {
        val text = "One. Two."

        assertEquals(text, FieldPreamble.extract(text, cursor = 9999, sentences = 2))
    }

    @Test
    fun `a negative cursor is clamped to the start and yields null`() {
        assertNull(FieldPreamble.extract("One. Two.", cursor = -5, sentences = 2))
    }

    @Test
    fun `unicode text is extracted by UTF-16 index like Android selection`() {
        // Emoji is a surrogate pair; the cursor and boundary indices are UTF-16, as Android reports.
        val text = "Hei maailma. Tämä on koe🙂"
        val expected = "Tämä on koe🙂"

        assertEquals(expected, FieldPreamble.extract(text, cursor = text.length, sentences = 1))
    }

    @Test
    fun `a multi-code-unit character before the boundary does not shift the tail`() {
        val text = "Koe🙂. Toinen lause."

        assertEquals("Toinen lause.", FieldPreamble.extract(text, cursor = text.length, sentences = 1))
    }
}
