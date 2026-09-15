package fi.ak.smartvoice.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamHealerTest {

    @Test
    fun `empty or blank text returns empty split`() {
        val empty = SeamHealer.split("", sentenceCount = 2)
        assertEquals("", empty.anchorContext)
        assertEquals("", empty.overlapText)
        assertEquals("", empty.strippedOverlap)

        val blank = SeamHealer.split("   \n\t  ", sentenceCount = 2)
        assertEquals("", blank.anchorContext)
        assertEquals("", blank.overlapText)
        assertEquals("", blank.strippedOverlap)
    }

    @Test
    fun `zero sentence count returns empty split (append-only mode)`() {
        val split = SeamHealer.split("Testing one two three. Next sentence.", sentenceCount = 0)
        assertEquals("", split.anchorContext)
        assertEquals("", split.overlapText)
        assertEquals("", split.strippedOverlap)
    }

    @Test
    fun `single sentence text with sentenceCount=2 returns full text as overlap`() {
        val text = "I was thinking that."
        val split = SeamHealer.split(text, sentenceCount = 2)

        assertEquals("", split.anchorContext)
        assertEquals("I was thinking that.", split.overlapText)
        assertEquals("I was thinking that", split.strippedOverlap)
    }

    @Test
    fun `unclosed single sentence returns full text as overlap`() {
        val text = "I was thinking that"
        val split = SeamHealer.split(text, sentenceCount = 2)

        assertEquals("", split.anchorContext)
        assertEquals("I was thinking that", split.overlapText)
        assertEquals("I was thinking that", split.strippedOverlap)
    }

    @Test
    fun `two sentences text with sentenceCount=2 returns both sentences as overlap`() {
        val text = "First sentence. Second sentence."
        val split = SeamHealer.split(text, sentenceCount = 2)

        assertEquals("", split.anchorContext)
        assertEquals("First sentence. Second sentence.", split.overlapText)
        // Note: internal period of first sentence is preserved; trailing period of second is stripped
        assertEquals("First sentence. Second sentence", split.strippedOverlap)
    }

    @Test
    fun `three sentences text with sentenceCount=2 returns last two sentences`() {
        val text = "Sentence one. Sentence two. Sentence three."
        val split = SeamHealer.split(text, sentenceCount = 2)

        assertEquals("", split.anchorContext)
        assertEquals("Sentence two. Sentence three.", split.overlapText)
        assertEquals("Sentence two. Sentence three", split.strippedOverlap)
        assertTrue(text.endsWith(split.overlapText))
    }

    @Test
    fun `three sentences text with sentenceCount=1 returns last sentence only`() {
        val text = "Sentence one. Sentence two. Sentence three."
        val split = SeamHealer.split(text, sentenceCount = 1)

        assertEquals("", split.anchorContext)
        assertEquals("Sentence three.", split.overlapText)
        assertEquals("Sentence three", split.strippedOverlap)
        assertTrue(text.endsWith(split.overlapText))
    }

    @Test
    fun `english abbreviations like Dr and Mr are not treated as sentence boundaries`() {
        val text = "I met Dr. Smith and Mr. Brown today. They were very polite."
        val split = SeamHealer.split(text, sentenceCount = 1)

        assertEquals("", split.anchorContext)
        assertEquals("They were very polite.", split.overlapText)
        assertEquals("They were very polite", split.strippedOverlap)

        val splitAll = SeamHealer.split(text, sentenceCount = 2)
        assertEquals("I met Dr. Smith and Mr. Brown today. They were very polite.", splitAll.overlapText)
    }

    @Test
    fun `finnish abbreviations like esim and jne are not treated as sentence boundaries`() {
        val text = "Otetaan esim. omena ja jne. Se toimii."
        val split = SeamHealer.split(text, sentenceCount = 1)

        assertEquals("", split.anchorContext)
        assertEquals("Se toimii.", split.overlapText)
        assertEquals("Se toimii", split.strippedOverlap)
    }

    @Test
    fun `decimal numbers do not split sentence`() {
        val text = "The version is 3.14. Next sentence."
        val split = SeamHealer.split(text, sentenceCount = 1)

        assertEquals("Next sentence.", split.overlapText)
    }

    @Test
    fun `initials do not split sentence`() {
        val text = "John F. Kennedy was a president. He served in office."
        val split = SeamHealer.split(text, sentenceCount = 1)

        assertEquals("He served in office.", split.overlapText)
    }

    @Test
    fun `ellipsis is treated as a single boundary`() {
        val text = "Wait... What happened? Nothing."
        val split = SeamHealer.split(text, sentenceCount = 2)

        assertEquals("What happened? Nothing.", split.overlapText)
        assertEquals("What happened? Nothing", split.strippedOverlap)
    }

    @Test
    fun `newlines act as sentence boundaries`() {
        val text = "First line\nSecond line\nThird line"
        val split = SeamHealer.split(text, sentenceCount = 2)

        assertEquals("Second line\nThird line", split.overlapText)
        assertEquals("Second line\nThird line", split.strippedOverlap)
    }

    @Test
    fun `strips various trailing terminal punctuation and whitespace while preserving internal punctuation`() {
        val text1 = "Sentence one. Let us see if this works..."
        val split1 = SeamHealer.split(text1, sentenceCount = 1)
        assertEquals("Let us see if this works...", split1.overlapText)
        assertEquals("Let us see if this works", split1.strippedOverlap)

        val text2 = "Are you sure? Yes!  \n"
        val split2 = SeamHealer.split(text2, sentenceCount = 1)
        assertEquals("Yes!  \n", split2.overlapText)
        assertEquals("Yes", split2.strippedOverlap)
    }
}
