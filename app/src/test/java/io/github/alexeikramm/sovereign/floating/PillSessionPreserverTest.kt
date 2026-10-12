package io.github.alexeikramm.sovereign.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class PillSessionPreserverTest {

    @Test
    fun `prefers deliveryText when non-blank`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "Cleaned final text.",
            previewText = "Cleaned preview text.",
            provisionalText = "extra words",
        )
        assertEquals("Cleaned final text.", text)
    }

    @Test
    fun `falls back to previewText when deliveryText is blank`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "   ",
            previewText = "Preview fallback text.",
            provisionalText = "extra words",
        )
        assertEquals("Preview fallback text.", text)
    }

    @Test
    fun `falls back to provisionalText when delivery and preview are blank`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "",
            previewText = "  ",
            provisionalText = "in flight words",
        )
        assertEquals("in flight words", text)
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
    fun `trims surrounding whitespace`() {
        val text = PillSessionPreserver.resolveTextToPreserve(
            deliveryText = "   Hello world!   \n",
            previewText = "",
        )
        assertEquals("Hello world!", text)
    }
}
