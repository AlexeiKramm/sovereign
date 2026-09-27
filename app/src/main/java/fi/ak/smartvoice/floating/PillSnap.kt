package fi.ak.smartvoice.floating

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure arithmetic for edge-anchoring: determining which screen edge the pill is nearer to,
 * calculating the target resting coordinates that hug that edge with [PillDock.DRAG_MARGIN_DP]
 * margin, and clamping vertical travel with optional momentum follow-through.
 *
 * Deliberately free of Android and Compose types so that edge-snapping math is 100% testable
 * in pure JVM unit tests without an emulator or device.
 */
object PillSnap {

    /**
     * Attenuation applied to vertical flick momentum during edge-anchored snap (0.0 .. 1.0).
     * 0.25f provides a tight, restrained follow-through that keeps the pill comfortably within
     * the user's thumb area without excessive vertical drift.
     */
    const val DEFAULT_ATTENUATION = 0.25f

    /**
     * Stiffness for the edge snap motion (Compose Spring.StiffnessMediumLow = 400f).
     * 400f provides a soft, cushioned acceleration curve from rest rather than an aggressive yank.
     */
    const val SNAP_STIFFNESS = 400f

    /** Damping ratio for edge snap (critical damping: no overshoot). */
    const val SNAP_DAMPING_RATIO = 1f

    /** Natural frequency for edge snap in rad/s (`sqrt(SNAP_STIFFNESS) = 20f`). */
    const val SNAP_NATURAL_FREQUENCY = 20f

    /**
     * Calculates the projected vertical travel delta from vertical velocity [velocityY] in px/s.
     * Attenuating by [attenuation] gives the user intuitive, proportional vertical follow-through on release.
     */
    fun projectedDeltaY(
        velocityY: Float,
        attenuation: Float = DEFAULT_ATTENUATION,
        frequency: Float = SNAP_NATURAL_FREQUENCY,
    ): Float {
        if (abs(velocityY) < 1f || frequency <= 0f) return 0f
        return (velocityY / frequency) * attenuation
    }

    /**
     * One frame of the snap spring integration using [SNAP_STIFFNESS] and [SNAP_DAMPING_RATIO].
     */
    fun step(
        value: Float,
        velocity: Float,
        target: Float = 1f,
        dtSeconds: Float,
    ): PillMotionSample {
        val dt = dtSeconds.coerceIn(0f, PillSpring.MAX_STEP_SECONDS)
        val damping = 2f * SNAP_DAMPING_RATIO * SNAP_NATURAL_FREQUENCY
        val accel = -SNAP_STIFFNESS * (value - target) - damping * velocity
        val nextVelocity = velocity + accel * dt
        val nextValue = value + nextVelocity * dt
        if (isAtRest(nextValue, nextVelocity, target)) return PillMotionSample(target, 0f)
        return PillMotionSample(nextValue, nextVelocity)
    }

    /** Whether the state is close enough to target to be at rest. */
    fun isAtRest(value: Float, velocity: Float, target: Float = 1f): Boolean =
        abs(value - target) < PillSpring.DISPLACEMENT_THRESHOLD &&
            abs(velocity) < PillSpring.VELOCITY_THRESHOLD

    /**
     * Determines whether [currentX] (with optional horizontal release velocity [velocityX]) is nearer
     * to the START or END horizontal edge.
     *
     * When [velocityX] is significant, the projection `currentX + velocityX / omega` allows a flick
     * towards the opposite side to target that edge even before the pill crosses the midpoint.
     * Uses [PillEnter.pillCentreX] and [PillEnter.edgeFor] to ensure exact alignment with
     * arrival/departure and card expansion geometry.
     */
    fun targetEdge(
        currentX: Int,
        screenWidthPx: Int,
        viewWidthPx: Int,
        isRtl: Boolean = false,
        velocityX: Float = 0f,
    ): PillEdge {
        val projectedOffset = if (abs(velocityX) >= 100f) {
            (currentX + velocityX / SNAP_NATURAL_FREQUENCY).roundToInt()
        } else {
            currentX
        }
        val centreX = PillEnter.pillCentreX(
            displayWidthPx = screenWidthPx,
            offsetXPx = projectedOffset,
            viewWidthPx = viewWidthPx,
            isRtl = isRtl,
        )
        return PillEnter.edgeFor(
            pillCentreX = centreX,
            displayWidthPx = screenWidthPx,
            isRtl = isRtl,
        )
    }

    /**
     * Returns the target window offset `x` for [edge] so that the pill rests [marginPx]
     * away from the physical display boundary.
     *
     * In LTR (window gravity `BOTTOM or END`):
     * - [PillEdge.END] (right screen edge): distance from the right edge is [marginPx].
     * - [PillEdge.START] (left screen edge): distance from the right edge is `screenWidth - viewWidth - margin`.
     *
     * In RTL, the start/end physical edges swap.
     */
    fun snapTargetX(
        edge: PillEdge,
        screenWidthPx: Int,
        viewWidthPx: Int,
        marginPx: Int,
        isRtl: Boolean = false,
    ): Int {
        val maxOffset = (screenWidthPx - viewWidthPx - marginPx).coerceAtLeast(0)
        return when (edge) {
            PillEdge.END -> if (!isRtl) marginPx.coerceAtMost(maxOffset) else maxOffset
            PillEdge.START -> if (!isRtl) maxOffset else marginPx.coerceAtMost(maxOffset)
        }
    }

    /**
     * Computes the complete resting [PillPoint] when snapping to the nearer edge.
     * Horizontal coordinate is snapped to the nearest edge dock.
     * Vertical coordinate projects along [velocity.y] with [attenuation] and clamps within
     * `[marginPx, screenHeight - viewHeight - marginPx]`.
     */
    fun snapPosition(
        current: PillPoint,
        screenWidthPx: Int,
        screenHeightPx: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
        marginPx: Int,
        isRtl: Boolean = false,
        velocity: PillVelocity = PillVelocity(0f, 0f),
        attenuation: Float = DEFAULT_ATTENUATION,
    ): PillPoint {
        val edge = targetEdge(
            currentX = current.x,
            screenWidthPx = screenWidthPx,
            viewWidthPx = viewWidthPx,
            isRtl = isRtl,
            velocityX = velocity.x,
        )
        val targetX = snapTargetX(
            edge = edge,
            screenWidthPx = screenWidthPx,
            viewWidthPx = viewWidthPx,
            marginPx = marginPx,
            isRtl = isRtl,
        )
        val deltaY = projectedDeltaY(velocity.y, attenuation)
        val maxY = (screenHeightPx - viewHeightPx - marginPx).coerceAtLeast(0)
        val minY = marginPx.coerceAtMost(maxY)
        val targetY = (current.y + deltaY).roundToInt().coerceIn(minY, maxY)
        return PillPoint(
            x = targetX,
            y = targetY,
        )
    }

    /**
     * Returns true if [currentX] is already within [tolerancePx] of its snapped edge target.
     */
    fun isSnapped(
        currentX: Int,
        screenWidthPx: Int,
        viewWidthPx: Int,
        marginPx: Int,
        tolerancePx: Int = 1,
        isRtl: Boolean = false,
    ): Boolean {
        val edge = targetEdge(currentX, screenWidthPx, viewWidthPx, isRtl)
        val targetX = snapTargetX(edge, screenWidthPx, viewWidthPx, marginPx, isRtl)
        return abs(currentX - targetX) <= tolerancePx
    }
}
