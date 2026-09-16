package fi.ak.smartvoice.floating

import fi.ak.smartvoice.data.AppSettings
import kotlin.math.PI
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
    const val MIN_AMPLITUDE = 0.18f

    /**
     * Amplitude at full speech. The user asked for the animated waveform to be "significantly"
     * taller and livelier (CHUNK-3B), so the peaks use most of the strip's height while staying
     * clear of its edges.
     */
    const val MAX_AMPLITUDE = 0.82f

    /** Spatial cycles across the recording strip: the ripple's base. Raised for a livelier wave. */
    const val RECORDING_CYCLES = 3.0f

    /** A faster, lighter second component, so the ripple never looks like one clean sine. */
    const val RECORDING_DETAIL_CYCLES = 5.5f

    /** How much the detail component contributes relative to the base. */
    private const val RECORDING_DETAIL_WEIGHT = 0.35f

    /**
     * The envelope both waveforms are shaped by: `sin(πt)` raised to this power. It concentrates
     * the wave into a spindle — flat and calm at the edges, tallest in the middle — which is the
     * shape the user asked for (CHUNK-3B). It is exactly zero at both ends, so the line stays
     * anchored to the card.
     */
    const val ENVELOPE_POWER = 4f

    /**
     * The resting pill's own shape: a clean sine of about two and a half cycles, drawn at a fixed
     * phase (it only breathes, it never travels). Deliberately **not** the recording surface: at
     * near-zero amplitude that drew an almost flat line that read as a rendering glitch on the
     * small pill.
     */
    const val RESTING_CYCLES = 2.5f

    /** Peak height of the resting wave, as a fraction of the available half-height. */
    const val RESTING_AMPLITUDE = 0.80f

    /** Maps a 0..1 audio level to waveform amplitude, as a fraction of the available half-height. */
    fun amplitudeFor(audioLevel: Float): Float {
        val level = audioLevel.coerceIn(0f, 1f)
        return MIN_AMPLITUDE + level * (MAX_AMPLITUDE - MIN_AMPLITUDE)
    }

    /**
     * [segments] + 1 vertical offsets in `-amplitude..amplitude`, where negative is *up* in
     * screen coordinates. Both ends taper to zero so the surface stays anchored to the card edge.
     */
    fun samples(phase: Float, amplitude: Float, segments: Int = SEGMENTS): FloatArray {
        require(segments > 0) { "segments must be positive" }
        val amp = amplitude.coerceIn(0f, 1f)
        val out = FloatArray(segments + 1)
        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val wave = sin(t * TWO_PI * RECORDING_CYCLES + phase) +
                sin(t * TWO_PI * RECORDING_DETAIL_CYCLES - phase * 1.37f) * RECORDING_DETAIL_WEIGHT
            out[i] = (wave / (1f + RECORDING_DETAIL_WEIGHT)) * envelope(t) * amp
        }
        return out
    }

    /**
     * The resting pill's static waveform, scaled to [[RESTING_AMPLITUDE]]. A sine over
     * [RESTING_CYCLES] cycles, shaped by the same spindle envelope as the recording surface, so the
     * middle crest is the tallest and the wave fades out towards the pill's edges.
     */
    fun restingSamples(segments: Int = SEGMENTS): FloatArray {
        require(segments > 0) { "segments must be positive" }
        val out = FloatArray(segments + 1)
        for (i in 0..segments) {
            val t = i.toFloat() / segments
            out[i] = sin(t * TWO_PI * RESTING_CYCLES) * envelope(t) * RESTING_AMPLITUDE
        }
        return out
    }

    /** `sin(πt)` to [ENVELOPE_POWER]: zero at both ends, one at the middle. */
    private fun envelope(t: Float): Float = sin(t * PI_F).pow(ENVELOPE_POWER)

    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val PI_F = PI.toFloat()
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

