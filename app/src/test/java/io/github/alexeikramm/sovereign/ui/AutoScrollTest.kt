package io.github.alexeikramm.sovereign.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the transcript's follow-the-newest behaviour. The cases that matter are the ones
 * a quick manual test on the phone would miss: content growth must not unpin, and the resume only
 * happens when the user actually reaches the bottom.
 */
class AutoScrollTest {

    @Test
    fun `content growth keeps the pin`() {
        // Growing the content is not a scroll, so the pinned state is unchanged.
        assertTrue(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = false, value = 0, maxValue = 500, slopPx = 50,
            ),
        )
    }

    @Test
    fun `scrolling within the slop of the bottom stays pinned`() {
        assertTrue(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = true, value = 470, maxValue = 500, slopPx = 50,
            ),
        )
    }

    @Test
    fun `scrolling away from the bottom unpins`() {
        assertFalse(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = true, value = 300, maxValue = 500, slopPx = 50,
            ),
        )
    }

    @Test
    fun `returning to the bottom re-pins`() {
        assertTrue(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = false, scrolling = true, value = 500, maxValue = 500, slopPx = 50,
            ),
        )
    }

    @Test
    fun `content that does not scroll at all is always at the bottom`() {
        assertTrue(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = true, value = 0, maxValue = 0, slopPx = 50,
            ),
        )
    }
}
