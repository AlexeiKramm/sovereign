package io.github.alexeikramm.sovereign.ui.settings

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Basic screen's two timing selectors, as pure numbers.
 *
 * The brief asks for a 0.5 s step on both. Keeping the range, the snapping and the label
 * formatting here — rather than inline in the composables — means the requirement is pinned by a
 * unit test and the two sliders cannot quietly drift apart.
 */
object SettingsControls {

    /** The plan's step: every timing value is a whole or half second. */
    const val TIMING_STEP_SECONDS = 0.5f

    /** Sentence-pause sensitivity. 0.5 s is the shortest value that still reads as "a pause". */
    const val PAUSE_MIN_SECONDS = 0.5f
    const val PAUSE_MAX_SECONDS = 5.0f

    /** Silence that ends the recording. **0 means the mic never stops on its own.** */
    const val STOP_MIN_SECONDS = 0.0f
    const val STOP_MAX_SECONDS = 300.0f

    /** Discrete stops between the endpoints, in the shape Compose's `Slider(steps = ...)` wants. */
    val PAUSE_STEPS: Int = stepsFor(PAUSE_MIN_SECONDS, PAUSE_MAX_SECONDS)
    val STOP_STEPS: Int = stepsFor(STOP_MIN_SECONDS, STOP_MAX_SECONDS)

    /**
     * Stepper progression for the auto-stop silence setting.
     * Below 10 s, steps in 1 s increments (0 = Off, 1 s, 2 s, ... 10 s).
     * Above 10 s, steps in 5 s increments (10 s, 15 s, ... 300 s).
     */
    fun nextStopSeconds(current: Float): Float {
        if (current >= STOP_MAX_SECONDS) return STOP_MAX_SECONDS
        return if (current < 10f) {
            ((current + 1f).roundToInt().toFloat()).coerceIn(STOP_MIN_SECONDS, STOP_MAX_SECONDS)
        } else {
            val rem = current % 5f
            val next = if (rem == 0f) current + 5f else current + (5f - rem)
            next.coerceIn(STOP_MIN_SECONDS, STOP_MAX_SECONDS)
        }
    }

    fun prevStopSeconds(current: Float): Float {
        if (current <= STOP_MIN_SECONDS) return STOP_MIN_SECONDS
        return if (current <= 10f) {
            ((current - 1f).roundToInt().toFloat()).coerceIn(STOP_MIN_SECONDS, STOP_MAX_SECONDS)
        } else {
            val rem = current % 5f
            val prev = if (rem == 0f) current - 5f else current - rem
            prev.coerceIn(STOP_MIN_SECONDS, STOP_MAX_SECONDS)
        }
    }

    /**
     * Number of *interior* stops for a range that should land on [step] increments. A slider with
     * `steps = n` exposes `n + 2` values, so an inclusive `0.5..5.0` range at 0.5 s gives 8.
     */
    fun stepsFor(min: Float, max: Float, step: Float = TIMING_STEP_SECONDS): Int =
        ((max - min) / step).roundToInt() - 1

    /**
     * Clamps a stored value into the slider's range and snaps it onto a step. A value written by
     * the old free-text field (say `2.3 s`) must not render off-track now that the control is a
     * stepped slider.
     */
    fun snap(value: Float, min: Float, max: Float, step: Float = TIMING_STEP_SECONDS): Float {
        val clamped = value.coerceIn(min, max)
        val stepsFromMin = ((clamped - min) / step).roundToInt()
        return (min + stepsFromMin * step).coerceIn(min, max)
    }

    /**
     * How a timing reads next to its slider: `0` is "Off", otherwise `2 s` / `2.5 s`. Pinned to
     * `Locale.US` because the UI is English-only and a decimal comma would be a bug.
     */
    fun formatSeconds(value: Float): String {
        if (value <= 0f) return "Off"
        val whole = value == value.toInt().toFloat()
        return if (whole) {
            String.format(Locale.US, "%d s", value.toInt())
        } else {
            String.format(Locale.US, "%.1f s", value)
        }
    }
}
