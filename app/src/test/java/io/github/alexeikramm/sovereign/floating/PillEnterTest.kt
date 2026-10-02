package io.github.alexeikramm.sovereign.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One 16 ms frame, the step the service tick feeds the spring. */
private const val FRAME = 0.016f

/**
 * `theme/Tokens.kt:39` (`Motion.fastMs`), inlined rather than imported: a pure JVM test should not
 * have to load an object whose initializer builds Compose easings, and a `const val` arrives as a
 * literal either way.
 */
private const val FAST_MS = 150

/** A plausible phone: a 1080 px display, a 300 px pill, and the default dock's own offsets. */
private const val DISPLAY = 1080
private const val VIEW = 300
private val REST = PillOffset(x = 48f, y = 270f)

/**
 * The window's **left screen edge** for an offset. Gravity is `BOTTOM or END`, so a larger x is
 * further from the end edge and the window's screen position moves *opposite* to the offset.
 */
private fun leftEdgeScreenX(offset: PillOffset): Float = DISPLAY - offset.x - VIEW

/** Short form of [PillEnter.transitionFor] with every gate satisfied — the animation's normal case. */
private fun transition(
    visibleNow: Boolean,
    shouldShow: Boolean,
    start: PillOffset? = PillOffset(x = -600f, y = 270f),
    rest: PillOffset = REST,
    requestedAnimated: Boolean = true,
    keyboardDrivenVisibility: Boolean = true,
    animatorsEnabled: Boolean = true,
    motionTarget: Float? = null,
): PillTransition = PillEnter.transitionFor(
    visibleNow = visibleNow,
    shouldShow = shouldShow,
    motionTarget = motionTarget,
    start = start,
    rest = rest,
    requestedAnimated = requestedAnimated,
    keyboardDrivenVisibility = keyboardDrivenVisibility,
    animatorsEnabled = animatorsEnabled,
)

/**
 * The pill's arrival is arithmetic and policy, and both are hard to verify by watching a phone:
 * which edge it comes in from, whether it really starts off screen, whether a given visibility
 * change animates at all, and how the spring behaves when the keyboard is closed mid-flight are
 * exactly the things a person cannot measure by eye. These tests pin them.
 */
class PillEnterTest {

    // --- The nearest horizontal edge -------------------------------------------

    @Test
    fun `edgeFor returns the start edge left of the midpoint and the end edge right of it`() {
        assertEquals(PillEdge.START, PillEnter.edgeFor(DISPLAY / 2 - 1, DISPLAY, isRtl = false))
        assertEquals(PillEdge.END, PillEnter.edgeFor(DISPLAY / 2 + 1, DISPLAY, isRtl = false))
        assertEquals(PillEdge.START, PillEnter.edgeFor(0, DISPLAY, isRtl = false))
        assertEquals(PillEdge.END, PillEnter.edgeFor(DISPLAY, DISPLAY, isRtl = false))
    }

    @Test
    fun `edgeFor sends an exact tie to the end edge`() {
        // The tie is the default dock's own edge, which is why it is not arbitrary.
        assertEquals(PillEdge.END, PillEnter.edgeFor(DISPLAY / 2, DISPLAY, isRtl = false))

        // An odd width's midpoint falls between two integers; integer division puts the tie on it,
        // so both sides of it stay on the end edge.
        val odd = 1081
        assertEquals(PillEdge.END, PillEnter.edgeFor(odd / 2, odd, isRtl = false))
        assertEquals(PillEdge.END, PillEnter.edgeFor(odd / 2 + 1, odd, isRtl = false))
        assertEquals(PillEdge.START, PillEnter.edgeFor(odd / 2 - 1, odd, isRtl = false))
    }

    @Test
    fun `edgeFor mirrors under RTL`() {
        // TODO(rtl): unverified — the UI is English-only, so this branch has never run on a device.
        // In RTL the display's start edge is the right-hand one, so the same physical geometry
        // resolves to the mirrored edge.
        assertEquals(PillEdge.START, PillEnter.edgeFor(120, DISPLAY, isRtl = false))
        assertEquals(PillEdge.END, PillEnter.edgeFor(120, DISPLAY, isRtl = true))
        assertEquals(PillEdge.END, PillEnter.edgeFor(960, DISPLAY, isRtl = false))
        assertEquals(PillEdge.START, PillEnter.edgeFor(960, DISPLAY, isRtl = true))
        assertEquals(PillEdge.END, PillEnter.edgeFor(DISPLAY / 2, DISPLAY, isRtl = true))
    }

    @Test
    fun `pillCentreX is the end-anchored arithmetic, and mirrors under RTL`() {
        // The default dock (16 dp in from the end, 90 dp up) is the case that decides the feature's
        // visible direction: its centre is right of the midpoint, so the pill arrives from the end.
        val docked = PillDock.defaultPosition(density = 3f)
        val centre = PillEnter.pillCentreX(DISPLAY, docked.x, VIEW, isRtl = false)
        assertEquals(DISPLAY - docked.x - VIEW / 2, centre)
        assertEquals(PillEdge.END, PillEnter.edgeFor(centre, DISPLAY, isRtl = false))

        // ...and a pill dragged past the midpoint arrives from the other side.
        assertEquals(
            PillEdge.START,
            PillEnter.edgeFor(PillEnter.pillCentreX(DISPLAY, DISPLAY - 100, VIEW, isRtl = false), DISPLAY, false),
        )

        // TODO(rtl): unverified. The offset is then measured from the other edge, so the centre
        // mirrors about the display's own middle.
        assertEquals(DISPLAY - centre, PillEnter.pillCentreX(DISPLAY, docked.x, VIEW, isRtl = true))
    }

    // --- Where the arrival starts ----------------------------------------------

    @Test
    fun `startOffset at the end edge is one pill width outside the display`() {
        val start = PillEnter.startOffset(PillEdge.END, VIEW, DISPLAY, REST)
        assertEquals(-(PillEnter.OFF_SCREEN_MARGIN_WIDTHS + 1f) * VIEW, start.x, 0f)
        // The leading (left) screen edge is exactly one pill width beyond the display's end edge...
        assertTrue(
            "the leading edge is not clear of the display: $start",
            DISPLAY - start.x - VIEW >= DISPLAY + VIEW,
        )
        // ...and the whole pill is out there with it: the leading edge is at or beyond the end edge.
        assertTrue("the pill is still partly on screen: $start", DISPLAY - start.x - VIEW >= DISPLAY)
    }

    @Test
    fun `startOffset at the start edge is one pill width outside the display`() {
        val start = PillEnter.startOffset(PillEdge.START, VIEW, DISPLAY, REST)
        assertEquals(DISPLAY + PillEnter.OFF_SCREEN_MARGIN_WIDTHS * VIEW, start.x, 0f)
        // Gravity is BOTTOM|END, so this offset is *beyond* the display width and the pill sits to
        // the left of it: the leading (right) screen edge is one pill width outside the start edge,
        // and the whole pill is off screen with it.
        //
        // The natural screen-space check for this case, `start.x + viewWidthPx <= -viewWidthPx`, does
        // not hold for the pinned formula (start.x > 0 in this offset space) — the two assertions
        // below are its intent, in screen space.
        val leadingScreenX = DISPLAY - start.x
        assertEquals("the leading edge is not one pill width clear", -VIEW.toFloat(), leadingScreenX, 0f)
        assertTrue("the pill is still partly on screen", leadingScreenX + VIEW <= 0f)
        assertTrue("a start-edge offset must be beyond the display width", start.x > DISPLAY)
    }

    @Test
    fun `startOffset keeps the rest Y for every dock`() {
        // Horizontal only: a top-centre, a bottom-centre and a mid-left pill all keep their own y.
        val docks = listOf(
            PillOffset(48f, 270f),    // the default dock
            PillOffset(500f, 0f),     // top-centre
            PillOffset(600f, 2600f),  // bottom-centre
            PillOffset(40f, 1200f),   // mid-left
        )
        for (rest in docks) {
            for (edge in listOf(PillEdge.START, PillEdge.END)) {
                val start = PillEnter.startOffset(edge, VIEW, DISPLAY, rest)
                assertEquals("y must not travel on $edge", rest.y, start.y, 0f)
            }
        }
    }

    // --- The one seam -----------------------------------------------------------

    @Test
    fun `positionAt returns the rest at 1, the start at 0 and the midpoint in between`() {
        val start = PillOffset(x = -600f, y = 270f)

        // Fraction 1f is the rest offset *exactly* — the multiply by zero makes it exact.
        assertEquals(REST, PillEnter.positionAt(start, REST, 1f))
        assertEquals(start.x, PillEnter.positionAt(start, REST, 0f).x, 1e-3f)
        assertEquals(start.y, PillEnter.positionAt(start, REST, 0f).y, 1e-3f)
        assertEquals((start.x + REST.x) / 2f, PillEnter.positionAt(start, REST, 0.5f).x, 1e-3f)

        // Horizontal only, at every fraction.
        for (i in 0..10) {
            assertEquals(REST.y, PillEnter.positionAt(start, REST, i / 10f).y, 0f)
        }
    }

    @Test
    fun `positionAt clamps a fraction outside the zero-to-one range`() {
        val start = PillOffset(x = -600f, y = 270f)
        // A spring that overshoots its target must not be able to place the pill inside the screen
        // on the way out, or past its dock on the way in.
        assertEquals(PillEnter.positionAt(start, REST, 0f), PillEnter.positionAt(start, REST, -0.4f))
        assertEquals(PillEnter.positionAt(start, REST, 1f), PillEnter.positionAt(start, REST, 1.7f))
    }

    @Test
    fun `the arrival approaches the dock monotonically and never passes it`() {
        val docked = leftEdgeScreenX(REST)
        for (edge in listOf(PillEdge.START, PillEdge.END)) {
            val start = PillEnter.startOffset(edge, VIEW, DISPLAY, REST)
            var previous = leftEdgeScreenX(start)
            for (step in 1..100) {
                val at = leftEdgeScreenX(PillEnter.positionAt(start, REST, step / 100f))
                if (edge == PillEdge.END) {
                    assertTrue("$edge moved away from the dock: $previous -> $at", at <= previous + 1e-3f)
                    assertTrue("$edge passed the dock: $at < $docked", at >= docked - 1e-3f)
                } else {
                    assertTrue("$edge moved away from the dock: $previous -> $at", at >= previous - 1e-3f)
                    assertTrue("$edge passed the dock: $at > $docked", at <= docked + 1e-3f)
                }
                previous = at
            }
            assertEquals("the arrival must end at the dock", docked, previous, 1e-3f)
        }
    }

    // --- Whether anything animates at all ---------------------------------------

    @Test
    fun `a hidden pill that should show is an animated enter carrying the pair`() {
        val start = PillOffset(x = -600f, y = 270f)
        assertEquals(
            PillTransition.Enter(start = start, rest = REST, animated = true),
            transition(visibleNow = false, shouldShow = true, start = start),
        )
    }

    @Test
    fun `a visible pill that should hide is an animated leave`() {
        val start = PillOffset(x = -600f, y = 270f)
        assertEquals(
            PillTransition.Leave(start = start, rest = REST, animated = true),
            transition(visibleNow = true, shouldShow = false, start = start),
        )
    }

    @Test
    fun `no change in visibility is None, however much animation was requested`() {
        assertEquals(PillTransition.None, transition(visibleNow = true, shouldShow = true))
        assertEquals(PillTransition.None, transition(visibleNow = false, shouldShow = false))
    }

    @Test
    fun `the always-visible configuration never animates`() {
        // The setting means "always there", so there is no arrival to animate (question 3).
        val entering = transition(
            visibleNow = false,
            shouldShow = true,
            keyboardDrivenVisibility = false,
        )
        assertEquals(
            PillTransition.Enter(start = PillOffset(-600f, 270f), rest = REST, animated = false),
            entering,
        )
        assertEquals(
            PillTransition.Leave(start = PillOffset(-600f, 270f), rest = REST, animated = false),
            transition(visibleNow = true, shouldShow = false, keyboardDrivenVisibility = false),
        )
    }

    @Test
    fun `system animators off never animates`() {
        // Question 4: system-wide "remove animations" is the only reduce-motion switch, and it is
        // load-bearing here — the service's scope has no MotionDurationScale, so nothing else
        // would honour it.
        assertEquals(
            PillTransition.Enter(start = PillOffset(-600f, 270f), rest = REST, animated = false),
            transition(visibleNow = false, shouldShow = true, animatorsEnabled = false),
        )
        assertEquals(
            PillTransition.Leave(start = PillOffset(-600f, 270f), rest = REST, animated = false),
            transition(visibleNow = true, shouldShow = false, animatorsEnabled = false),
        )
    }

    @Test
    fun `a caller that is not the keyboard signal never animates`() {
        // Service start, settings change, display change, session cancel: none of them is a
        // transition anyone watches, and an animation there would delay the pill.
        assertEquals(
            PillTransition.Enter(start = PillOffset(-600f, 270f), rest = REST, animated = false),
            transition(visibleNow = false, shouldShow = true, requestedAnimated = false),
        )
        assertEquals(
            PillTransition.Leave(start = PillOffset(-600f, 270f), rest = REST, animated = false),
            transition(visibleNow = true, shouldShow = false, requestedAnimated = false),
        )
    }

    @Test
    fun `an unmeasured view skips the animation but still changes visibility`() {
        // `view.width == 0` — the window has not been laid out yet. An animation with no start
        // point is not an animation, but the visibility decision cannot depend on that.
        assertEquals(
            PillTransition.Enter(start = REST, rest = REST, animated = false),
            transition(visibleNow = false, shouldShow = true, start = null),
        )
        assertEquals(
            PillTransition.Leave(start = REST, rest = REST, animated = false),
            transition(visibleNow = true, shouldShow = false, start = null),
        )
        // A null start cannot resurrect the animation either: all gates are on above.
        assertTrue(
            transition(visibleNow = false, shouldShow = true, start = null) is PillTransition.Enter,
        )
    }

    // --- The decision seam while a motion is in flight --------------------------

    @Test
    fun `a running arrival with an unchanged decision is still None, so the motion survives`() {
        // The view is VISIBLE from the arrival's first frame, so a redundant accessibility event
        // during it looks exactly like "nothing changed". Only the motion itself distinguishes the
        // two: it is already heading for `desired`, so the decision is None and nothing aborts it.
        assertEquals(
            PillTransition.None,
            transition(visibleNow = true, shouldShow = true, motionTarget = 1f),
        )
    }

    @Test
    fun `a running departure with the keyboard reopened asks for the arrival instead`() {
        // The reopened keyboard leaves the view VISIBLE too (it goes GONE only when the exit ends),
        // so this is the same seam: the motion is heading the other way, which is the reversal and
        // not a no-op.
        assertEquals(
            PillTransition.Enter(start = PillOffset(-600f, 270f), rest = REST, animated = true),
            transition(visibleNow = true, shouldShow = true, motionTarget = 0f),
        )
    }

    @Test
    fun `a hidden pill whose departure is in flight is still None`() {
        // The mirror of the first case, on the hidden side: `visibleNow == shouldShow == false` with
        // a motion already heading off screen changes nothing, so the departure is left to finish.
        assertEquals(
            PillTransition.None,
            transition(visibleNow = false, shouldShow = false, motionTarget = 0f),
        )
    }

    // --- The spring -------------------------------------------------------------

    @Test
    fun `the spring is monotone toward its target and never passes it`() {
        var value = 0f
        var velocity = 0f
        var previous = value
        for (frame in 0 until (1f / FRAME).toInt()) {
            val sample = PillSpring.step(value, velocity, 1f, FRAME)
            value = sample.value
            velocity = sample.velocity
            assertTrue("the spring went backwards: $previous -> $value", value >= previous)
            assertTrue("the spring passed its dock: $value", value <= 1f)
            previous = value
        }
        assertEquals("the motion must end at the dock, not near it", 1f, value, 0f)
    }

    @Test
    fun `the spring settles into its dock between Motion's fast band and the standard ceiling`() {
        var value = 0f
        var velocity = 0f
        var elapsedMs = 0
        while (!PillSpring.isAtRest(value, velocity, 1f) && elapsedMs < 1_000) {
            val sample = PillSpring.step(value, velocity, 1f, FRAME)
            value = sample.value
            velocity = sample.velocity
            elapsedMs += (FRAME * 1_000f).toInt()
        }
        println("PillSpring: settle time ${elapsedMs}ms at ${(FRAME * 1_000f).toInt()}ms frames")

        // A spring has an asymptote, not a duration: this is the time to reach the dock inside
        // DISPLACEMENT_THRESHOLD, not a wall-clock animation length.
        assertTrue("settled in $elapsedMs ms: that reads as sluggish", elapsedMs in 1..300)
        assertTrue("settled in $elapsedMs ms: below $FAST_MS ms it reads as a glitch, not a transition", elapsedMs >= FAST_MS)
        assertEquals("the settled value must be exactly the dock", 1f, value, 0f)
        assertEquals("and it must have stopped", 0f, velocity, 0f)
    }

    @Test
    fun `a flipped target carries its velocity into the reversal`() {
        var value = 0f
        var velocity = 0f
        repeat(8) {
            val sample = PillSpring.step(value, velocity, 1f, FRAME)
            value = sample.value
            velocity = sample.velocity
        }
        val flipValue = value
        val flipVelocity = velocity
        assertTrue("after 8 frames the pill should be well in: $flipValue", flipValue in 0.5f..1f)
        assertTrue("and still moving toward the keyboard: $flipVelocity", flipVelocity > 0f)

        val reversed = PillSpring.step(flipValue, flipVelocity, 0f, FRAME)
        // Nothing was reset and nothing jumped: the first reversed frame is the state carried
        // forward, so its value is its own velocity's step away from where the flip found it.
        assertEquals(flipValue + reversed.velocity * FRAME, reversed.value, 1e-4f)
        // ...and it starts from the flip's velocity, which is what makes this a reversal rather
        // than a restart: a zeroed velocity takes a different first frame.
        val asIfRestarted = PillSpring.step(flipValue, 0f, 0f, FRAME)
        assertNotEquals(asIfRestarted.velocity, reversed.velocity)
        assertTrue("a reversal must not stall: ${reversed.velocity}", reversed.velocity < 0f)

        var v = reversed.value
        var vel = reversed.velocity
        var elapsedMs = 0
        while (!PillSpring.isAtRest(v, vel, 0f) && elapsedMs < 1_000) {
            val sample = PillSpring.step(v, vel, 0f, FRAME)
            v = sample.value
            vel = sample.velocity
            elapsedMs += (FRAME * 1_000f).toInt()
        }
        println("PillSpring: the reversal settled in ${elapsedMs}ms")
        assertTrue("the reversal settled in $elapsedMs ms", elapsedMs in 1..300)
        assertEquals("the reversal must end at the off-screen start", 0f, v, 0f)
        assertEquals(0f, vel, 0f)
    }

    @Test
    fun `a long frame is clamped, and the integration is frame-rate independent at small steps`() {
        // A dropped frame must not blow the motion up: 200 ms behaves exactly as MAX_STEP_SECONDS.
        assertEquals(PillSpring.MAX_STEP_SECONDS, 0.05f, 0f)
        assertEquals(
            PillSpring.step(0.1f, 1f, 1f, PillSpring.MAX_STEP_SECONDS),
            PillSpring.step(0.1f, 1f, 1f, 0.2f),
        )

        // Frame-rate independence holds while the step is small against the spring's own period
        // (w = sqrt(1500) ~ 38.7 rad/s): one 4 ms step and two 2 ms steps land within 1e-2.
        val oneStep = PillSpring.step(0f, 0f, 1f, 0.004f)
        var value = 0f
        var velocity = 0f
        repeat(2) {
            val sample = PillSpring.step(value, velocity, 1f, 0.002f)
            value = sample.value
            velocity = sample.velocity
        }
        assertEquals(oneStep.value, value, 1e-2f)
    }

    @Test
    fun `the step size is where frame-rate independence stops holding`() {
        // Recorded, not asserted as an equality: the brief's "one 32 ms step and two 16 ms steps
        // land within 1e-2" does NOT hold for the pinned semi-implicit Euler. At w*dt = 1.24 the
        // first frame's travel is roughly doubled (measured: 1.536 for one 32 ms step against
        // 0.529 for two 16 ms steps), and it even passes the target. Semi-implicit Euler's error
        // grows with w*dt, which is why the tick clamps dt instead of trusting the frame time.
        val one32 = PillSpring.step(0f, 0f, 1f, 0.032f)
        var value = 0f
        var velocity = 0f
        repeat(2) {
            val sample = PillSpring.step(value, velocity, 1f, FRAME)
            value = sample.value
            velocity = sample.velocity
        }
        assertTrue("a 32 ms frame should be seen to overshoot: ${one32.value}", one32.value > 1f)
        assertTrue(
            "the two paths must not be conflated: ${one32.value} vs $value",
            kotlin.math.abs(one32.value - value) > 0.1f,
        )
    }
}
