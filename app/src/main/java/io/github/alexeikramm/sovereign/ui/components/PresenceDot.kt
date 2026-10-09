package io.github.alexeikramm.sovereign.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small **static** presence indicator (D-132): a dot, drawn once, no animation.
 *
 * It was briefly a pulsing dot. That was a defect: a `rememberInfiniteTransition` redraws at the
 * display refresh rate for as long as the screen is visible, so any screen showing a connected
 * client rendered ~70 fps forever, keeping the phone warm and out of deep sleep. Presence is a
 * state, not an activity — it must not cost frames. Shared by the Server active dashboard's status
 * chip and the Server settings client list.
 */
@Composable
fun PresenceDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .drawBehind { drawCircle(color = color) },
    )
}
