package io.github.alexeikramm.sovereign.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's growth arithmetic, pinned on the JVM — the same discipline as `PillEnterTest`.
 *
 * The property that matters is the whole point of the feature: at fraction `0f` the scaled card
 * must land **exactly on the pill's rectangle** (so the swap has no jump) and at `1f` on the card.
 * "Exactly" is asserted against the pill's own screen rect, because that is what the eye compares.
 */
class PillExpandTest {

    // A plausible test-device geometry: card surface 1190×910, a pill docked bottom-end and the
    // same pill dragged to the left. The bottoms coincide, as they do in the service.
    private val card = PillRect(left = 54f, top = 100f, right = 1244f, bottom = 1010f)
    private val pillRight = PillRect(left = 929f, top = 930f, right = 1216f, bottom = 1010f)
    private val pillLeft = PillRect(left = 85f, top = 930f, right = 372f, bottom = 1010f)

    /** The card's screen rect at [fraction] of the growth, applying the transform the way Compose does. */
    private fun scaled(growth: PillGrowth, fraction: Float): PillRect {
        val (scaleX, scaleY) = growth.scaleAt(fraction)
        val originX = card.left + growth.originX * card.width
        val originY = card.top + growth.originY * card.height
        fun x(value: Float) = originX + (value - originX) * scaleX
        fun y(value: Float) = originY + (value - originY) * scaleY
        return PillRect(x(card.left), y(card.top), x(card.right), y(card.bottom))
    }

    private fun assertRectEquals(expected: PillRect, actual: PillRect, tag: String) {
        assertEquals("$tag left", expected.left, actual.left, 0.5f)
        assertEquals("$tag top", expected.top, actual.top, 0.5f)
        assertEquals("$tag right", expected.right, actual.right, 0.5f)
        assertEquals("$tag bottom", expected.bottom, actual.bottom, 0.5f)
    }

    @Test
    fun `a left pill pins its left edge and grows rightward`() {
        val growth = PillExpand.growthFor(card, pillLeft, PillEdge.START)

        // At the start the card is exactly the pill...
        assertRectEquals(pillLeft, scaled(growth, 0f), "start")
        // ...and by the end it is exactly the card.
        assertRectEquals(card, scaled(growth, 1f), "end")
        // The left edge moves only across the small gap between the pill's edge (85) and the card's
        // (54) — a scale cannot hold it perfectly still and also end on the card — while the right
        // edge does the visible travel.
        val mid = scaled(growth, 0.5f)
        assertTrue(
            "the pinned left edge left the pill/card gap: ${mid.left}",
            mid.left in card.left - 0.5f..pillLeft.left + 0.5f,
        )
        assertTrue("the left pill must grow rightward", mid.right > pillLeft.right + (card.right - pillLeft.right) * 0.4f)
    }

    @Test
    fun `a right pill pins its right edge and grows leftward`() {
        val growth = PillExpand.growthFor(card, pillRight, PillEdge.END)

        assertRectEquals(pillRight, scaled(growth, 0f), "start")
        assertRectEquals(card, scaled(growth, 1f), "end")
        val mid = scaled(growth, 0.5f)
        assertTrue(
            "the pinned right edge left the pill/card gap: ${mid.right}",
            mid.right in pillRight.right - 0.5f..card.right + 0.5f,
        )
        assertTrue("the right pill must grow leftward", mid.left < pillRight.left - (pillRight.left - card.left) * 0.4f)
    }

    @Test
    fun `the growth keeps the bottom pinned whatever the pill's height`() {
        // A pill dragged up: its bottom is above the card's. The transform must still start on it.
        val raised = PillRect(left = 85f, top = 800f, right = 372f, bottom = 880f)
        val growth = PillExpand.growthFor(card, raised, PillEdge.START)
        assertRectEquals(raised, scaled(growth, 0f), "start")
        assertRectEquals(card, scaled(growth, 1f), "end")
    }

    @Test
    fun `the origin is corrected for the scale, not just the edge offset`() {
        // TransformOrigin is against the unscaled bounds, so `edgeOffset / cardWidth` would be
        // wrong. The left pill's left edge is 31 px in from the card's; the origin is 31/(1190-287).
        val growth = PillExpand.growthFor(card, pillLeft, PillEdge.START)
        val edgeOffset = pillLeft.left - card.left
        val naive = edgeOffset / card.width
        assertEquals(edgeOffset / (card.width - pillLeft.width), growth.originX, 1e-4f)
        assertTrue("the naive fraction would jump on the first frame", growth.originX != naive)
    }

    @Test
    fun `scaleAt clamps outside the zero-to-one band`() {
        val growth = PillExpand.growthFor(card, pillLeft, PillEdge.START)
        assertEquals(growth.scaleAt(0f), growth.scaleAt(-0.5f))
        assertEquals(growth.scaleAt(1f), growth.scaleAt(1.5f))
        assertEquals(1f, growth.scaleAt(1f).first, 0f)
        assertEquals(1f, growth.scaleAt(1f).second, 0f)
    }

    @Test
    fun `an unmeasured pill collapses to the card's end corner`() {
        // The fallback the service passes when `view.width == 0`: a zero-size pill at the card's
        // bottom-end. The transform is then a grow-from-that-corner with scale zero.
        val corner = PillRect(card.right, card.bottom, card.right, card.bottom)
        val growth = PillExpand.growthFor(card, corner, PillEdge.END)
        assertEquals(0f, growth.startScaleX, 0f)
        assertEquals(0f, growth.startScaleY, 0f)
        assertEquals(1f, growth.originX, 1e-4f)
        assertEquals(1f, growth.originY, 1e-4f)
        assertRectEquals(card, scaled(growth, 1f), "end")
    }

    @Test
    fun `a zero-size card does not divide by zero`() {
        val empty = PillRect(10f, 10f, 10f, 10f)
        val growth = PillExpand.growthFor(card = empty, pill = pillLeft, edge = PillEdge.START)
        assertEquals(1f, growth.startScaleX, 0f)
        assertEquals(1f, growth.startScaleY, 0f)
    }
}
