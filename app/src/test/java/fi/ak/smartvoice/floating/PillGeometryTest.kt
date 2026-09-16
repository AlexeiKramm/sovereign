package fi.ak.smartvoice.floating

import fi.ak.smartvoice.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** One millisecond in nanoseconds — the tracker's clock unit. */
private const val MS = 1_000_000L

/**
 * The pill's geometry is the part of CHUNK-3 that is hard to verify by dragging on a device and
 * easy to get subtly wrong (an off-screen pill, a wave that clips or that does not react to
 * speech). These tests pin it.
 */
class PillGeometryTest {

    // --- Docking ---------------------------------------------------------------

    @Test
    fun `default dock is bottom-right at the plan's insets`() {
        val docked = PillDock.defaultPosition(density = 1f)
        assertEquals(PillDock.DEFAULT_INSET_DP.toInt(), docked.x)
        assertEquals(PillDock.DEFAULT_BOTTOM_OFFSET_DP.toInt(), docked.y)
    }

    @Test
    fun `default dock scales with display density`() {
        val atThree = PillDock.defaultPosition(density = 3f)
        assertEquals((PillDock.DEFAULT_INSET_DP * 3).toInt(), atThree.x)
        assertEquals((PillDock.DEFAULT_BOTTOM_OFFSET_DP * 3).toInt(), atThree.y)
    }

    @Test
    fun `restore falls back to the default dock when the pill has never been dragged`() {
        val restored = PillDock.restore(
            storedX = AppSettings.PILL_POSITION_UNSET,
            storedY = AppSettings.PILL_POSITION_UNSET,
            density = 2f,
        )
        assertEquals(PillDock.defaultPosition(2f), restored)
    }

    @Test
    fun `restore uses a half-set stored position only when both axes are set`() {
        // A single unset axis means the pair is not trustworthy; the default dock wins.
        val halfSet = PillDock.restore(
            storedX = 500,
            storedY = AppSettings.PILL_POSITION_UNSET,
            density = 1f,
        )
        assertEquals(PillDock.defaultPosition(1f), halfSet)

        val bothSet = PillDock.restore(storedX = 120, storedY = 240, density = 1f)
        assertEquals(PillPoint(120, 240), bothSet)
    }

    @Test
    fun `clamp keeps the whole view on screen`() {
        val clamped = PillDock.clamp(
            point = PillPoint(x = 10_000, y = 10_000),
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            viewWidthPx = 300,
            viewHeightPx = 120,
            marginPx = 8,
        )
        assertEquals(1080 - 300 - 8, clamped.x)
        assertEquals(2400 - 120 - 8, clamped.y)
    }

    @Test
    fun `clamp pulls negative and off-screen positions back into range`() {
        val clamped = PillDock.clamp(
            point = PillPoint(x = -50, y = -1),
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            viewWidthPx = 300,
            viewHeightPx = 120,
            marginPx = 8,
        )
        assertEquals(PillPoint(0, 0), clamped)
    }

    @Test
    fun `clamp leaves an already-valid position alone`() {
        val valid = PillPoint(x = 100, y = 200)
        val clamped = PillDock.clamp(valid, 1080, 2400, 300, 120, marginPx = 8)
        assertEquals(valid, clamped)
    }

    @Test
    fun `clamp cannot produce a negative maximum when the view is wider than the screen`() {
        // Rotation with a very wide card: the best that can be done is x = 0, not a crash/negative.
        val clamped = PillDock.clamp(
            point = PillPoint(x = 40, y = 40),
            screenWidthPx = 200,
            screenHeightPx = 200,
            viewWidthPx = 340,
            viewHeightPx = 340,
            marginPx = 8,
        )
        assertEquals(PillPoint(0, 0), clamped)
    }

    // --- Voice level mapping ------------------------------------------------

    @Test
    fun `the visual level is a dB window, so ordinary speech is not pinned at the top`() {
        // The bug: `rms * k` saturates long before ordinary speech, which is ~-30..-20 dBFS.
        assertEquals(0f, VoiceLevel.perceptual(0.0018f), 0.03f)   // ~ -55 dBFS -> floor
        assertEquals(1f, VoiceLevel.perceptual(0.25f), 0.03f)     // ~ -12 dBFS -> full
        val speech = VoiceLevel.perceptual(0.03f)                 // ~ -30 dBFS
        assertTrue("ordinary speech should sit mid-range, was $speech", speech in 0.4f..0.8f)
        val loud = VoiceLevel.perceptual(0.1f)                    // ~ -20 dBFS
        assertTrue("a raised voice should have headroom, was $loud", loud in 0.7f..0.95f)
    }

    @Test
    fun `the visual level is monotonic, clamped and safe at silence`() {
        assertEquals(0f, VoiceLevel.perceptual(0f), 0f)
        assertEquals(0f, VoiceLevel.perceptual(-1f), 0f)
        assertEquals(1f, VoiceLevel.perceptual(1f), 0f)
        var previous = -1f
        for (i in 0..50) {
            val value = VoiceLevel.perceptual(i / 100f)
            assertTrue("level must rise with rms", value >= previous)
            assertTrue(value in 0f..1f)
            previous = value
        }
    }

    // --- The water surface -----------------------------------------------------

    @Test
    fun `amplitude rises with audio level and stays within the drawable band`() {
        assertEquals(WaterSurface.MIN_AMPLITUDE, WaterSurface.amplitudeFor(0f), 1e-6f)
        assertEquals(WaterSurface.MAX_AMPLITUDE, WaterSurface.amplitudeFor(1f), 1e-6f)

        val quiet = WaterSurface.amplitudeFor(0.1f)
        val loud = WaterSurface.amplitudeFor(0.9f)
        assertTrue("louder speech must mean a taller wave", loud > quiet)

        // Out-of-range input must not escape the band.
        assertEquals(WaterSurface.MIN_AMPLITUDE, WaterSurface.amplitudeFor(-3f), 1e-6f)
        assertEquals(WaterSurface.MAX_AMPLITUDE, WaterSurface.amplitudeFor(9f), 1e-6f)
    }

    @Test
    fun `surface samples exactly one value per segment plus the endpoint`() {
        val samples = WaterSurface.samples(phase = 0f, amplitude = 0.4f)
        assertEquals(WaterSurface.SEGMENTS + 1, samples.size)
    }

    @Test
    fun `surface is anchored flat at both card edges`() {
        val samples = WaterSurface.samples(phase = 1.3f, amplitude = 0.46f)
        assertEquals(0f, samples.first(), 1e-6f)
        assertEquals(0f, samples.last(), 1e-6f)
    }

    @Test
    fun `surface never exceeds the amplitude it was given`() {
        val amplitude = 0.3f
        for (phase in 0..20) {
            val samples = WaterSurface.samples(phase = phase * 0.31f, amplitude = amplitude)
            samples.forEach { value ->
                assertTrue("sample $value escaped +/-$amplitude", abs(value) <= amplitude + 1e-6f)
            }
        }
    }

    @Test
    fun `silence collapses the surface to a flat line`() {
        val samples = WaterSurface.samples(phase = 2f, amplitude = 0f)
        samples.forEach { assertEquals(0f, it, 1e-6f) }
    }

    @Test
    fun `the resting shape is a centre-node packet, not a flat line`() {
        val samples = WaterSurface.restingSamples()
        val peak = samples.maxOf { abs(it) }
        assertTrue("resting peak $peak is too flat to read as a wave", peak > 0.5f)

        // The centre is always a node, and the edges are anchored flat.
        assertEquals(0f, samples[WaterSurface.SEGMENTS / 2], 1e-6f)
        assertEquals(0f, samples.first(), 1e-6f)
        assertEquals(0f, samples.last(), 1e-6f)
    }

    @Test
    fun `the resting shape mirrors about its centre with opposite sign`() {
        // The user's "high on the left, negative on the right" symmetry: sample[i] == -sample[n-i].
        val samples = WaterSurface.restingSamples()
        val n = samples.size - 1
        for (i in samples.indices) {
            assertEquals(-samples[n - i], samples[i], 1e-5f)
        }
    }

    @Test
    fun `the recording wave is a centre-node antisymmetric packet at every phase`() {
        val n = WaterSurface.SEGMENTS
        for (phase in 0..12) {
            val samples = WaterSurface.samples(phase = phase * 0.52f, amplitude = 1f)
            assertEquals("centre must stay a node", 0f, samples[n / 2], 1e-6f)
            assertEquals(0f, samples.first(), 1e-6f)
            assertEquals(0f, samples.last(), 1e-6f)
            for (i in samples.indices) {
                assertEquals(-samples[n - i], samples[i], 1e-5f)
            }
        }
    }

    @Test
    fun `the recording surface uses most of its height at full speech`() {
        // CHUNK-3B: the user asked for a significantly taller animated waveform.
        assertTrue(WaterSurface.MAX_AMPLITUDE >= 0.7f)
        assertTrue("must not clip", WaterSurface.MAX_AMPLITUDE <= 0.9f)
        assertTrue(WaterSurface.MIN_AMPLITUDE >= 0.15f)
    }

    @Test
    fun `the resting packet has a big, a medium and a tiny lobe each side`() {
        val samples = WaterSurface.restingSamples()
        val n = WaterSurface.SEGMENTS
        fun at(u: Float) = samples[((u + 1f) * n / 2f).toInt()]

        // Lobe centres for k = 3π sit at u = ±1/6, ±1/2, ±5/6.
        val inner = abs(at(-1f / 6f))
        val mid = abs(at(-1f / 2f))
        val outer = abs(at(-5f / 6f))
        assertTrue("inner=$inner mid=$mid outer=$outer", inner > mid && mid > outer)
        assertTrue("the outer lobe should still be visible", outer > 0.05f)

        // The user's convention: positive on the left, negative on the right.
        assertTrue(at(-1f / 6f) > 0f)
        assertTrue(at(1f / 6f) < 0f)
    }

    @Test
    fun `the recording wave keeps a visible wave and a centre node at all times`() {
        assertTrue(WaterSurface.RECORDING_LOBES >= 2f)
        assertTrue(WaterSurface.RESTING_LOBES >= 1f)
        assertTrue(WaterSurface.WAVE_FLOOR in 0f..1f)
        assertTrue("the wave must never fully collapse", WaterSurface.WAVE_FLOOR > 0.1f)
    }

    @Test
    fun `the wave is smooth across the centre with no sharp angle`() {
        // The bug the user saw: a travelling `sign(u)` term jumps at the centre by 2·sin(phase).
        // A smooth wave must have no large step between adjacent samples.
        val waveforms = listOf(
            WaterSurface.samples(phase = 0.7f, amplitude = 1f),
            WaterSurface.restingSamples(),
        )
        for (samples in waveforms) {
            var maxStep = 0f
            for (i in 1 until samples.size) {
                maxStep = maxOf(maxStep, abs(samples[i] - samples[i - 1]))
            }
            assertTrue("max adjacent step $maxStep suggests a jump", maxStep < 0.6f)
        }
    }

    @Test
    fun `the surface oscillates as the phase advances`() {
        val atRest = WaterSurface.samples(phase = 0f, amplitude = 0.4f)
        val later = WaterSurface.samples(phase = 0.9f, amplitude = 0.4f)
        val moved = atRest.indices.count { abs(atRest[it] - later[it]) > 1e-4f }
        assertTrue("the wave must actually change, moved=$moved", moved > WaterSurface.SEGMENTS / 2)
    }

    @Test
    fun `louder speech makes a taller surface at the same instant`() {
        val quiet = WaterSurface.samples(phase = 0.5f, amplitude = WaterSurface.amplitudeFor(0.05f))
        val loud = WaterSurface.samples(phase = 0.5f, amplitude = WaterSurface.amplitudeFor(0.95f))
        val quietPeak = quiet.maxOf { abs(it) }
        val loudPeak = loud.maxOf { abs(it) }
        assertTrue("loud=$loudPeak quiet=$quietPeak", loudPeak > quietPeak * 1.5f)
    }

    @Test
    fun `a single segment still yields a usable two-point line`() {
        val samples = WaterSurface.samples(phase = 0.2f, amplitude = 0.4f, segments = 1)
        assertEquals(2, samples.size)
        assertFalse(samples.any { it.isNaN() })
    }

    // --- Drag release velocity -------------------------------------------------

    @Test
    fun `velocity is the offset travelled over the tracked time`() {
        val tracker = DragVelocityTracker()
        tracker.reset(1_000 * MS, x = 0f, y = 0f)
        tracker.sample(1_050 * MS, x = -500f, y = -250f)   // a rightward/upward flick
        val velocity = tracker.velocity()
        assertEquals(-10_000f, velocity.x, 1f)
        assertEquals(-5_000f, velocity.y, 1f)
    }

    @Test
    fun `velocity needs at least two samples`() {
        val tracker = DragVelocityTracker()
        tracker.reset(0L, 10f, 10f)
        assertEquals(PillVelocity(0f, 0f), tracker.velocity())
    }

    @Test
    fun `a slow crawl before a flick does not dilute the release speed`() {
        val tracker = DragVelocityTracker(horizonNanos = 100 * MS)
        tracker.reset(0L, 0f, 0f)
        // A long, slow drift...
        tracker.sample(500 * MS, 10f, 10f)
        tracker.sample(1_000 * MS, 20f, 20f)
        tracker.sample(1_500 * MS, 30f, 30f)
        // ...then a fast final flick, which is what the fling should inherit.
        tracker.sample(1_550 * MS, 130f, 30f)
        val velocity = tracker.velocity()
        assertEquals(2_000f, velocity.x, 1f)
        assertEquals(0f, velocity.y, 1f)
    }

    @Test
    fun `samples past the buffer capacity keep the newest ones`() {
        val tracker = DragVelocityTracker(horizonNanos = 10_000 * MS, capacity = 3)
        tracker.reset(0L, 0f, 0f)
        tracker.sample(10 * MS, 100f, 0f)
        tracker.sample(20 * MS, 200f, 0f)
        tracker.sample(30 * MS, 300f, 0f)
        // Only the last three survive: 100 -> 300 over 20 ms.
        assertEquals(10_000f, tracker.velocity().x, 1f)
    }

    @Test
    fun `reset drops the previous gesture's motion`() {
        val tracker = DragVelocityTracker()
        tracker.reset(0L, 0f, 0f)
        tracker.sample(50 * MS, 5_000f, 0f)
        tracker.reset(10_000 * MS, 0f, 0f)
        tracker.sample(10_050 * MS, -100f, 0f)
        assertEquals(-2_000f, tracker.velocity().x, 1f)
    }

    @Test
    fun `a flick whose samples share a millisecond still yields a velocity`() {
        // The case that silently killed the fling before: sub-millisecond timing.
        val tracker = DragVelocityTracker()
        tracker.reset(atNanos = 1_000_000L, x = 0f, y = 0f)
        tracker.sample(atNanos = 1_500_000L, x = 60f, y = 0f)   // 0.5 ms later
        val velocity = tracker.velocity()
        assertEquals(120_000f, velocity.x, 100f)
    }

    @Test
    fun `velocity cannot be recovered from a single instant`() {
        val tracker = DragVelocityTracker()
        tracker.reset(1_000_000L, 0f, 0f)
        tracker.sample(1_000_000L, 500f, 0f)   // same instant
        val velocity = tracker.velocity()
        assertFalse(velocity.x.isNaN())
        assertFalse(velocity.x.isInfinite())
        assertEquals(0f, velocity.x, 0.01f)
    }

    @Test
    fun `speed is the magnitude across both axes`() {
        val velocity = PillVelocity(3f, 4f)
        assertEquals(5f, velocity.speed, 1e-4f)
    }

    // --- Fling ---

    @Test
    fun `friction sets how far a flick travels`() {
        // travel = release speed / friction
        assertEquals(250f, PillFling(frictionPerSecond = 20f).travelFor(5_000f), 0.1f)
        assertEquals(125f, PillFling(frictionPerSecond = 40f).travelFor(5_000f), 0.1f)
        assertTrue(
            "more friction must mean a shorter glide",
            PillFling(40f).travelFor(5_000f) < PillFling(20f).travelFor(5_000f),
        )
    }

    @Test
    fun `a glide never travels further than its release speed allows`() {
        val fling = PillFling(frictionPerSecond = 20f)
        val total = fling.travelFor(5_000f)
        var x = 0f
        var vx = 5_000f
        repeat(200) {
            val step = fling.advance(x = x, y = 0f, velocityX = vx, velocityY = 0f, dtSeconds = 0.008f)
            x = step.x
            vx = step.velocityX
        }
        assertEquals(total, x, 1f)
    }

    @Test
    fun `both axes share one decay so a diagonal flick stays diagonal`() {
        val fling = PillFling(frictionPerSecond = 20f)
        var sample = PillFlingSample(0f, 0f, 3_000f, 3_000f)
        repeat(50) {
            sample = fling.advance(sample.x, sample.y, sample.velocityX, sample.velocityY, 0.008f)
        }
        // Equal release speeds on both axes must stay equal for the whole glide.
        assertEquals(sample.x, sample.y, 0.5f)
    }

    @Test
    fun `the glide does not depend on the frame rate`() {
        val coarse = PillFling(frictionPerSecond = 20f)
        val fine = PillFling(frictionPerSecond = 20f)

        val coarseStep = coarse.advance(0f, 0f, 4_000f, 1_200f, 0.032f)
        var x = 0f
        var y = 0f
        var vx = 4_000f
        var vy = 1_200f
        repeat(4) {
            val step = fine.advance(x, y, vx, vy, 0.008f)
            x = step.x
            y = step.y
            vx = step.velocityX
            vy = step.velocityY
        }
        assertEquals(coarseStep.x, x, 0.5f)
        assertEquals(coarseStep.y, y, 0.5f)
    }

    @Test
    fun `the glide ends once the speed drops below the stop threshold`() {
        val fling = PillFling(frictionPerSecond = 20f)
        assertFalse(fling.isFinished(4_000f, 0f))
        assertTrue(fling.isFinished(10f, 10f))
        // ...and a real glide always gets there.
        var vx = 20_000f
        repeat(500) {
            vx = fling.advance(0f, 0f, vx, 0f, 0.016f).velocityX
        }
        assertTrue(fling.isFinished(vx, 0f))
    }

    @Test
    fun `zero friction cannot make the glide run forever`() {
        val reckless = PillFling(frictionPerSecond = 0f)
        val step = reckless.advance(0f, 0f, 1_000f, 1_000f, 0.016f)
        assertFalse(step.x.isInfinite())
        assertTrue(step.x < 10_000f)
    }

    @Test
    fun `the default friction sits inside the settings slider range and glides a sane distance`() {
        assertEquals(30f, AppSettings.DEFAULT_PILL_FLICK_FRICTION, 0f)
        assertTrue(
            AppSettings.DEFAULT_PILL_FLICK_FRICTION in
                AppSettings.MIN_PILL_FLICK_FRICTION..AppSettings.MAX_PILL_FLICK_FRICTION,
        )
        // A typical flick should glide a short way, not across the screen.
        val travel = PillFling().travelFor(5_000f)
        assertTrue("default glide travelled $travel px", travel in 100f..400f)
    }
}
