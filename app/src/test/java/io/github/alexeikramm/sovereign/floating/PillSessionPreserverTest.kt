package io.github.alexeikramm.sovereign.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class PillSessionPreserverTest {

    @Test
    fun `combines settled delivery text with provisional text when both are present`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "Cleaned final text.",
            previewText = "Cleaned final text.",
            provisionalText = "and more words spoken",
        )
        assertEquals("Cleaned final text. and more words spoken", text)
    }

    @Test
    fun `combines preview text with provisional text when delivery text is blank`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "   ",
            previewText = "Preview fallback text.",
            provisionalText = "in flight words",
        )
        assertEquals("Preview fallback text. in flight words", text)
    }

    @Test
    fun `returns only settled text when provisional text is blank`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "Just settled text.",
            previewText = "Just settled text.",
            provisionalText = "   ",
        )
        assertEquals("Just settled text.", text)
    }

    @Test
    fun `returns only provisional text when settled text is blank`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "",
            previewText = "  ",
            provisionalText = "first spoken words before cut",
        )
        assertEquals("first spoken words before cut", text)
    }

    @Test
    fun `returns empty string when all candidates are blank or whitespace`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "   ",
            previewText = "\t\n",
            provisionalText = " ",
        )
        assertEquals("", text)
    }

    @Test
    fun `handles whitespace at seam properly`() {
        val textWithSpace = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "First part ",
            previewText = "",
            provisionalText = "second part",
        )
        assertEquals("First part second part", textWithSpace)
    }
}
