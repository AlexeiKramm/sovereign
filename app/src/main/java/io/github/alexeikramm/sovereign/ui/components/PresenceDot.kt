package io.github.alexeikramm.sovereign.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small presence indicator: one dot, drawn in [color], gently pulsing while [pulsing].
 *
 * Shared by the Server active dashboard's status chip and the Server settings client list, so a
 * "client is connected" reads identically in both places (D-130's one visual language).
 */
@Composable
fun PresenceDot(
    color: Color,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val transition = rememberInfiniteTransition(label = "presence")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(color = color.copy(alpha = if (pulsing) pulseAlpha else 0.45f))
            },
    )
}
