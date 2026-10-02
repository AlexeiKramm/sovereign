package io.github.alexeikramm.sovereign.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The pinned-to-bottom decision, pure and unit-tested.
 *
 * Streaming text only ever appends, so "follow the newest line" is the right default. The one
 * interesting rule is when to stop: the moment the user scrolls away from the bottom, so they can
 * read back without being yanked down by the next word — and to resume once they return to the
 * bottom.
 */
object AutoScrollRules {

    /** Within this many dp of the bottom still counts as "at the bottom". */
    const val BOTTOM_SLOP_DP = 24f

    /**
     * How long the follow takes. Short enough to read as "almost a snap", long enough that the
     * eye can see the text move rather than teleport. Units arrive at speech pauses (roughly a
     * second or more apart), so the tween finishes before the next one starts and never has to be
     * interrupted mid-flight.
     */
    const val FOLLOW_DURATION_MS = 120

    /**
     * The pinned state after a scroll observation.
     *
     * @param currentlyPinned the state before this observation.
     * @param scrolling whether the viewport is being scrolled. Content growing is not a scroll, so
     *   it leaves the pin as it was.
     * @param value the current scroll offset.
     * @param maxValue the maximum scroll offset for the current content.
     * @param slopPx how close to [maxValue] counts as the bottom.
     */
    fun pinnedAfterScroll(
        currentlyPinned: Boolean,
        scrolling: Boolean,
        value: Int,
        maxValue: Int,
        slopPx: Int,
    ): Boolean = if (!scrolling) currentlyPinned else maxValue - value <= slopPx
}

/**
 * Keeps a scrolling transcript pinned to its newest line as [contentKey] changes, without fighting a
 * user who has scrolled back to read. Attach it next to the `ScrollState` of the scrollable content.
 *
 * On content change it waits one frame before scrolling: the new text has not been measured when the
 * effect first runs, so [ScrollState.maxValue] would still be the old bottom and the last line would
 * stay hidden.
 */
@Composable
fun rememberPinnedToBottom(state: ScrollState, contentKey: Any) {
    val slopPx = with(LocalDensity.current) { AutoScrollRules.BOTTOM_SLOP_DP.dp.roundToPx() }
    var pinned by remember { mutableStateOf(true) }

    LaunchedEffect(state, slopPx) {
        snapshotFlow { state.isScrollInProgress to state.value }
            .collect { (scrolling, value) ->
                pinned = AutoScrollRules.pinnedAfterScroll(
                    currentlyPinned = pinned,
                    scrolling = scrolling,
                    value = value,
                    maxValue = state.maxValue,
                    slopPx = slopPx,
                )
            }
    }

    LaunchedEffect(contentKey) {
        if (!pinned) return@LaunchedEffect
        withFrameNanos { }
        state.animateScrollTo(
            value = state.maxValue,
            animationSpec = tween(
                durationMillis = AutoScrollRules.FOLLOW_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        )
    }
}
