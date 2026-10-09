package io.github.alexeikramm.sovereign.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PillSnapTest {

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val viewWidth = 120 // ~40dp at 3x
    private val viewHeight = 180 // ~60dp at 3x
    private val margin = 24 // ~8dp at 3x

    @Test
    fun `targetEdge resolves END when pill is on the right half of screen in LTR`() {
        // x is offset from right edge: x=50 means pill is 50px from right edge -> right half
        val edge = PillSnap.targetEdge(
            currentX = 50,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            isRtl = false,
        )
        assertEquals(PillEdge.END, edge)
    }

    @Test
    fun `targetEdge resolves START when pill is on the left half of screen in LTR`() {
        // x=800 means pill is 800px from right edge -> left half
        val edge = PillSnap.targetEdge(
            currentX = 800,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            isRtl = false,
        )
        assertEquals(PillEdge.START, edge)
    }

    @Test
    fun `targetEdge tie resolves to END`() {
        // Center of screen is 540.
        // In LTR: pillCentreX = screenWidth - currentX - viewWidth / 2
        // For centreX == 540: 1080 - currentX - 60 = 540 => currentX = 480
        val edge = PillSnap.targetEdge(
            currentX = 480,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            isRtl = false,
        )
        assertEquals(PillEdge.END, edge)
    }

    @Test
    fun `snapTargetX calculates correct distance from right edge for END in LTR`() {
        val targetX = PillSnap.snapTargetX(
            edge = PillEdge.END,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            marginPx = margin,
            isRtl = false,
        )
        // Right edge in LTR has distance = margin
        assertEquals(margin, targetX)
    }

    @Test
    fun `snapTargetX calculates correct distance from right edge for START in LTR`() {
        val targetX = PillSnap.snapTargetX(
            edge = PillEdge.START,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            marginPx = margin,
            isRtl = false,
        )
        // Left edge in LTR has distance from right edge = screenWidth - viewWidth - margin
        assertEquals(screenWidth - viewWidth - margin, targetX)
    }

    @Test
    fun `snapPosition preserves current Y while snapping X to nearer edge`() {
        val current = PillPoint(x = 500, y = 350)
        val snapped = PillSnap.snapPosition(
            current = current,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            marginPx = margin,
            isRtl = false,
        )
        // x=500 -> centreX = 1080 - 500 - 60 = 520 < 540 -> START edge
        assertEquals(screenWidth - viewWidth - margin, snapped.x)
        assertEquals(350, snapped.y)
    }

    @Test
    fun `snapPosition clamps Y within screen bounds`() {
        val overflowY = PillPoint(x = 100, y = 3000)
        val snapped = PillSnap.snapPosition(
            current = overflowY,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            marginPx = margin,
            isRtl = false,
        )
        assertEquals(margin, snapped.x)
        assertEquals(screenHeight - viewHeight - margin, snapped.y)
    }

    @Test
    fun `isSnapped reports true only when within tolerance`() {
        // Near END edge (margin = 24)
        assertTrue(PillSnap.isSnapped(24, screenWidth, viewWidth, margin, tolerancePx = 1))
        assertTrue(PillSnap.isSnapped(25, screenWidth, viewWidth, margin, tolerancePx = 1))
        assertFalse(PillSnap.isSnapped(26, screenWidth, viewWidth, margin, tolerancePx = 1))

        // In free space
        assertFalse(PillSnap.isSnapped(400, screenWidth, viewWidth, margin, tolerancePx = 1))

        // Near START edge (1080 - 120 - 24 = 936)
        assertTrue(PillSnap.isSnapped(936, screenWidth, viewWidth, margin, tolerancePx = 1))
        assertTrue(PillSnap.isSnapped(935, screenWidth, viewWidth, margin, tolerancePx = 1))
        assertFalse(PillSnap.isSnapped(930, screenWidth, viewWidth, margin, tolerancePx = 1))
    }

    @Test
    fun `RTL mirrors target edges correctly`() {
        // In RTL: START is right physical edge, END is left physical edge
        val startTargetX = PillSnap.snapTargetX(
            edge = PillEdge.START,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            marginPx = margin,
            isRtl = true,
        )
        assertEquals(margin, startTargetX)

        val endTargetX = PillSnap.snapTargetX(
            edge = PillEdge.END,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            marginPx = margin,
            isRtl = true,
        )
        assertEquals(screenWidth - viewWidth - margin, endTargetX)
    }

    @Test
    fun `projectedDeltaY produces proportional vertical follow-through`() {
        // Zero velocity -> 0 delta
        assertEquals(0f, PillSnap.projectedDeltaY(0f), 0.001f)

        // 2000 px/s / 20 ~ 100 * 0.25 attenuation = 25px
        val deltaUp = PillSnap.projectedDeltaY(2000f, attenuation = 0.25f)
        assertEquals(25f, deltaUp, 0.5f)

        // Negative velocity (downward flick)
        val deltaDown = PillSnap.projectedDeltaY(-2000f, attenuation = 0.25f)
        assertEquals(-25f, deltaDown, 0.5f)

        // Attenuation 1.0 (unattenuated)
        val fullDelta = PillSnap.projectedDeltaY(2000f, attenuation = 1.0f)
        assertEquals(100f, fullDelta, 0.5f)
    }

    @Test
    fun `targetEdge with horizontal velocity throws target across center line`() {
        // Pill is on the right half (currentX = 100 -> centreX = 1080 - 100 - 60 = 920 > 540)
        // With zero velocity, it targets END (right edge)
        val edgeNoVel = PillSnap.targetEdge(
            currentX = 100,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            velocityX = 0f,
        )
        assertEquals(PillEdge.END, edgeNoVel)

        // User flicks towards left: velocityX = +10000 px/s
        // Projected offset = 100 + 10000 / 20 = 600 -> centreX = 1080 - 600 - 60 = 420 < 540
        // Target edge becomes START (left edge)!
        val edgeFlickLeft = PillSnap.targetEdge(
            currentX = 100,
            screenWidthPx = screenWidth,
            viewWidthPx = viewWidth,
            velocityX = 10000f,
        )
        assertEquals(PillEdge.START, edgeFlickLeft)
    }

    @Test
    fun `snapPosition with 2D velocity projects both X and Y with attenuation and bounds`() {
        // Pill at x=100 (right side), y=500
        // Flick up-left: velocityX = +10000 px/s (left), velocityY = +2000 px/s (up)
        val current = PillPoint(x = 100, y = 500)
        val snapped = PillSnap.snapPosition(
            current = current,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            marginPx = margin,
            velocity = PillVelocity(x = 10000f, y = 2000f),
            attenuation = 0.25f,
        )
        // X target is left edge (START)
        assertEquals(screenWidth - viewWidth - margin, snapped.x)
        // Y target projected by 25px -> 525
        assertEquals(525, snapped.y)
    }

    @Test
    fun `snapPosition with strong downward velocity clamps at bottom margin`() {
        val current = PillPoint(x = 50, y = 30)
        // Strong downward flick
        val snapped = PillSnap.snapPosition(
            current = current,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            marginPx = margin,
            velocity = PillVelocity(x = 0f, y = -10000f),
        )
        // Clamped at bottom margin
        assertEquals(margin, snapped.y)
    }

    @Test
    fun `PillSnap step starts with gentle acceleration and settles without overshoot`() {
        var progress = 0f
        var velocity = 0f
        val dt = 0.016f

        // First frame: soft onset (progress is small, velocity is ramping up)
        val frame1 = PillSnap.step(progress, velocity, 1f, dt)
        assertTrue(frame1.value > 0f)
        assertTrue(frame1.value < 0.15f) // Under 15% in first 16ms (soft onset)
        assertTrue(frame1.velocity > 0f)

        progress = frame1.value
        velocity = frame1.velocity

        // Simulate 35 frames (~560ms)
        for (i in 2..35) {
            val step = PillSnap.step(progress, velocity, 1f, dt)
            progress = step.value
            velocity = step.velocity
        }

        // Must settle cleanly at target 1f
        assertEquals(1f, progress, 0.001f)
        assertEquals(0f, velocity, 0.001f)
        assertTrue(PillSnap.isAtRest(progress, velocity, 1f))
    }

    @Test
    fun `PillSnap step with high stiffness settles faster than low stiffness`() {
        val dt = 0.016f

        // Run low stiffness (100f) for 10 frames (~160ms)
        var pLow = 0f
        var vLow = 0f
        for (i in 1..10) {
            val sample = PillSnap.step(pLow, vLow, 1f, dt, stiffness = 100f, dampingRatio = 1f)
            pLow = sample.value
            vLow = sample.velocity
        }

        // Run high stiffness (1500f) for 10 frames (~160ms)
        var pHigh = 0f
        var vHigh = 0f
        for (i in 1..10) {
            val sample = PillSnap.step(pHigh, vHigh, 1f, dt, stiffness = 1500f, dampingRatio = 1f)
            pHigh = sample.value
            vHigh = sample.velocity
        }

        // High stiffness spring moves much further towards target in 160ms than low stiffness
        assertTrue("High stiffness should make more progress than low stiffness", pHigh > pLow)
        assertTrue("High stiffness progress should be near completion", pHigh > 0.9f)
        assertTrue("Low stiffness progress should be under 0.6", pLow < 0.6f)
    }

    @Test
    fun `snapPosition with custom attenuation scales vertical momentum proportionally`() {
        val current = PillPoint(x = 100, y = 500)
        val velocity = PillVelocity(x = 0f, y = 2000f)

        val snappedZero = PillSnap.snapPosition(
            current = current,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            marginPx = margin,
            velocity = velocity,
            attenuation = 0.0f,
        )
        // With attenuation 0.0, vertical position is strictly unchanged
        assertEquals(500, snappedZero.y)

        val snappedHalf = PillSnap.snapPosition(
            current = current,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            marginPx = margin,
            velocity = velocity,
            attenuation = 0.5f,
        )
        // 2000 / 20 * 0.5 = 50px delta -> y = 550
        assertEquals(550, snappedHalf.y)
    }

    @Test
    fun `DRAG_MARGIN_DP is half the original 8dp`() {
        // 8dp reduced by one half = 4dp.
        assertEquals(4f, PillDock.DRAG_MARGIN_DP, 0.001f)
    }
}
