package fi.ak.smartvoice.floating

import fi.ak.smartvoice.data.AppSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * The overlay window's position, as distances from the screen's END and BOTTOM edges
 * (the window's gravity is `BOTTOM or END`). Larger [x] / [y] means further from the corner.
 */
data class PillPoint(val x: Int, val y: Int)

/**
 * Pure geometry for the pill: where it docks by default, and how a drag is kept on screen.
 *
 * Deliberately free of Android, `WindowManager` and Compose types so the JVM unit tests can
 * pin the arithmetic that is otherwise only verifiable by dragging the thing around on a phone.
 */
object PillDock {

    /** Inset from the docked corner at rest. */
    const val DEFAULT_INSET_DP = 16f

    /** How far above the bottom edge the resting pill sits — clears the keyboard's own strip. */
    const val DEFAULT_BOTTOM_OFFSET_DP = 90f

    /** Keeps the pill's edge off the very screen edge after a drag. */
    const val DRAG_MARGIN_DP = 8f

    fun defaultPosition(density: Float): PillPoint = PillPoint(
        x = (DEFAULT_INSET_DP * density).toInt(),
        y = (DEFAULT_BOTTOM_OFFSET_DP * density).toInt(),
    )

    /**
     * The dock to restore from storage: the saved spot, or the default corner when the pill
     * has never been dragged. Callers must still [clamp] the result — a saved position can be
     * off-screen after a rotation or a display change.
     */
    fun restore(storedX: Int, storedY: Int, density: Float): PillPoint {
        val neverDragged = storedX == AppSettings.PILL_POSITION_UNSET ||
            storedY == AppSettings.PILL_POSITION_UNSET
        return if (neverDragged) defaultPosition(density) else PillPoint(storedX, storedY)
    }

    /**
     * Clamps [point] so the whole view (plus [marginPx] of breathing room) stays on screen.
     * The maxima are the distances at which the view's far edge meets the opposite screen edge,
     * which is what stops a fling from carrying the pill half off the display.
     */
    fun clamp(
        point: PillPoint,
        screenWidthPx: Int,
        screenHeightPx: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
        marginPx: Int,
    ): PillPoint {
        val maxX = (screenWidthPx - viewWidthPx - marginPx).coerceAtLeast(0)
        val maxY = (screenHeightPx - viewHeightPx - marginPx).coerceAtLeast(0)
        return PillPoint(
            x = point.x.coerceIn(0, maxX),
            y = point.y.coerceIn(0, maxY),
        )
    }
}

/**
 * The recording state's waveform: a calm water surface, sampled as vertical offsets from a
 * flat centre line. Two sine components at different frequencies and speeds, tapered to zero
 * at both ends, are what make it read as water in a container rather than a single clean sine.
 */object WaterSurface {

    /**
     * Horizontal samples per draw. More is smoother; the line is drawn as a cubic bezier path
     * through these, so this trades a little path length for a lot of smoothness cheaply.
     */
    const val SEGMENTS = 56

    /** Amplitude of near-silence — the line should still look alive when the room is quiet. */
    const val MIN_AMPLITUDE = 0.22f

    /**
     * Amplitude at full speech. The strip is sized so the peaks can be tall without clipping; the
     * user wanted the peak-to-line-thickness ratio of their reference (~6:1), which needs vertical
     * room rather than a thinner line.
     */
    const val MAX_AMPLITUDE = 0.90f

    /**
     * Lobes (half-cycles) each side of the centre for the recording wave. The wave is a *centre-node*
     * packet — the centre is always zero and the two halves mirror with opposite sign — so this is
     * the lobe count on one side only. Three reads clearly at 340 dp.
     */
    const val RECORDING_LOBES = 3f

    /**
     * Lobes each side for the small resting pill. Three gives the read the user asked for — one big,
     * one medium, one tiny per side — which is clearer than two.
     */
    const val RESTING_LOBES = 3f

    /**
     * How much the recording wave's *amplitude* envelope is offset from zero, so the wave never
     * collapses to a flat line at the moment the standing oscillation crosses zero. `0.3` keeps a
     * visible wave at all times while still letting the lobes swing and invert.
     */
    const val WAVE_FLOOR = 0.3f

    /** Peak height of the small resting wave, as a fraction of the available half-height. */
    const val RESTING_AMPLITUDE = 0.90f

    /**
     * Envelope exponent: the window is `cos(πu/2)` raised to this. Any exponent above 1 keeps the
     * line meeting the flat edges with zero slope (no kink), while `1.5` tapers more gently than a
     * plain Hann (`2.0`), so the outer lobes stay visible rather than vanishing.
     */
    const val ENVELOPE_POWER = 1.5f

    /** Maps a 0..1 audio level to waveform amplitude, as a fraction of the available half-height. */
    fun amplitudeFor(audioLevel: Float): Float {
        val level = audioLevel.coerceIn(0f, 1f)
        return MIN_AMPLITUDE + level * (MAX_AMPLITUDE - MIN_AMPLITUDE)
    }

    /**
     * [segments] + 1 vertical offsets in `-amplitude..amplitude` (negative is *up*). The wave is a
     * **centre-node packet**: `env(u) * carrier(u, phase)` over `u in [-1, 1]`, where the envelope
     * fades to zero at the edges and the carrier is odd in `u`, so the centre is always exactly zero
     * and the halves mirror with opposite signs (positive on the left, negative on the right).
     *
     * The carrier is a standing wave — the antisymmetric mode of a rope pinned at the middle — with a
     * floor so it never collapses to a flat line as the oscillation crosses zero. A travelling
     * outward wave was tried and removed: an outward source is *symmetric* about the centre, so an
     * antisymmetric one cannot travel without a discontinuity (a visible sharp angle at the middle).
     *
     * Only the overall [amplitude] and the caller's phase *speed* change with the audio level; the
     * spatial structure never does, which is what keeps it coherent.
     */
    fun samples(phase: Float, amplitude: Float, segments: Int = SEGMENTS): FloatArray {
        require(segments > 0) { "segments must be positive" }
        val amp = amplitude.coerceIn(0f, 1f)
        val k = RECORDING_LOBES * PI_F
        val swing = WAVE_FLOOR + (1f - WAVE_FLOOR) * cos(phase)
        val out = FloatArray(segments + 1)
        for (i in 0..segments) {
            val u = 2f * i / segments - 1f
            out[i] = amp * envelope(u) * (-sin(k * u) * swing)
        }
        return out
    }

    /**
     * The resting pill's static shape: the same centre-node packet frozen at phase 0 — one big, one
     * medium and one tiny lobe each side, positive on the left and negative on the right, fading
     * into a flat line at the pill's edges.
     */
    fun restingSamples(segments: Int = SEGMENTS): FloatArray {
        require(segments > 0) { "segments must be positive" }
        val k = RESTING_LOBES * PI_F
        val out = FloatArray(segments + 1)
        for (i in 0..segments) {
            val u = 2f * i / segments - 1f
            out[i] = -RESTING_AMPLITUDE * envelope(u) * sin(k * u)
        }
        return out
    }

    /**
     * The edging window over `u in [-1, 1]`: `cos(πu/2)` to [ENVELOPE_POWER]. One at the centre,
     * exactly zero at both edges **and** with zero slope there, so the line meets the flat edges
     * without a visible corner while the outer lobes stay visible.
     */
    private fun envelope(u: Float): Float =
        cos(u * PI_F / 2f).coerceAtLeast(0f).pow(ENVELOPE_POWER)

    private const val PI_F = PI.toFloat()
}

/**
 * Turns a raw mic RMS into a 0..1 *visual* level on a perceptual (dBFS) scale.
 *
 * A linear map is the reason the wave "slammed to max": ordinary speech sits at roughly -30..-20
 * dBFS, so a `rms * k` map is already saturated by then and the first few syllables sweep the whole
 * range. A dB window spreads the speech range evenly and leaves headroom for a raised voice.
 */
object VoiceLevel {

    /** Below this the input is room tone; the visual level is zero. */
    const val FLOOR_DB = -55f

    /** At or above this the visual level is full (a raised voice / close mic). */
    const val CEIL_DB = -12f

    /** 0..1 visual level for a raw RMS (0..1). Monotonic, clamped, and roughly linear in dB. */
    fun perceptual(rms: Float): Float {
        val db = 20f * log10(rms.coerceAtLeast(MIN_RMS))
        return ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
    }

    /** Guards `log10(0)`. Anything quieter than this is silence anyway. */
    private const val MIN_RMS = 1e-5f
}

/** A velocity estimate in the window-offset axes (px/s). */
data class PillVelocity(val x: Float, val y: Float) {
    val speed: Float get() = kotlin.math.hypot(x, y)
}

/** One step of a fling: where the pill is now, and what is left of its speed. */
data class PillFlingSample(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
)

/**
 * The fling after the user lets go.
 *
 * Deliberately **not** `OverScroller`: that runs one 1-D fling per axis, so a diagonal flick's
 * axes outlive each other and the pill's path bends into a vertical or horizontal slide (reported
 * on the device as "it stops on x first, then only moves up"). Here the whole velocity vector
 * decays by one shared factor, so a diagonal stays diagonal and the pill stops once.
 *
 * [frictionPerSecond] is a user setting, because "how slippery the pill feels" is a taste question
 * best answered by flicking it. It is the only knob: the distance a flick travels is
 * `releaseSpeed / friction`, so lowering it makes every flick glide further.
 */
class PillFling(private val frictionPerSecond: Float = AppSettings.DEFAULT_PILL_FLICK_FRICTION) {

    /** Total distance a release at [speedPxPerSecond] will travel before it stops. */
    fun travelFor(speedPxPerSecond: Float): Float =
        speedPxPerSecond / safeFriction()

    /**
     * Advances a glide by [dtSeconds]. This is the closed-form integral of `v * exp(-k t)`, so a
     * long frame and two short ones land in the same place — the glide does not depend on the
     * device's frame rate.
     */
    fun advance(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float,
        dtSeconds: Float,
    ): PillFlingSample {
        val friction = safeFriction()
        val remaining = kotlin.math.exp(-friction * dtSeconds)
        // distance = v * (1 - e^-kt) / k
        return PillFlingSample(
            x = x + velocityX * (1f - remaining) / friction,
            y = y + velocityY * (1f - remaining) / friction,
            velocityX = velocityX * remaining,
            velocityY = velocityY * remaining,
        )
    }

    fun isFinished(velocityX: Float, velocityY: Float): Boolean =
        kotlin.math.hypot(velocityX, velocityY) < STOP_SPEED_PX_PER_SECOND

    /** Guards the division: a friction of zero would mean the pill never stops. */
    private fun safeFriction(): Float = frictionPerSecond.coerceAtLeast(MIN_SAFE_FRICTION)

    companion object {
        /** Below this speed the glide is over — without it the tail crawls forever. */
        const val STOP_SPEED_PX_PER_SECOND = 60f

        /** A friction at or below this would travel without bound. */
        private const val MIN_SAFE_FRICTION = 1f
    }
}

/**
 * Estimates a drag's release velocity from the **window offset's own trajectory**.
 *
 * Compose's `VelocityTracker` cannot be used for this: it consumes the pointer's coordinates,
 * which are relative to the overlay window — and that window is itself being moved under the
 * finger, so its local deltas collapse towards zero as the window keeps up. The estimate then
 * becomes noise, which showed up on the device as flings in the *wrong* direction. Sampling the
 * quantity we actually want to fling (the offset) has no such feedback.
 *
 * Kept deliberately simple: velocity is the offset travelled over the last [horizonMs], which is
 * the same "release speed" idea the platform uses, and is enough to make a flick feel right.
 */
class DragVelocityTracker(
    private val horizonNanos: Long = 100_000_000L,
    capacity: Int = 16,
) {
    private val capacity = capacity.coerceAtLeast(2)
    private val times = LongArray(this.capacity)
    private val xs = FloatArray(this.capacity)
    private val ys = FloatArray(this.capacity)
    private var size = 0
    private var next = 0

    fun reset(atNanos: Long, x: Float, y: Float) {
        size = 0
        next = 0
        sample(atNanos, x, y)
    }

    fun sample(atNanos: Long, x: Float, y: Float) {
        times[next] = atNanos
        xs[next] = x
        ys[next] = y
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * px/s along the offset axes; zero when there is not enough movement to go on. Timestamps are
     * nanoseconds, so a flick whose samples all land inside one millisecond still gets a real
     * elapsed time instead of a divide-by-zero and a silent loss of the fling.
     */
    fun velocity(): PillVelocity {
        if (size < 2) return PillVelocity(0f, 0f)

        val newest = (next - 1 + capacity) % capacity
        val newestTime = times[newest]
        var oldest = newest
        var step = 1
        while (step < size) {
            val index = (newest - step + capacity) % capacity
            val age = newestTime - times[index]
            if (age > horizonNanos) break
            // Same-timestamp samples cannot contribute a rate.
            if (age > 0L) oldest = index
            step++
        }

        val seconds = (newestTime - times[oldest]) / 1_000_000_000f
        if (seconds <= 0f) return PillVelocity(0f, 0f)
        return PillVelocity(
            x = (xs[newest] - xs[oldest]) / seconds,
            y = (ys[newest] - ys[oldest]) / seconds,
        )
    }
}

