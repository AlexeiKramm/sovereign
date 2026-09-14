package fi.ak.smartvoice.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamHealerTest {

    @Test
    fun `empty or blank text returns empty split`() {
        val empty = SeamHealer.split("", overlapWords = 5, totalContextWords = 10)
        assertEquals("", empty.anchorContext)
        assertEquals("", empty.overlapText)
        assertEquals("", empty.strippedOverlap)

        val blank = SeamHealer.split("   \n\t  ", overlapWords = 5, totalContextWords = 10)
        assertEquals("", blank.anchorContext)
        assertEquals("", blank.overlapText)
        assertEquals("", blank.strippedOverlap)
    }

    @Test
    fun `zero overlap words returns empty split (append-only mode)`() {
        val split = SeamHealer.split("Testing one two three", overlapWords = 0, totalContextWords = 10)
        assertEquals("", split.anchorContext)
        assertEquals("", split.overlapText)
        assertEquals("", split.strippedOverlap)
    }

    @Test
    fun `short text with fewer words than overlap becomes full overlap with empty anchor`() {
        val text = "I was thinking that."
        val split = SeamHealer.split(text, overlapWords = 5, totalContextWords = 10)

        // 4 words total <= 5 overlap words
        assertEquals("", split.anchorContext)
        assertEquals("I was thinking that.", split.overlapText)
        assertEquals("I was thinking that", split.strippedOverlap)
    }

    @Test
    fun `moderate text splits overlap and anchor correctly`() {
        val text = "Today when I was driving to the supermarket I realized that."
        // 11 words:
        // [0:Today, 1:when, 2:I, 3:was, 4:driving, 5:to, 6:the, 7:supermarket, 8:I, 9:realized, 10:that.]
        // overlapWords = 5 -> words 6..10: "the supermarket I realized that."
        // totalContextWords = 10 -> anchorWords = 10 - 5 = 5 -> words 1..5: "when I was driving to"
        val split = SeamHealer.split(text, overlapWords = 5, totalContextWords = 10)

        assertEquals("when I was driving to", split.anchorContext)
        assertEquals("the supermarket I realized that.", split.overlapText)
        assertEquals("the supermarket I realized that", split.strippedOverlap)
        assertTrue(text.endsWith(split.overlapText))
    }

    @Test
    fun `strips various trailing terminal punctuation and whitespace`() {
        val text1 = "Let us see if this works..."
        val split1 = SeamHealer.split(text1, overlapWords = 3, totalContextWords = 5)
        assertEquals("if this works...", split1.overlapText)
        assertEquals("if this works", split1.strippedOverlap)

        val text2 = "Are you sure?  \n"
        val split2 = SeamHealer.split(text2, overlapWords = 3, totalContextWords = 5)
        assertEquals("Are you sure?  \n", split2.overlapText)
        assertEquals("Are you sure", split2.strippedOverlap)

        val text3 = "Wait, look at that, "
        val split3 = SeamHealer.split(text3, overlapWords = 2, totalContextWords = 4)
        assertEquals("at that, ", split3.overlapText)
        assertEquals("at that", split3.strippedOverlap)
    }

    @Test
    fun `finnish sentence seam strips trailing period before etta`() {
        val text = "Luulen että."
        val split = SeamHealer.split(text, overlapWords = 5, totalContextWords = 10)

        assertEquals("", split.anchorContext)
        assertEquals("Luulen että.", split.overlapText)
        assertEquals("Luulen että", split.strippedOverlap)
    }
}
