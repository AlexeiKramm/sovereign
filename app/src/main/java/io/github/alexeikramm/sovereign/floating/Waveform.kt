package io.github.alexeikramm.sovereign.floating

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.exp

/**
 * The one water-surface renderer (server-active-screen CHUNK-4). Every wave the app draws — the
 * minimized pill's cold/ready mark, its recording wave, and the expanded card's waveform — goes
 * through [WaterSurfaceWave], so the geometry, the level-follower and the glow live in one place
 * instead of being copy-pasted per call site.
 *
 * The generator lives in [WaterSurface] (pure, JVM-tested); this file is the Compose side: the frame
 * clock, the shared level-follower, and the single [drawWaterSurface] paint routine.
 */

/** Which surface [WaterSurfaceWave] draws. */
enum class WaveMark {
    /** The static resting mark (two lobes a side), phase zero. No frame clock. */
    RESTING,

    /**
     * The resting mark, drifting right at [WaterSurface.OMEGA_READY] — the pill's "model is warm"
     * cue. Runs a frame clock (it is motion by design, not a battery defect).
     */
    READY,

    /** The travelling recording wave, height following the mic level. */
    RECORDING,
}

/**
 * Draws one water surface. [mark] picks the shape; [audioLevel] feeds the level (RECORDING only);
 * [strokeWidth], [glow] and [speedRadPerSecond] are the knobs the call sites vary.
 *
 * [audioLevel] and the animation states are read inside the `Canvas` draw lambda, so a frame
 * invalidates the draw pass only — the composition never runs per frame. That is what keeps the
 * ripple at display rate instead of recomposition rate.
 */
@Composable
fun WaterSurfaceWave(
    color: Color,
    modifier: Modifier = Modifier,
    audioLevel: () -> Float = { 0f },
    mark: WaveMark = WaveMark.RECORDING,
    strokeWidth: Dp = PillLayout.MARK_STROKE_DP.dp,
    glow: Boolean = false,
    speedRadPerSecond: Float = WaterSurface.OMEGA_RECORDING,
) {
    when (mark) {
        WaveMark.RESTING -> Canvas(modifier) {
            drawWaterSurface(
                values = WaterSurface.restingSamples(),
                color = color,
                strokeWidth = strokeWidth,
                glowAlpha = 0f,
            )
        }

        WaveMark.READY -> {
            val phase = rememberDriftPhase(speedRadPerSecond)
            Canvas(modifier) {
                drawWaterSurface(
                    values = WaterSurface.readySamples(phase = phase.floatValue),
                    color = color,
                    strokeWidth = strokeWidth,
                    glowAlpha = 0f,
                )
            }
        }

        WaveMark.RECORDING -> {
            val level = rememberWaveLevel(audioLevel, glow, speedRadPerSecond)
            Canvas(modifier) {
                // Read the states inside the draw lambda so a frame invalidates drawing only.
                val amplitude = if (glow) {
                    WaterSurface.amplitudeFor(level.fast * (1f - SWELL_MIX) + level.swell * SWELL_MIX)
                } else {
                    WaterSurface.amplitudeFor(level.fast)
                }
                drawWaterSurface(
                    values = WaterSurface.samples(phase = level.phase, amplitude = amplitude),
                    color = color,
                    strokeWidth = strokeWidth,
                    // The halo follows the slow swell, so it breathes rather than flickers.
                    glowAlpha = if (glow) level.swell * 0.9f else 0f,
                )
            }
        }
    }
}

/**
 * A phase that advances at a constant [speedRadPerSecond] — the frame clock behind the drifting ready
 * mark. Read only inside the `Canvas` draw lambda, so a frame invalidates drawing, not composition.
 */
@Composable
private fun rememberDriftPhase(speedRadPerSecond: Float): MutableFloatState {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(speedRadPerSecond) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos
            phase.floatValue = (phase.floatValue + dtSeconds * speedRadPerSecond) % TWO_PI
        }
    }
    return phase
}

/**
 * The smoothed level + phase a wave is drawn from. All three fields are read only inside the `Canvas`
 * draw lambda (a deferred read), so the frame loop invalidates drawing, not composition.
 */
private class WaveLevelState {
    var phase by mutableFloatStateOf(0f)
    var staged by mutableFloatStateOf(0f)
    var fast by mutableFloatStateOf(0f)
    var swell by mutableFloatStateOf(0f)
}

/**
 * Drives [WaveLevelState] on its own frame clock: a perceptual level through a two-pole follower,
 * an optional slow swell, and a constant-speed phase. Shared by every recording wave.
 */
@Composable
private fun rememberWaveLevel(
    audioLevel: () -> Float,
    glow: Boolean,
    speedRadPerSecond: Float,
): WaveLevelState {
    val state = remember { WaveLevelState() }
    val level = rememberUpdatedState(audioLevel)
    LaunchedEffect(glow, speedRadPerSecond) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos

            // Perceptual level (dB window), followed by two-pole filter (0.08s attack / 0.30s release).
            val target = VoiceLevel.perceptual(level.value())
            val tau = (if (target > state.staged) LEVEL_ATTACK_SECONDS else LEVEL_RELEASE_SECONDS) *
                TWO_POLE_CORRECTION
            val follow = 1f - exp(-dtSeconds / tau)
            state.staged += (target - state.staged) * follow
            state.fast += (state.staged - state.fast) * follow

            // ...plus a much slower follower the glow path mixes in, so the wave breathes.
            if (glow) {
                val swellFollow = 1f - exp(-dtSeconds / SWELL_SECONDS)
                state.swell += (state.fast - state.swell) * swellFollow
            }

            // Constant speed: the wave's tempo never reacts to the voice, only its height does.
            state.phase = (state.phase + dtSeconds * speedRadPerSecond) % TWO_PI
        }
    }
    return state
}

/**
 * Draws a water surface as one smooth cubic-bezier line through the sampled offsets, using
 * Catmull-Rom (Cardinal) control points so the joints have no visible corners. [values] are the
 * vertical offsets from the centre line, so the same drawing serves the travelling recording
 * surface and the resting pill's static sine.
 */
private fun DrawScope.drawWaterSurface(
    values: FloatArray,
    color: Color,
    strokeWidth: Dp = 2.5.dp,
    glowAlpha: Float = 0f,
) {
    val segments = values.size - 1
    val centreY = size.height / 2f
    val halfHeight = size.height / 2f
    val stepX = size.width / segments

    fun xAt(index: Int) = index * stepX
    fun yAt(index: Int) = centreY - values[index] * halfHeight

    val path = Path().apply {
        moveTo(xAt(0), yAt(0))
        for (i in 0 until segments) {
            val previous = values[(i - 1).coerceAtLeast(0)]
            val next = values[(i + 2).coerceAtMost(segments)]
            val control1Y = centreY - (values[i] + (values[i + 1] - previous) / 6f) * halfHeight
            val control2Y = centreY - (values[i + 1] - (next - values[i]) / 6f) * halfHeight
            cubicTo(
                xAt(i) + stepX / 3f,
                control1Y,
                xAt(i + 1) - stepX / 3f,
                control2Y,
                xAt(i + 1),
                yAt(i + 1),
            )
        }
    }

    // Soft additive halo behind the stroke: the "modern assistant glow" without a blur pass.
    if (glowAlpha > 0.01f) {
        for ((widthMultiple, alpha) in GLOW_PASSES) {
            drawPath(
                path = path,
                color = color.copy(alpha = (glowAlpha * alpha).coerceIn(0f, 1f)),
                style = Stroke(width = strokeWidth.toPx() * widthMultiple, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
    )
}

/**
 * Fast level follower: two cascaded one-poles (≈ critically damped, no overshoot) with a prompt
 * attack and a softer release. Each pole is faster than the overall target by [TWO_POLE_CORRECTION].
 */
private const val LEVEL_ATTACK_SECONDS = 0.08f
private const val LEVEL_RELEASE_SECONDS = 0.30f
private const val TWO_POLE_CORRECTION = 0.64f

/**
 * Slow "swell" follower mixed into the fast one. This is what gives the wave the organic,
 * breathing quality of a modern assistant glow rather than a VU needle: the level never simply
 * tracks the signal, it drifts.
 */
private const val SWELL_SECONDS = 1.8f
private const val SWELL_MIX = 0.25f

/** Additive glow passes behind the stroke, as (width multiple, alpha). Cheap stacked strokes. */
private val GLOW_PASSES = listOf(2.4f to 0.22f, 4.6f to 0.09f)

private const val TWO_PI = (2.0 * kotlin.math.PI).toFloat()
