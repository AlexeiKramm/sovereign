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

    @Test
    fun `our own follow animation never unpins even far from the bottom`() {
        // Mid-follow the viewport is far from the (new) bottom. That is our motion, not the user
        // leaving, so the pin must hold — otherwise the follow cancels itself.
        assertTrue(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = true, autoScrolling = true,
                value = 100, maxValue = 900, slopPx = 50,
            ),
        )
    }

    @Test
    fun `a large content jump during our follow keeps the pin`() {
        // A committed unit can add several lines, so the follow starts well beyond the slop.
        assertTrue(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = true, autoScrolling = true,
                value = 0, maxValue = 5000, slopPx = 50,
            ),
        )
    }

    @Test
    fun `a user scroll away from the bottom still unpins`() {
        assertFalse(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = true, scrolling = true, autoScrolling = false,
                value = 100, maxValue = 900, slopPx = 50,
            ),
        )
    }

    @Test
    fun `auto-scrolling does not resurrect a pin the user already dropped`() {
        assertFalse(
            AutoScrollRules.pinnedAfterScroll(
                currentlyPinned = false, scrolling = true, autoScrolling = true,
                value = 0, maxValue = 900, slopPx = 50,
            ),
        )
    }
}
