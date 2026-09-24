package fi.ak.smartvoice.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared design tokens: the numbers that should be identical everywhere rather
 * than re-guessed per screen. Screens should reference these instead of
 * hard-coding durations, radii and gaps.
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Standard screen-edge inset. */
    val screen: Dp = 16.dp
}

object Radius {
    val sm: Dp = 12.dp
    val md: Dp = 16.dp

    /** The plan's standard card corner. */
    val lg: Dp = 24.dp
    val pill: Dp = 999.dp
}

/**
 * Motion tokens. The plan's rule: standard transitions land in the 200–300 ms
 * band. Anything slower feels sluggish, anything faster feels like a glitch.
 */
object Motion {
    const val fastMs = 150
    const val standardMs = 250

    /** Emphasised decelerate — the default for something entering the screen. */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Accelerate — for something leaving. */
    val exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

/**
 * Shared colour roles from the locked visual design specification (PLAN.md).
 * Role-based language: Accent, Ok, Bad, Neutral, and Waveform (at 85% accent).
 */
object ColorRoles {
    val AccentDark = Color(0xFFFF9248)
    val AccentLight = Color(0xFFEA580C)

    val AcceptGlyphDark = Color(0xFF1A1207)
    val AcceptGlyphLight = Color(0xFFFFFFFF)

    val WaveformDark = Color(0xFFDB7F40)
    val WaveformLight = Color(0xFFD55A1A)

    val OkDark = Color(0xFF4ADE80)
    val OkLight = Color(0xFF16A34A)

    val BadDark = Color(0xFFFF6766)
    val BadLight = Color(0xFFE5484D)

    val BorderDark = Color(0xFF191919)
    val BorderLight = Color(0xFFAFAFAF)

    val NeutralDark = Color(0xFFBEBAB8)
    val NeutralLight = Color(0xFF5C6368)

    val FillDark = Color(0xFF0F1416)
    val FillLight = Color(0xFFFFFFFF)

    val TextPrimaryDark = Color(0xFFE9E6E4)
    val TextPrimaryLight = Color(0xFF2B2F31)

    val TextSecondaryDark = Color(0xFFBEBAB8)
    val TextSecondaryLight = Color(0xFF5C6368)

    val TextTertiary = Color(0xFF7F888E)
}
