package io.github.alexeikramm.sovereign.floating

/**
 * The gesture a single press on the **resting** pill turned out to be.
 *
 * The resting pill is one small target carrying three gestures: tap (open the card and dictate
 * normally), drag (move the pill), and hold (quick capture — dictate and auto-inject without the
 * card). They all begin with the same finger-down, so they cannot be layered as independent
 * `pointerInput` modifiers; they are arbitrated by one driver in `FloatingPillView`, and the
 * decision itself lives here so it is testable without a device.
 */
enum class PillGesture {
    /** A short press with no movement: the existing tap-to-open-card behaviour. */
    TAP,

    /** The finger left the slop circle: move/fling the pill. */
    DRAG,

    /** A still press held past the threshold: push-to-talk quick capture. */
    QUICK_CAPTURE,
}

/**
 * The one place the resting pill's press arbitration is decided. Pure: no Compose, no Android.
 *
 * Precedence matters and is deliberate — **movement beats time**. A press that ever leaves the
 * slop circle is a drag even if it was also held long enough, because someone repositioning the
 * pill should not accidentally start dictating. Only a press that never moved and outlasted
 * [HOLD_THRESHOLD_MS] is quick capture; anything shorter and stiller is a tap.
 *
 * The thresholds are device-tuning defaults, not user settings. ~350 ms is long enough not to
 * fire on a normal tap and short enough that speaking starts promptly; 10 dp is the standard
 * touch-slop order and is converted with the display density by the Compose driver.
 */
object PillGestureRules {

    /** How long a still press must last before it counts as hold-to-talk. */
    const val HOLD_THRESHOLD_MS = 350L

    /** How far the finger may travel before the press is a drag instead. */
    const val SLOP_DP = 10f

    /** The slop in pixels for a display of [density] (dp × density). */
    fun slopPx(density: Float): Float = SLOP_DP * density

    /**
     * Classifies a completed press from the two facts the driver observed:
     *
     * @param movedBeyondSlop the finger ever left the slop circle during the press.
     * @param heldPastThreshold the finger was still down after the hold threshold **and**
     *   hold-to-talk was enabled. The caller folds the setting in here on purpose: with the
     *   setting off, a long still press must fall back to the old tap behaviour, not do nothing.
     */
    fun classify(movedBeyondSlop: Boolean, heldPastThreshold: Boolean): PillGesture = when {
        movedBeyondSlop -> PillGesture.DRAG
        heldPastThreshold -> PillGesture.QUICK_CAPTURE
        else -> PillGesture.TAP
    }
}
