package io.github.alexeikramm.sovereign.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
     * eye can see the text move rather than teleport. The streaming preview revises the text far
     * faster than this (device partial cadence: ~100 ms median), so this is deliberately one step
     * of a re-targeting loop, not a tween expected to finish before the next text change.
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

    /**
     * As [pinnedAfterScroll], but also told whether the in-flight scroll is **our own follow
     * animation** rather than the user's finger.
     *
     * `ScrollState.isScrollInProgress` is true for a programmatic `animateScrollTo` as well as for a
     * drag, and a follow that has to travel more than one line starts out far from the bottom. Fed
     * to the plain rule, that transient reading unpins the transcript; if the follow is then
     * preempted (a streaming partial arrives mid-tween), the pin is never restored and the follow
     * stops for the rest of the utterance. Our own motion must therefore never change the pin —
     * only a real user scroll can.
     */
    fun pinnedAfterScroll(
        currentlyPinned: Boolean,
        scrolling: Boolean,
        autoScrolling: Boolean,
        value: Int,
        maxValue: Int,
        slopPx: Int,
    ): Boolean = if (autoScrolling) {
        currentlyPinned
    } else {
        pinnedAfterScroll(currentlyPinned, scrolling, value, maxValue, slopPx)
    }
}

/**
 * Keeps a scrolling transcript pinned to its newest line as [contentKey] changes, without fighting a
 * user who has scrolled back to read. Attach it next to the `ScrollState` of the scrollable content.
 *
 * A follow takes one frame to start (the grown text is not measured when the effect first fires, so
 * [ScrollState.maxValue] would still be the old bottom) and then animates over a short tween. Because
 * a streaming recogniser can revise the text far faster than that tween runs, the follow is **one
 * long-lived driver** that keeps re-targeting the newest bottom rather than a tween that is cancelled
 * and restarted on every text change — the latter never finishes and the transcript falls behind.
 * The tween runs in a child coroutine, so a user drag (which preempts it) cancels only that child; the
 * driver survives and resumes when they return to the bottom.
 */
@Composable
fun rememberPinnedToBottom(state: ScrollState, contentKey: Any) {
    val slopPx = with(LocalDensity.current) { AutoScrollRules.BOTTOM_SLOP_DP.dp.roundToPx() }
    var pinned by remember { mutableStateOf(true) }
    // True while *our* follow animation is driving `state`. Snapshot state on purpose: the pin
    // observer reads it as part of each observation so a scroll is attributed to the follow or to the
    // user at the moment it happened, not when the observation is processed.
    var following by remember { mutableStateOf(false) }
    // `snapshotFlow` only re-runs its block when a **snapshot state** it read changes, and
    // `contentKey` is a plain parameter — so it is mirrored into state the driver can observe.
    val latestContentKey = rememberUpdatedState(contentKey)

    LaunchedEffect(state, slopPx) {
        snapshotFlow { Triple(state.isScrollInProgress, state.value, following) }
            .collect { (scrolling, value, autoScrolling) ->
                pinned = AutoScrollRules.pinnedAfterScroll(
                    currentlyPinned = pinned,
                    scrolling = scrolling,
                    autoScrolling = autoScrolling,
                    value = value,
                    maxValue = state.maxValue,
                    slopPx = slopPx,
                )
            }
    }

    LaunchedEffect(state) {
        snapshotFlow { latestContentKey.value to pinned }
            .collect { (_, isPinned) ->
                if (!isPinned) return@collect
                // Chase the newest bottom for as long as the text keeps growing: each tween covers
                // what existed when it started, and the loop re-reads `maxValue` for anything that
                // arrived while it ran. Lag is bounded by one tween instead of accumulating.
                while (pinned) {
                    withFrameNanos { }
                    val target = state.maxValue
                    if (state.value >= target) break
                    following = true
                    try {
                        coroutineScope {
                            launch {
                                state.animateScrollTo(
                                    value = target,
                                    animationSpec = tween(
                                        durationMillis = AutoScrollRules.FOLLOW_DURATION_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }.join()
                        }
                    } finally {
                        following = false
                    }
                }
            }
    }
}
