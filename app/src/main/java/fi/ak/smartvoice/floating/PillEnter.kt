package fi.ak.smartvoice.floating

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which screen edge the pill arrives from and retreats to, named for the **layout direction**
 * rather than for a side: under LTR [START] is the left edge and [END] the right one, and under
 * RTL they swap. The overlay window's own gravity is `BOTTOM or END`
 * (`FloatingOverlayService.kt:419`), so this is a naming of the geometry below, not a second
 * coordinate system.
 *
 * TODO(rtl): the RTL branch of [PillEnter.pillCentreX] and [PillEnter.edgeFor] is **unverified** —
 * the UI is English-only, so nothing here has ever run right-to-left on a device.
 */
enum class PillEdge { START, END }

/** One frame of a spring: where the value is now, and how fast it is moving (per second). */
data class PillMotionSample(val value: Float, val velocity: Float)

/**
 * The pill's arrival arithmetic: which horizontal edge it comes in from, exactly where it starts
 * (fully off screen), exactly where it is at any fraction of the motion, whether a given
 * visibility change animates at all, and one frame of the spring that does the moving.
 *
 * Deliberately free of Android **and** of Compose — the `PillGeometry.kt` discipline, so the
 * arithmetic is pinned by JVM tests instead of by watching a phone. Compose's own animation cannot
 * drive this motion at all: `withFrameNanos` throws without a `MonotonicFrameClock` in the calling
 * coroutine context, and the overlay service's scope
 * (`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, `FloatingOverlayService.kt:76`)
 * has none. The curve is Compose's (`dampingRatio = 1f` = `Spring.DampingRatioNoBouncy`,
 * `stiffness = 1500f` = `Spring.StiffnessMedium`); the integrator is [PillSpring]. See
 * `docs/work/pill-enter-animation/PLAN.md`, § *Curve*.
 */
object PillEnter {

    /**
     * How far beyond the display the pill's **leading** edge starts, measured in pill widths.
     * One whole width of clearance past the edge is what makes the pill not visible at t = 0
     * instead of a sliver of it poking in.
     */
    const val OFF_SCREEN_MARGIN_WIDTHS = 1f

    /**
     * The pill's horizontal **centre** in screen pixels, from the window's offset.
     *
     * LTR: the window is anchored to the display's end (right) edge, so the offset is the distance
     * from it — `displayWidthPx - offsetXPx` is the window's right edge, and the centre sits half a
     * view inside that. RTL mirrors, with the offset measured from the start edge.
     *
     * TODO(rtl): unverified (see [PillEdge]).
     */
    fun pillCentreX(displayWidthPx: Int, offsetXPx: Int, viewWidthPx: Int, isRtl: Boolean): Int =
        if (isRtl) offsetXPx + viewWidthPx / 2 else displayWidthPx - offsetXPx - viewWidthPx / 2

    /**
     * The nearer **horizontal** edge for a pill whose centre is at [pillCentreX] — decided on the
     * horizontal axis alone, whatever the pill's vertical position, dock or height. A pill docked
     * top-centre or bottom-centre therefore still arrives sideways.
     *
     * An exact tie resolves to [PillEdge.END]: that is the default dock's own edge, and it is also
     * what an odd `displayWidthPx` gets, because integer division puts the tie on the midpoint.
     *
     * TODO(rtl): unverified (see [PillEdge]). In RTL the display's start edge is the right-hand
     * one, so the same physical geometry resolves to the mirrored edge.
     */
    fun edgeFor(pillCentreX: Int, displayWidthPx: Int, isRtl: Boolean): PillEdge {
        val centre = if (isRtl) displayWidthPx - pillCentreX else pillCentreX
        return if (centre < displayWidthPx / 2) PillEdge.START else PillEdge.END
    }

    /**
     * Where the arrival starts: fully off screen beyond [edge], with the pill's leading edge one
     * pill width clear of the display.
     *
     * **Both answers look backwards, and that is the offset space, not a mistake.** Gravity is
     * `BOTTOM or END` (`FloatingOverlayService.kt:419`), so a *larger* x is further from the docked
     * corner and the window's screen position moves *opposite* to the value: the end-edge start is
     * negative, and the start-edge start is beyond the display width.
     *
     * `y = rest.y` in both cases is the horizontal-only rule: **no vertical travel in any
     * configuration**, so the arrival never depends on the pill's vertical position, its dock, or
     * the gesture-inset and cutout clearances.
     */
    fun startOffset(
        edge: PillEdge,
        viewWidthPx: Int,
        displayWidthPx: Int,
        rest: PillOffset,
    ): PillOffset = when (edge) {
        PillEdge.END -> PillOffset(x = -(OFF_SCREEN_MARGIN_WIDTHS + 1f) * viewWidthPx, y = rest.y)
        PillEdge.START -> PillOffset(x = displayWidthPx + OFF_SCREEN_MARGIN_WIDTHS * viewWidthPx, y = rest.y)
    }

    /**
     * **The seam**: the window offset at [fraction] of a transition between [start] (`0f`) and
     * [rest] (`1f`). [fraction] is coerced into `0f..1f`, so a spring that overshoots its target
     * still cannot place the pill outside the transition.
     *
     * `1f` is the rest offset *exactly* (the multiply by zero makes that an exact float result) and
     * `0f` is the start offset. Nothing else in the feature computes a position: the arrival drives
     * this `0f -> 1f` and the departure drives the same pair back, `1f -> 0f`.
     */
    fun positionAt(start: PillOffset, rest: PillOffset, fraction: Float): PillOffset {
        val remaining = 1f - fraction.coerceIn(0f, 1f)
        return PillOffset(
            x = rest.x + (start.x - rest.x) * remaining,
            y = rest.y + (start.y - rest.y) * remaining,
        )
    }

    /**
     * The transition to execute for a visibility change, and whether it may be animated.
     *
     * This is one of only two places in the feature that make a policy decision (the other is
     * [edgeFor]), so it is a test rather than a condition buried in the service. The visibility
     * decision itself never depends on the animation being possible — an unmeasured view still gets
     * its `Enter`/`Leave`, just an instant one.
     *
     * `animated` has **four** independent gates:
     * - [requestedAnimated] — only the keyboard signal asks for motion; a service start, a settings
     *   change, a display change and a session cancel are not transitions anyone watches;
     * - [keyboardDrivenVisibility] — the always-visible configuration
     *   (`floatingPillOnlyWhenKeyboardOpen = false`) has no arrival to animate;
     * - [animatorsEnabled] — system-wide "remove animations";
     * - `start != null` — an animation with no start point is not an animation.
     *
     * When [start] is `null` (the view has not been measured yet) the pair carried by the transition
     * is `rest` twice: an instant transition's start *is* its end, and no consumer reads the pair
     * when `animated` is false.
     *
     * [motionTarget] is the target of the motion the service already has in flight — its
     * `entryTarget`, `1f` for an arrival and `0f` for a departure — or `null` when there is none. It
     * exists because `visibleNow == shouldShow` covers **two** situations: nothing happening, and the
     * pill mid-flight toward exactly the state the decision asks for. The second is not an edge case:
     * the view is `VISIBLE` from the first frame of an arrival, so a second accessibility event
     * during it sees no change either, and a departure is `VISIBLE` until its last frame. Only the
     * first is "nothing to do"; a motion heading the **other** way is the reversal — `Enter` while
     * leaving, `Leave` while arriving — which carries the fraction and the velocity the motion
     * already has, because the reversal path reads neither [start] nor [rest].
     */
    fun transitionFor(
        visibleNow: Boolean,
        shouldShow: Boolean,
        motionTarget: Float?,
        start: PillOffset?,
        rest: PillOffset,
        requestedAnimated: Boolean,
        keyboardDrivenVisibility: Boolean,
        animatorsEnabled: Boolean,
    ): PillTransition {
        val animated = requestedAnimated && keyboardDrivenVisibility && animatorsEnabled && start != null
        val from = start ?: rest
        if (visibleNow == shouldShow) {
            // What the motion would have to be heading for to agree with this decision.
            val desired = if (shouldShow) 1f else 0f
            return when {
                // Nothing in flight, or a motion already heading for this state: nothing to do. This
                // is the whole of the argument — without it the two are one answer, and a motion that
                // was not contradicting anything would be aborted (an arrival cut short by a
                // redundant event, a reversing departure snapped back to its dock).
                motionTarget == null || motionTarget == desired -> PillTransition.None
                // A motion heading the other way is the flip.
                shouldShow -> PillTransition.Enter(start = from, rest = rest, animated = animated)
                else -> PillTransition.Leave(start = from, rest = rest, animated = animated)
            }
        }
        return if (shouldShow) {
            PillTransition.Enter(start = from, rest = rest, animated = animated)
        } else {
            PillTransition.Leave(start = from, rest = rest, animated = animated)
        }
    }
}

/**
 * What [PillEnter.transitionFor] decided: nothing to do, or an arrival / departure between two
 * window offsets, either animated or instant.
 *
 * A `Leave` carries the *same* pair as the `Enter` that preceded it — the mirror is one pair driven
 * to the other end, not a second geometry — and the pair travels with the transition so a reversal
 * mid-flight cannot recompute (and so cannot jump).
 */
sealed interface PillTransition {
    object None : PillTransition

    data class Enter(val start: PillOffset, val rest: PillOffset, val animated: Boolean) : PillTransition

    data class Leave(val start: PillOffset, val rest: PillOffset, val animated: Boolean) : PillTransition
}

/**
 * One damped spring with unit mass, integrated by the app in **semi-implicit Euler** — one call per
 * frame, one frame per call.
 *
 * Why not Compose's `Animatable`: it cannot run in the overlay service's scope (see [PillEnter]).
 * The constants are Compose's, so the *curve* is unchanged: [DAMPING_RATIO] is
 * `Spring.DampingRatioNoBouncy` (critically damped — the pill must not bounce past its dock) and
 * [STIFFNESS] is `Spring.StiffnessMedium`. A device session that finds the motion too slow or too
 * quick changes [STIFFNESS] alone; the next step down is `StiffnessMediumLow` = `400f`, which
 * roughly doubles the settle time.
 *
 * The value is the **entry fraction** — `1f` is the pill at its dock, `0f` is fully off screen — so
 * the spring's units are fractions, not pixels, and the same curve serves any travel.
 */
object PillSpring {

    /** Critical damping: the fastest approach that does not overshoot. */
    const val DAMPING_RATIO = 1f

    /** Compose's `Spring.StiffnessMedium`. With unit mass, `w = sqrt(this) ~ 38.7 rad/s`. */
    const val STIFFNESS = 1500f

    /** Within this much of the target (in fraction of the travel) the motion is over. */
    const val DISPLACEMENT_THRESHOLD = 0.01f

    /** Below this speed (fractions per second) the motion is over. */
    const val VELOCITY_THRESHOLD = 0.2f

    /**
     * The largest frame this integrator is asked to take. Semi-implicit Euler's error grows with
     * `w * dt`, and a dropped frame is exactly when the motion must not blow up, so a long frame is
     * cut to this rather than trusted.
     */
    const val MAX_STEP_SECONDS = 0.05f

    /**
     * One frame: the state after [dtSeconds] (coerced into `0f..MAX_STEP_SECONDS`).
     *
     * A frame that lands inside the rest window returns **exactly** `(target, 0f)`, so the motion
     * ends *at* the dock rather than near it — `0.01f` of the travel would otherwise leave a few
     * pixels of residue behind for good, and the next arrival would start from a rest position that
     * is not the one it was clamped to.
     */
    fun step(value: Float, velocity: Float, target: Float, dtSeconds: Float): PillMotionSample {
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        val damping = 2f * DAMPING_RATIO * sqrt(STIFFNESS)
        val accel = -STIFFNESS * (value - target) - damping * velocity
        val nextVelocity = velocity + accel * dt
        val nextValue = value + nextVelocity * dt
        if (isAtRest(nextValue, nextVelocity, target)) return PillMotionSample(target, 0f)
        return PillMotionSample(nextValue, nextVelocity)
    }

    /** Whether this state is close enough to [target] to be over — in position *and* in speed. */
    fun isAtRest(value: Float, velocity: Float, target: Float): Boolean =
        abs(value - target) < DISPLACEMENT_THRESHOLD && abs(velocity) < VELOCITY_THRESHOLD
}
