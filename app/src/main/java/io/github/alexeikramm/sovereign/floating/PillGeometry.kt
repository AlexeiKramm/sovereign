package io.github.alexeikramm.sovereign.floating

import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.theme.WAVEFORM_DARK_ARGB
import io.github.alexeikramm.sovereign.theme.WAVEFORM_LIGHT_ARGB
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
 * Static dimensions and color tokens for the minimized floating pill.
 *
 * Deliberately free of Android and Compose types so JVM unit tests can verify layout numbers
 * without an instrumented environment.
 */
object PillLayout {

    /** Pill container width in density-independent pixels. */
    const val WIDTH_DP = 40f

    /** Pill container height in density-independent pixels. */
    const val HEIGHT_DP = 60f

    /** Corner radius of the stadium (15dp squircle/stadium). */
    const val RADIUS_DP = 15f

    /** Border thickness around the stadium (2dp for both dark and light). */
    const val BORDER_WIDTH_DP = 2f

    /** Width of the mark (resting waveform / icon canvas) in dp. */
    const val MARK_WIDTH_DP = 24f

    /** Height of the mark's bounding box in dp. */
    const val MARK_BOX_HEIGHT_DP = 2.17f

    /** Peak-to-peak amplitude of the resting mark in dp. */
    const val MARK_PEAK_TO_PEAK_DP = 1.95f

    /** Peak amplitude (from centerline) of the resting mark in dp. */
    const val MARK_AMPLITUDE_DP = MARK_PEAK_TO_PEAK_DP / 2f

    /** Stroke width of the resting mark in dp. */
    const val MARK_STROKE_DP = 2f

    /** Half-height of the recording mark's bounding box in dp. */
    const val RECORDING_HALF_HEIGHT_DP = 4.44f

    /** Full height of the recording mark's bounding box in dp. */
    const val RECORDING_BOX_HEIGHT_DP = RECORDING_HALF_HEIGHT_DP * 2f

    /** Peak-to-peak amplitude at the silence floor (level 0) in dp. Equal to resting mark (1.95dp). */
    const val RECORDING_SILENCE_PEAK_TO_PEAK_DP = 2f * 0.22f * RECORDING_HALF_HEIGHT_DP

    /** Peak-to-peak amplitude at full voice (level 1) in dp (8.0dp). */
    const val RECORDING_FULL_VOICE_PEAK_TO_PEAK_DP = 2f * 0.90f * RECORDING_HALF_HEIGHT_DP

    /** Dark theme container fill color (#1A120D). */
    const val COLOR_FILL_DARK = 0xFF1A120DL

    /** Light theme container fill color (#FFFFFF). */
    const val COLOR_FILL_LIGHT = 0xFFFFFFFFL

    /** Dark theme border role color (#4D3627). */
    const val COLOR_BORDER_DARK = 0xFF4D3627L

    /** Light theme border role color (#CBCBCB). */
    const val COLOR_BORDER_LIGHT = 0xFFCBCBCBL

    /** Dark theme accent role color (#FF9248). */
    const val COLOR_ACCENT_DARK = 0xFFFF9248L

    /** Light theme accent role color (#EA580C). */
    const val COLOR_ACCENT_LIGHT = 0xFFEA580CL

    /** Dark theme waveform color at 85% accent (#DB7F40). */
    const val COLOR_WAVEFORM_DARK = WAVEFORM_DARK_ARGB

    /** Light theme waveform color at 85% accent (#D55A1A). */
    const val COLOR_WAVEFORM_LIGHT = WAVEFORM_LIGHT_ARGB

    /** Dark theme OK / success role color (#4ADE80). */
    const val COLOR_OK_DARK = 0xFF4ADE80L

    /** Light theme OK / success role color (#16A34A). */
    const val COLOR_OK_LIGHT = 0xFF16A34AL

    /** Dark theme BAD / failure role color (#FF6766). */
    const val COLOR_BAD_DARK = 0xFFFF6766L

    /** Light theme BAD / failure role color (#E5484D). */
    const val COLOR_BAD_LIGHT = 0xFFE5484DL
}

/**
 * Static dimensions and color tokens for the expanded dictation card.
 *
 * Deliberately free of Android and Compose types so JVM unit tests can verify layout numbers
 * without an instrumented environment.
 */
object CardLayout {

    /** Total width of the expanded card in dp. */
    const val WIDTH_DP = 340f

    /** Total height of the expanded card in dp. */
    const val HEIGHT_DP = 210f

    /** Corner radius of the card container in dp. */
    const val RADIUS_DP = 24f

    /** Inner padding of the card container in dp. */
    const val PADDING_DP = 16f

    /** Height of the header row in dp. */
    const val HEADER_HEIGHT_DP = 20f

    /** Horizontal offset of the header row in dp. */
    const val STATUS_OFFSET_X_DP = 0f

    /** Vertical offset of the header row in dp. */
    const val STATUS_OFFSET_Y_DP = 0f

    /** Status label font size in sp. */
    const val STATUS_FONT_SP = 15f

    /** Diameter of the status dot in dp. */
    const val STATUS_DOT_SIZE_DP = 8f

    /** Gap between status dot and status text in dp. */
    const val STATUS_GAP_DP = 7f

    /** Touch target size of the quick settings button in dp. */
    const val MORE_TOUCH_SIZE_DP = 28f

    /** Glyph size of the quick settings more_horiz icon in dp. */
    const val MORE_GLYPH_SIZE_DP = 20f

    /** Compensated vertical gap between header and transcript in dp (locked variant C). */
    const val GAP_TOP_DP = 8f

    /** Compensated vertical gap between transcript and action row in dp (locked variant C). */
    const val GAP_BOTTOM_DP = 14f

    /** Transcript font size in sp. */
    const val TRANSCRIPT_FONT_SP = 16f

    /** Transcript line height in sp. */
    const val TRANSCRIPT_LINE_HEIGHT_SP = 19f

    /** Total height of the transcript viewing area in dp (210 - 32 padding - 20 header - 8 gap - 14 gap - 36 row = 100dp). */
    const val TRANSCRIPT_HEIGHT_DP = 100f

    /** Height of the bottom action row in dp. */
    const val ACTION_ROW_HEIGHT_DP = 36f

    /** Diameter of the circular action buttons in dp. */
    const val ACTION_CIRCLE_SIZE_DP = 41f

    /** Glyph size inside the circular action buttons in dp. */
    const val ACTION_GLYPH_SIZE_DP = 26f

    /** Margin on each side of the middle capsule in dp. */
    const val CAPSULE_MARGIN_DP = 8f

    /** Outline border width of the middle capsule and outline action buttons in dp. */
    const val CAPSULE_OUTLINE_WIDTH_DP = 1f

    /** Width of the card waveform canvas in dp (44dp * 2.6). */
    const val WAVEFORM_WIDTH_DP = 114.4f

    /** Height of the card waveform canvas in dp. */
    const val WAVEFORM_HEIGHT_DP = 32f

    /** Stroke width of the card waveform in dp. */
    const val WAVEFORM_STROKE_DP = 1.6f

    /** Peak-to-peak amplitude of the card waveform at silence floor (level 0) in dp (7.04dp). */
    const val WAVEFORM_SILENCE_PEAK_TO_PEAK_DP = 2f * 0.22f * (WAVEFORM_HEIGHT_DP / 2f)

    /** Peak-to-peak amplitude of the card waveform at full voice (level 1) in dp (28.8dp). */
    const val WAVEFORM_FULL_VOICE_PEAK_TO_PEAK_DP = 2f * 0.90f * (WAVEFORM_HEIGHT_DP / 2f)

    /** Card fill color (Dark): #1A120D. */
    const val COLOR_FILL_DARK = 0xFF1A120DL

    /** Card fill color (Light): #FFFFFF. */
    const val COLOR_FILL_LIGHT = 0xFFFFFFFFL

    /** Neutral lines, control outlines, and secondary text (Dark): #BEBAB8. */
    const val COLOR_LINE_DARK = 0xFFBEBAB8L

    /** Neutral lines, control outlines, and secondary text (Light): #5C6368. */
    const val COLOR_LINE_LIGHT = 0xFF5C6368L

    /** Primary transcript text (Dark): #E9E6E4. */
    const val COLOR_TEXT_PRIMARY_DARK = 0xFFE9E6E4L

    /** Primary transcript text (Light): #2B2F31. */
    const val COLOR_TEXT_PRIMARY_LIGHT = 0xFF2B2F31L

    /** Secondary text (Dark): #BEBAB8. */
    const val COLOR_TEXT_SECONDARY_DARK = 0xFFBEBAB8L

    /** Secondary text (Light): #5C6368. */
    const val COLOR_TEXT_SECONDARY_LIGHT = 0xFF5C6368L

    /** Tertiary text (more_horiz glyph): #7F888E. */
    const val COLOR_TEXT_TERTIARY = 0xFF7F888EL

    /** Accent color (Dark): #FF9248. */
    const val COLOR_ACCENT_DARK = 0xFFFF9248L

    /** Accent color (Light): #EA580C. */
    const val COLOR_ACCENT_LIGHT = 0xFFEA580CL

    /** Accept glyph on filled button (Dark): #1A1207. */
    const val COLOR_ACCEPT_GLYPH_DARK = 0xFF1A1207L

    /** Accept glyph on filled button (Light): #FFFFFF. */
    const val COLOR_ACCEPT_GLYPH_LIGHT = 0xFFFFFFFFL

    /** Waveform color at 85% accent (Dark): #DB7F40. */
    const val COLOR_WAVEFORM_DARK = WAVEFORM_DARK_ARGB

    /** Waveform color at 85% accent (Light): #D55A1A. */
    const val COLOR_WAVEFORM_LIGHT = WAVEFORM_LIGHT_ARGB

    /** Ok / success status dot color (Dark): #4ADE80. */
    const val COLOR_OK_DARK = 0xFF4ADE80L

    /** Ok / success status dot color (Light): #16A34A. */
    const val COLOR_OK_LIGHT = 0xFF16A34AL

    /** Bad / failure reject button color (Dark): #FF6766. */
    const val COLOR_BAD_DARK = 0xFFFF6766L

    /** Bad / failure reject button color (Light): #E5484D. */
    const val COLOR_BAD_LIGHT = 0xFFE5484DL
}

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

    /**
     * Keeps the pill's edge off the very screen edge after a drag. Half the original 8dp (→ 4dp),
     * applied on both axes and to both the resting dock and the drag clamp.
     */
    const val DRAG_MARGIN_DP = 4f

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
     * through these. Raised to 96 so the recording wave's six lobes per side stay well resolved.
     */
    const val SEGMENTS = 96

    /** Amplitude of near-silence — the line should still look alive when the room is quiet. */
    const val MIN_AMPLITUDE = 0.22f

    /**
     * Amplitude at full speech. The strip is sized so the peaks can be tall without clipping; the
     * user wanted the peak-to-line-thickness ratio of their reference (~6:1), which needs vertical
     * room rather than a thinner line.
     */
    const val MAX_AMPLITUDE = 0.90f

    /**
     * Lobes (half-cycles) each side of the centre for the recording wave. Three lobes each side
     * as locked in the visual design spec (PLAN.md).
     */
    const val RECORDING_LOBES = 3f

    /**
     * Lobes each side for the resting mark. Two gives four humps across the width.
     */
    const val RESTING_LOBES = 2f

    /**
     * Constant angular speed of the recording wave in radians per second.
     * Equivalent to 1.2 crests/second (2 * PI * 1.2 ≈ 7.5398 rad/s).
     */
    const val OMEGA_RECORDING = 7.5398f

    /**
     * Constant angular speed of the ready wave in radians per second.
     * Equivalent to 0.44 crests/second (2 * PI * 0.44 ≈ 2.77 rad/s).
     */
    const val OMEGA_READY = 2.77f

    /**
     * Tapering window exponent: `cos(πu/2)` raised to this. Above 1 keeps zero slope at the edges (no
     * corner) while tapering gently enough that the outer lobes stay visible.
     */
    const val ENVELOPE_POWER = 1.5f

    /**
     * Peak height of the resting wave as a fraction of the mark box half-height (1.085dp).
     * Sized so peak amplitude is 0.975dp (1.95dp peak-to-peak).
     */
    const val RESTING_AMPLITUDE = PillLayout.MARK_AMPLITUDE_DP / (PillLayout.MARK_BOX_HEIGHT_DP / 2f)

    /** Maps a 0..1 audio level to waveform amplitude, as a fraction of the available half-height. */
    fun amplitudeFor(audioLevel: Float): Float {
        val level = audioLevel.coerceIn(0f, 1f)
        return MIN_AMPLITUDE + level * (MAX_AMPLITUDE - MIN_AMPLITUDE)
    }

    /**
     * [segments] + 1 vertical offsets in `-amplitude..amplitude` (negative is *up*).
     * The locked recording wave travels right at [OMEGA_RECORDING] (1.2 crests/s):
     * `y(u) = -amp * envelope(u) * sin(3 * PI * u - phase)`.
     */
    fun samples(phase: Float, amplitude: Float, segments: Int = SEGMENTS): FloatArray {
        require(segments > 0) { "segments must be positive" }
        val amp = amplitude.coerceIn(0f, 1f)
        val k = RECORDING_LOBES * PI_F
        val out = FloatArray(segments + 1)
        for (i in 0..segments) {
            val u = 2f * i / segments - 1f
            out[i] = -amp * envelope(u) * sin(k * u - phase)
        }
        return out
    }

    /**
     * Vertical offsets for the Ready wave across `u in [-1, 1]`.
     * Resting glyph drifting right at [OMEGA_READY] with flat envelope:
     * `y(u) = -RESTING_AMPLITUDE * sin(2 * PI * u - phase)`.
     */
    fun readySamples(phase: Float, segments: Int = SEGMENTS): FloatArray {
        require(segments > 0) { "segments must be positive" }
        val k = RESTING_LOBES * PI_F
        val out = FloatArray(segments + 1)
        for (i in 0..segments) {
            val u = 2f * i / segments - 1f
            out[i] = -RESTING_AMPLITUDE * sin(k * u - phase)
        }
        return out
    }

    /**
     * The resting mark's static shape: two lobes a side with a flat envelope across `u in [-1, 1]`.
     * `y(u) = -RESTING_AMPLITUDE * sin(2 * PI * u)`. Four equal humps, alternating sign, zero at
     * the centre and at both edges. Identical to [readySamples] at phase 0.
     */
    fun restingSamples(segments: Int = SEGMENTS): FloatArray = readySamples(phase = 0f, segments = segments)

    /**
     * Tapering window over `u in [-1, 1]`: `cos(πu/2)` to [ENVELOPE_POWER]. One at the centre, zero
     * at both edges and with zero slope there, so the line meets the flat edges without a corner.
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

