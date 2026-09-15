package fi.ak.smartvoice.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
    const val slowMs = 350

    /** Emphasised decelerate — the default for something entering the screen. */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Accelerate — for something leaving. */
    val exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}
