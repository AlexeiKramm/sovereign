package io.github.alexeikramm.sovereign.floating

/**
 * A rectangle in **screen pixels**. Deliberately Android-free (the `PillGeometry.kt` discipline),
 * so the growth arithmetic is pinned by JVM tests instead of by watching a phone.
 */
data class PillRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The same rect pulled in by [px] on every side (the overlay's outer padding). */
    fun inset(px: Float): PillRect =
        PillRect(left + px, top + px, right - px, bottom - px)
}

/**
 * The card's growth out of the pill: the scale it starts at and the transform origin — fractions
 * of the card's **own, unscaled bounds** — that keeps the pill's pinned corner fixed while it
 * grows. `startScale` is `pillSize / cardSize`; a fraction of `0f` is that scale and `1f` is the
 * full card.
 */
data class PillGrowth(
    val startScaleX: Float,
    val startScaleY: Float,
    val originX: Float,
    val originY: Float,
) {
    /** The per-axis `(scaleX, scaleY)` at [fraction] of the growth, clamped to `0f..1f`. */
    fun scaleAt(fraction: Float): Pair<Float, Float> {
        val progress = fraction.coerceIn(0f, 1f)
        return (startScaleX + (1f - startScaleX) * progress) to
            (startScaleY + (1f - startScaleY) * progress)
    }

    companion object {
        /** No growth: full scale from the card's centre. The state before any expansion. */
        val NONE = PillGrowth(startScaleX = 1f, startScaleY = 1f, originX = 0.5f, originY = 1f)
    }
}

/**
 * The container transform that turns the minimized pill into the expanded card.
 *
 * The card is already as wide as the display is (≈93% on the test device), so the interesting
 * decision is not *where* the card rests but *which way it grows*: it is anchored on the pill's
 * **nearer horizontal edge** ([PillEdge], the same rule the arrival uses) and on the **bottom**
 * (the window is bottom-docked, so the two bottom edges already coincide). A pill on the left
 * grows rightward; a pill on the right grows leftward.
 *
 * The origin arithmetic is the non-obvious part. `TransformOrigin` is expressed against the
 * *unscaled* bounds, so scaling about a corner does **not** put that corner on the target unless
 * the origin is corrected for the scale: the fraction on an axis is
 * `edgeOffset / (cardSize - pillSize)`, not `edgeOffset / cardSize`. `growthFor` is that
 * correction, and the tests pin both ends of the motion back to the pill's rectangle.
 */
object PillExpand {

    /**
     * The growth for a card already at [card] (its final, clamped screen rect, **without** the
     * overlay's outer padding) out of a pill at [pill] (its screen rect, also without the padding;
     * a zero-size pill is the unmeasured fallback and resolves to the card's end-bottom corner).
     */
    fun growthFor(card: PillRect, pill: PillRect, edge: PillEdge): PillGrowth {
        val cardWidth = card.width
        val cardHeight = card.height
        val pillWidth = pill.width.coerceAtLeast(0f)
        val pillHeight = pill.height.coerceAtLeast(0f)

        val startScaleX = if (cardWidth > 0f) (pillWidth / cardWidth).coerceIn(0f, 1f) else 1f
        val startScaleY = if (cardHeight > 0f) (pillHeight / cardHeight).coerceIn(0f, 1f) else 1f

        // The pinned edge offset is measured from the card's own `left`/`right`; the denominator
        // `cardSize - pillSize` is what makes the *scaled* pinned edge land exactly on the pill's.
        val originX = when {
            cardWidth <= pillWidth -> if (edge == PillEdge.START) 0f else 1f
            edge == PillEdge.START ->
                ((pill.left - card.left) / (cardWidth - pillWidth)).coerceIn(0f, 1f)

            else ->
                ((pill.right - card.left - pillWidth) / (cardWidth - pillWidth)).coerceIn(0f, 1f)
        }
        val originY = if (cardHeight <= pillHeight) {
            1f
        } else {
            ((pill.bottom - card.top - pillHeight) / (cardHeight - pillHeight)).coerceIn(0f, 1f)
        }

        return PillGrowth(
            startScaleX = startScaleX,
            startScaleY = startScaleY,
            originX = originX,
            originY = originY,
        )
    }
}
