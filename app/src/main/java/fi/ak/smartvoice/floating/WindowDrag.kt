package fi.ak.smartvoice.floating

/**
 * A pair of floats in window-offset space (for the offset) or screen space (for a pointer). The
 * window is written as integers, but a drag accumulates fractionally so a slow drag is not truncated
 * into never moving.
 */
data class PillOffset(val x: Float, val y: Float)

/**
 * Turns the pointer's **screen** position into the overlay window's offset.
 *
 * Why screen and not the deltas Compose reports: Compose pointer coordinates are relative to the
 * window, and this drag moves that very window under the finger. A local delta is therefore short by
 * however far the window already moved — integrating it makes the pill travel at ~half the finger's
 * speed in visible stutters (measured: 387 px of pill travel for an 800 px swipe). Adding the
 * window's movement back from the offset we last *asked* for makes it worse, because
 * `WindowManager` applies the move asynchronously and the requested value runs ahead of the transform
 * the events actually carry (observed: the pill oscillated between the screen edges).
 *
 * The pointer's `MotionEvent.rawX/rawY` are measured against the display and are immune to the
 * window's own movement, so the offset is anchored to those instead. They are captured in the
 * overlay view's `dispatchTouchEvent` (a `ViewGroup`'s `OnTouchListener` never sees touches that a
 * child consumes).
 *
 * Sign convention: gravity is `BOTTOM | END`, so the offset is the distance from the docked corner
 * and the window's screen position moves opposite to it on both axes. Dragging the finger left/up
 * (decreasing screen x/y) therefore increases the offset. Both axes are identical.
 *
 * Pure, so the tracking is unit-tested instead of only felt on a phone. [DragVelocityTracker]
 * samples the offset (not the pointer) for the same feedback reason; this is the position half of
 * that idea.
 */
class WindowDrag {
    private var startScreenX = 0f
    private var startScreenY = 0f
    private var startOffsetX = 0f
    private var startOffsetY = 0f

    /** Anchors a drag at the pointer's screen position and the window offset it started from. */
    fun begin(screen: PillOffset, offset: PillOffset) {
        startScreenX = screen.x
        startScreenY = screen.y
        startOffsetX = offset.x
        startOffsetY = offset.y
    }

    /** The window offset for a pointer now at the given screen position. */
    fun offsetFor(screenX: Float, screenY: Float): PillOffset = PillOffset(
        x = startOffsetX - (screenX - startScreenX),
        y = startOffsetY - (screenY - startScreenY),
    )
}
