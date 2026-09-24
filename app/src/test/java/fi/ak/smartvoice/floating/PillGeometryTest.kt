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
    fun `the recording wave travels right and advances 1 point 2 crests per second`() {
        assertEquals(3f, WaterSurface.RECORDING_LOBES, 0f)
        // 1.2 crests/second = 2 * PI * 1.2 rad/s ≈ 7.5398 rad/s
        val expectedOmega = (2.0 * kotlin.math.PI * 1.2).toFloat()
        assertEquals(expectedOmega, WaterSurface.OMEGA_RECORDING, 1e-3f)

        // As phase increases, a crest moves in the positive u direction (left to right)
        val phase0 = 0f
        val phase1 = 0.5f
        val s0 = WaterSurface.samples(phase = phase0, amplitude = 1f)
        val s1 = WaterSurface.samples(phase = phase1, amplitude = 1f)

        val peakIndex0 = s0.indices.maxByOrNull { s0[it] } ?: 0
        val peakIndex1 = s1.indices.maxByOrNull { s1[it] } ?: 0
        assertTrue("peak must travel right (higher index) as phase advances", peakIndex1 >= peakIndex0)
    }

    @Test
    fun `recording packet peak-to-peak equals 1 point 95dp at silence floor and 8 point 0dp at full voice`() {
        // At level 0 (silence floor): frac = MIN_AMPLITUDE = 0.22
        val silenceAmp = WaterSurface.amplitudeFor(0f)
        assertEquals(0.22f, silenceAmp, 1e-5f)
        // In a box of half-height 4.44dp:
        val silenceP2pDp = 2f * silenceAmp * PillLayout.RECORDING_HALF_HEIGHT_DP
        assertEquals(1.95f, silenceP2pDp, 0.01f)
        assertEquals(PillLayout.MARK_PEAK_TO_PEAK_DP, silenceP2pDp, 0.01f)

        // At level 1 (full voice): frac = MAX_AMPLITUDE = 0.90
        val voiceAmp = WaterSurface.amplitudeFor(1f)
        assertEquals(0.90f, voiceAmp, 1e-5f)
        // In a box of half-height 4.44dp:
        val voiceP2pDp = 2f * voiceAmp * PillLayout.RECORDING_HALF_HEIGHT_DP
        assertEquals(8.0f, voiceP2pDp, 0.01f)

        // The maximum envelope extent across space-time reaches +/-amp
        var maxPosSilence = 0f
        var maxNegSilence = 0f
        for (step in 0..100) {
            val phase = step * (2f * kotlin.math.PI.toFloat() / 100f)
            val sSilence = WaterSurface.samples(phase = phase, amplitude = silenceAmp)
            maxPosSilence = maxOf(maxPosSilence, sSilence.maxOrNull()!!)
            maxNegSilence = minOf(maxNegSilence, sSilence.minOrNull()!!)
        }
        assertEquals(silenceAmp, maxPosSilence, 0.01f)
        assertEquals(-silenceAmp, maxNegSilence, 0.01f)
        val envelopeP2pSilence = (maxPosSilence - maxNegSilence) * PillLayout.RECORDING_HALF_HEIGHT_DP
        assertEquals(1.95f, envelopeP2pSilence, 0.02f)
    }

    @Test
    fun `ready wave travels right at 0 point 44 crests per second with flat envelope`() {
        assertEquals(2f, WaterSurface.RESTING_LOBES, 0f)
        val expectedOmega = (2.0 * kotlin.math.PI * 0.44).toFloat()
        assertEquals(expectedOmega, WaterSurface.OMEGA_READY, 0.01f)

        // At phase 0, readySamples equals restingSamples
        val atRest = WaterSurface.restingSamples()
        val atReady0 = WaterSurface.readySamples(phase = 0f)
        for (i in atRest.indices) {
            assertEquals(atRest[i], atReady0[i], 1e-6f)
        }

        // As phase advances, peaks travel right (higher index)
        val atReady1 = WaterSurface.readySamples(phase = 0.5f)
        val peak0 = atReady0.indices.maxByOrNull { atReady0[it] } ?: 0
        val peak1 = atReady1.indices.maxByOrNull { atReady1[it] } ?: 0
        assertTrue("ready peak must travel right as phase advances", peak1 > peak0)
    }

    @Test
    fun `the recording wave keeps a visible wave at silence floor`() {
        assertTrue(WaterSurface.RECORDING_LOBES == 3f)
        assertTrue(WaterSurface.RESTING_LOBES == 2f)
        assertTrue("silence amplitude must be positive", WaterSurface.MIN_AMPLITUDE > 0.15f)
        // At silence floor, amplitudeFor(0f) is MIN_AMPLITUDE (0.22f) so wave never collapses
        val silenceSamples = WaterSurface.samples(phase = 1.0f, amplitude = WaterSurface.amplitudeFor(0f))
        val peak = silenceSamples.maxOf { abs(it) }
        assertTrue("wave must remain visible at silence", peak >= WaterSurface.MIN_AMPLITUDE * 0.9f)
    }

    @Test
    fun `the recording surface uses most of its height at full speech`() {
        // CHUNK-3B: the user asked for a significantly taller animated waveform.
        assertTrue(WaterSurface.MAX_AMPLITUDE >= 0.7f)
        assertTrue("must not clip", WaterSurface.MAX_AMPLITUDE <= 0.9f)
        assertTrue(WaterSurface.MIN_AMPLITUDE >= 0.15f)
    }

    @Test
    fun `the resting mark has two lobes each side with flat envelope and exact zeros and peaks`() {
        val samples = WaterSurface.restingSamples()
        val n = WaterSurface.SEGMENTS // 96
        fun at(u: Float) = samples[(((u + 1f) / 2f) * n).toInt()]

        // Zeros at u = -1, -0.5, 0, +0.5, +1
        assertEquals("zero at u=-1", 0f, at(-1f), 1e-5f)
        assertEquals("zero at u=-0.5", 0f, at(-0.5f), 1e-5f)
        assertEquals("zero at u=0", 0f, at(0f), 1e-5f)
        assertEquals("zero at u=+0.5", 0f, at(0.5f), 1e-5f)
        assertEquals("zero at u=+1", 0f, at(1f), 1e-5f)

        // Peak locations at u = -0.75, -0.25, +0.25, +0.75
        val peak1 = at(-0.75f) // negative lobe
        val peak2 = at(-0.25f) // positive lobe
        val peak3 = at(+0.25f) // negative lobe
        val peak4 = at(+0.75f) // positive lobe

        // All 4 lobes have equal peak magnitude
        val expectedAmp = WaterSurface.RESTING_AMPLITUDE
        assertEquals(-expectedAmp, peak1, 1e-5f)
        assertEquals(expectedAmp, peak2, 1e-5f)
        assertEquals(-expectedAmp, peak3, 1e-5f)
        assertEquals(expectedAmp, peak4, 1e-5f)

        // Peak magnitude * box half-height equals 0.975dp (1.95dp peak-to-peak)
        val boxHalfHeightDp = PillLayout.MARK_BOX_HEIGHT_DP / 2f
        val peakHeightDp = expectedAmp * boxHalfHeightDp
        assertEquals(PillLayout.MARK_AMPLITUDE_DP, peakHeightDp, 1e-5f)
        assertEquals(1.95f, peakHeightDp * 2f, 1e-5f)
    }

    @Test
    fun `pill layout tokens match the locked visual design spec`() {
        assertEquals(35f, PillLayout.WIDTH_DP, 0f)
        assertEquals(55.65f, PillLayout.HEIGHT_DP, 0f)
        assertEquals(PillLayout.WIDTH_DP / 2f, PillLayout.RADIUS_DP, 0f)
        assertEquals(1.75f, PillLayout.BORDER_WIDTH_DP, 0f)

        assertEquals(24f, PillLayout.MARK_WIDTH_DP, 0f)
        assertEquals(2.17f, PillLayout.MARK_BOX_HEIGHT_DP, 0f)
        assertEquals(1.95f, PillLayout.MARK_PEAK_TO_PEAK_DP, 0f)
        assertEquals(0.975f, PillLayout.MARK_AMPLITUDE_DP, 0f)
        assertEquals(1.75f, PillLayout.MARK_STROKE_DP, 0f)

        assertEquals(0xFF0F1416L, PillLayout.COLOR_FILL_DARK)
        assertEquals(0xFFFFFFFFL, PillLayout.COLOR_FILL_LIGHT)
        assertEquals(0xFF191919L, PillLayout.COLOR_BORDER_DARK)
        assertEquals(0xFFAFAFAFL, PillLayout.COLOR_BORDER_LIGHT)

        assertEquals(4.44f, PillLayout.RECORDING_HALF_HEIGHT_DP, 0f)
        assertEquals(8.88f, PillLayout.RECORDING_BOX_HEIGHT_DP, 0f)

        assertEquals(0xFFFF9248L, PillLayout.COLOR_ACCENT_DARK)
        assertEquals(0xFFEA580CL, PillLayout.COLOR_ACCENT_LIGHT)
        assertEquals(0xFFDB7F40L, PillLayout.COLOR_WAVEFORM_DARK)
        assertEquals(0xFFD55A1AL, PillLayout.COLOR_WAVEFORM_LIGHT)
        assertEquals(0xFF4ADE80L, PillLayout.COLOR_OK_DARK)
        assertEquals(0xFF16A34AL, PillLayout.COLOR_OK_LIGHT)
        assertEquals(0xFFFF6766L, PillLayout.COLOR_BAD_DARK)
        assertEquals(0xFFE5484DL, PillLayout.COLOR_BAD_LIGHT)
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
            assertTrue("max adjacent step $maxStep suggests a jump", maxStep < 0.8f)
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
