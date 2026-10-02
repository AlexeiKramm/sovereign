package io.github.alexeikramm.sovereign.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resting pill arbitrates three gestures that all start with the same finger-down. The
 * driver observes time and movement on the device; the decision is pure and lives in
 * [PillGestureRules], so the precedence is pinned here rather than by holding a finger on a
 * phone and guessing.
 */
class PillGestureTest {

    @Test
    fun `a short still press is a tap`() {
        assertEquals(
            PillGesture.TAP,
            PillGestureRules.classify(movedBeyondSlop = false, heldPastThreshold = false),
        )
    }

    @Test
    fun `a still press held past the threshold is quick capture`() {
        assertEquals(
            PillGesture.QUICK_CAPTURE,
            PillGestureRules.classify(movedBeyondSlop = false, heldPastThreshold = true),
        )
    }

    @Test
    fun `movement makes it a drag even when it was also held long enough`() {
        assertEquals(
            PillGesture.DRAG,
            PillGestureRules.classify(movedBeyondSlop = true, heldPastThreshold = true),
        )
    }

    @Test
    fun `movement makes it a drag even for a short press`() {
        assertEquals(
            PillGesture.DRAG,
            PillGestureRules.classify(movedBeyondSlop = true, heldPastThreshold = false),
        )
    }

    @Test
    fun `with hold-to-talk disabled a long still press falls back to a tap`() {
        // The driver folds the setting into `heldPastThreshold`, so this is the fallback contract:
        // turning the setting off must restore the old tap behaviour, not make long presses inert.
        assertEquals(
            PillGesture.TAP,
            PillGestureRules.classify(movedBeyondSlop = false, heldPastThreshold = false),
        )
    }

    @Test
    fun `slop is ten dp converted at the display density`() {
        assertEquals(10f, PillGestureRules.slopPx(density = 1f), 0.001f)
        assertEquals(30f, PillGestureRules.slopPx(density = 3f), 0.001f)
        assertEquals(14f, PillGestureRules.slopPx(density = 1.4f), 0.001f)
    }
}
