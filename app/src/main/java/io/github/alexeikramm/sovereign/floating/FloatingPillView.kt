package io.github.alexeikramm.sovereign.floating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeikramm.sovereign.data.AppThemeMode
import io.github.alexeikramm.sovereign.data.CleaningProfile
import io.github.alexeikramm.sovereign.theme.Motion
import io.github.alexeikramm.sovereign.theme.Radius
import io.github.alexeikramm.sovereign.theme.Spacing
import io.github.alexeikramm.sovereign.theme.SovereignTheme
import io.github.alexeikramm.sovereign.ui.rememberPinnedToBottom
import kotlin.math.exp
import kotlinx.coroutines.withTimeoutOrNull

enum class FloatingUiState {
    RESTING_PILL,

    /**
     * Hold-to-talk quick capture: the pill stays minimized (no card) while the user holds, through
     * the post-release flush/cleanup, and while the brief result is shown. Distinct from
     * [RESTING_PILL] so the overlay treats it as "session in progress" for visibility, without
     * pretending it is the expanded recording card.
     */
    QUICK_CAPTURE,
    RECORDING,
    REVIEW,
}

/**
 * The six visual states of the minimized pill, as locked in the visual design spec (PLAN.md):
 * - [COLD]: resting mark, static, neutral colour.
 * - [READY]: resting mark, drifting at 2.77 rad/s (0.44 crests/s), neutral colour.
 * - [RECORDING]: travelling wave (3 lobes tapered), height following voice level, 85% accent.
 * - [CLEANING]: three dots cycling at 420ms each, accent colour.
 * - [RESULT]: check mark (9dp half-size, 0.49dp stroke), ok colour, holds ~1.7s.
 * - [ERROR]: cross mark (9dp half-size, 0.49dp stroke), bad colour, holds until next attempt.
 */
enum class MinimizedPillPhase {
    COLD,
    READY,
    RECORDING,
    CLEANING,
    RESULT,
    ERROR,
}

/**
 * The recording wave's phase speed, in radians per second — locked at [WaterSurface.OMEGA_RECORDING]
 * (1.2 crests/second).
 */
private const val RIPPLE_RADIANS_PER_SECOND = WaterSurface.OMEGA_RECORDING

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

/**
 * Width of the expanded card (and of the settings view that replaces it). Internal rather than
 * private because the service needs it to compute the card's expansion geometry (`PillExpand`).
 */
internal val CARD_WIDTH = CardLayout.WIDTH_DP.dp

/**
 * Fixed height of the expanded card. The dictation view and the settings view are both exactly this
 * tall, so flipping between them never resizes the overlay window — resizing it is what made the pill
 * shake, because a `WRAP_CONTENT` overlay's new bounds and its Compose content are not applied on the
 * same frame. The transcript and the settings list scroll inside this fixed box.
 */
internal val CARD_HEIGHT = CardLayout.HEIGHT_DP.dp

/**
 * The transparent padding the overlay's `Box` adds around whichever content is showing. The window
 * is `WRAP_CONTENT`, so this is part of its size; the service subtracts it to get the card's own
 * rect for the expansion transform.
 */
internal val OVERLAY_INSET = 8.dp

@Composable
fun FloatingDictationOverlay(
    darkTheme: Boolean,
    colorScheme: ColorScheme? = null,
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    /**
     * A **provider**, not a value: the level changes on every audio frame, and reading it inside
     * the waveform's draw pass means a new frame invalidates drawing only — never a recomposition
     * of the whole card.
     */
    audioLevel: () -> Float,
    /** The card's growth geometry for the current expansion, computed by the service (`PillExpand`). */
    cardGrowth: PillGrowth,
    /**
     * The card's expansion fraction: `0f` pill-sized, `1f` the full card. A **provider**, read
     * inside the card's `graphicsLayer` — the per-frame change invalidates the layer, never the
     * composition.
     */
    cardExpandProgress: () -> Float,
    isProcessing: Boolean,
    statusNote: String?,
    /** Non-null when cleanup failed; the card switches to the raw-text fallback treatment. */
    errorMessage: String?,
    /** True once everything this configuration needs is resident, which is what the resting pill's
     * breathing signals: ASR loaded, and either the cleanup model loaded or cleanup disabled. */
    modelReady: Boolean,
    profiles: List<CleaningProfile>,
    activeProfileId: String,
    cleanupEnabled: Boolean,
    /** True while the card shows its settings side (profiles + quick settings) instead of the text. */
    settingsOpen: Boolean,
    /** The word the user long-pressed in the review transcript, highlighted in the card. */
    wordSelection: WordSelection?,
    /** Non-null while a word action's input form is open (e.g. "Add to dictionary"). */
    wordForm: WordFormState?,
    onPillClicked: () -> Unit,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocessFullText: () -> Unit,
    onToggleSettings: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onToggleCleanup: () -> Unit,
    onWordSelected: (WordSelection) -> Unit,
    onWordAction: (WordAction) -> Unit,
    onWordFormSubmit: (String) -> Unit,
    onWordDismiss: () -> Unit,
    /**
     * Whether hold-to-talk is enabled. A **provider**, not a value: the setting is changed from
     * the app's Settings screen, and the overlay is a long-lived window that does not recompose
     * when a preference changes. Reading it at gesture time makes the switch take effect on the
     * next press without restarting the pill.
     */
    quickCaptureEnabled: () -> Boolean,
    /** The brief result shown on the minimized pill after a quick capture, or null. */
    quickStatus: String?,
    /** True when [quickStatus] is a failure, which tints it with the error palette. */
    quickStatusIsError: Boolean,
    onQuickCaptureStart: () -> Unit,
    onQuickCaptureEnd: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    /**
     * Whether this window renders the expanded card. The card now has its **own window** (the pill
     * window and the card window are both fixed-size, so neither ever resizes mid-motion); the pill
     * window disables this, the card window disables [hostsPill]. See
     * `docs/work/archive/card-expand-animation/`.
     */
    hostsCard: Boolean = true,
    /** Whether this window renders the minimized pill. The mirror of [hostsCard]. */
    hostsPill: Boolean = true,
    /** Debug override for testing/inspecting the minimized pill's six states. */
    debugPillPhase: MinimizedPillPhase? = null,
    /** True while the card is collapsing: the container fades out over the last third so the pill
     * window already visible underneath is revealed, instead of an empty shrinking rectangle. */
    cardClosing: Boolean = false,
    /**
     * True while a card session owns the screen — the pill window is only on screen as the backdrop
     * of a card that is opening or closing. The pill must then be drawn in its **resting** look:
     * the session's `RECORDING`/`REVIEW` state would otherwise give it the lighter listening face
     * and a flat waveform, so the same pill changed appearance at the end of the collapse.
     */
    pillBackdrop: Boolean = false,
) {
    // The overlay lives in a service window, so it does not inherit the Activity's theme: the
    // service resolves the app's theme mode (falling back to the system) and passes it in.
    SovereignTheme(
        themeMode = if (darkTheme) AppThemeMode.DARK else AppThemeMode.LIGHT,
        colorScheme = colorScheme,
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(OVERLAY_INSET),
            contentAlignment = Alignment.BottomEnd,
        ) {
            if (hostsCard && (uiState == FloatingUiState.RECORDING || uiState == FloatingUiState.REVIEW)) {
                Box(
                    modifier = Modifier
                        // The exit fade. The card window is a fixed card-sized window, so the card is
                        // hidden by *this* fade plus the scale, never by a window resize.
                        .graphicsLayer {
                            alpha = if (cardClosing) {
                                (cardExpandProgress() / CARD_CLOSE_FADE).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                        },
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    ExpandedCardView(
                        darkTheme = darkTheme,
                        uiState = uiState,
                        previewText = previewText,
                        rawText = rawText,
                        audioLevel = audioLevel,
                        cardGrowth = cardGrowth,
                        cardExpandProgress = cardExpandProgress,
                        isProcessing = isProcessing,
                        statusNote = statusNote,
                        errorMessage = errorMessage,
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        cleanupEnabled = cleanupEnabled,
                        settingsOpen = settingsOpen,
                        wordSelection = wordSelection,
                        wordForm = wordForm,
                        onCancel = onCancel,
                        onToggleRecording = onToggleRecording,
                        onCommit = onCommit,
                        onReprocess = onReprocessFullText,
                        onToggleSettings = onToggleSettings,
                        onSelectProfile = onSelectProfile,
                        onToggleCleanup = onToggleCleanup,
                        onWordSelected = onWordSelected,
                        onWordAction = onWordAction,
                        onWordFormSubmit = onWordFormSubmit,
                        onWordDismiss = onWordDismiss,
                    )
                }
            }
            // The pill window always renders the pill: whether it is *on screen* is the service's
            // window-visibility decision, not a state of this composable. That is what removes the
            // blink at the start of a card session — the pill is still drawn while the card's first
            // frame composes, and vanishes only when the card is on screen.
            if (hostsPill) {
                val effectivePhase = debugPillPhase ?: when {
                    // A backdrop pill is the resting pill, whatever the session is doing.
                    pillBackdrop -> if (modelReady) MinimizedPillPhase.READY else MinimizedPillPhase.COLD
                    uiState == FloatingUiState.RESTING_PILL ->
                        if (modelReady) MinimizedPillPhase.READY else MinimizedPillPhase.COLD
                    uiState == FloatingUiState.QUICK_CAPTURE && quickStatus != null ->
                        if (quickStatusIsError) MinimizedPillPhase.ERROR else MinimizedPillPhase.RESULT
                    isProcessing -> MinimizedPillPhase.CLEANING
                    else -> MinimizedPillPhase.RECORDING
                }
                MinimizedPillView(
                    phase = effectivePhase,
                    modelReady = modelReady,
                    audioLevel = audioLevel,
                    statusText = quickStatus,
                    statusIsError = quickStatusIsError,
                    quickCaptureEnabled = quickCaptureEnabled,
                    darkTheme = darkTheme,
                    onTap = onPillClicked,
                    onQuickCaptureStart = onQuickCaptureStart,
                    onQuickCaptureEnd = onQuickCaptureEnd,
                    onDragStart = onDragStart,
                    onDragBy = onDragBy,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
        }
    }
}

/** Fraction of the collapse over which the card container fades out (the rest is pure shrink). */
private const val CARD_CLOSE_FADE = 0.35f

/**
 * The minimized pill: a portrait stadium (`35 × 55.65dp`) displaying a minimalist mark inside.
 * At rest it displays a two-lobe flat resting mark. During quick capture the same
 * box becomes a live listening surface, then a processing spinner, then a one-line result.
 *
 * All four phases are one composable on purpose — see [MinimizedPillPhase]. Nothing here changes
 * the window's height, so the pill never resizes during a hold.
 */
@Composable
fun MinimizedPillView(
    phase: MinimizedPillPhase,
    modelReady: Boolean,
    audioLevel: () -> Float,
    statusText: String?,
    statusIsError: Boolean,
    quickCaptureEnabled: () -> Boolean,
    onTap: () -> Unit,
    onQuickCaptureStart: () -> Unit,
    onQuickCaptureEnd: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val container = cs.surfaceContainer
    val borderColor = cs.outlineVariant

    Surface(
        modifier = Modifier
            .size(width = PillLayout.WIDTH_DP.dp, height = PillLayout.HEIGHT_DP.dp)
            .clip(RoundedCornerShape(PillLayout.RADIUS_DP.dp))
            .border(
                width = PillLayout.BORDER_WIDTH_DP.dp,
                color = borderColor,
                shape = RoundedCornerShape(PillLayout.RADIUS_DP.dp),
            )
            .semantics { onClick(label = "Dictate") { onTap(); true } }
            .minimizedPillGestures(
                quickCaptureEnabled = quickCaptureEnabled,
                slopPx = PillGestureRules.slopPx(density),
                onTap = onTap,
                onQuickCaptureStart = onQuickCaptureStart,
                onQuickCaptureEnd = onQuickCaptureEnd,
                onDragStart = onDragStart,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
            ),
        color = container,
        contentColor = borderColor,
    ) {
        val waveColor = cs.primary

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                MinimizedPillPhase.COLD -> {
                    Canvas(
                        modifier = Modifier.size(
                            width = PillLayout.MARK_WIDTH_DP.dp,
                            height = PillLayout.MARK_BOX_HEIGHT_DP.dp,
                        ),
                    ) {
                        drawWaterSurface(
                            values = WaterSurface.restingSamples(),
                            color = waveColor,
                            strokeWidth = PillLayout.MARK_STROKE_DP.dp,
                        )
                    }
                }

                MinimizedPillPhase.READY -> {
                    ReadyWave(
                        color = waveColor,
                        modifier = Modifier.size(
                            width = PillLayout.MARK_WIDTH_DP.dp,
                            height = PillLayout.MARK_BOX_HEIGHT_DP.dp,
                        ),
                    )
                }

                MinimizedPillPhase.RECORDING -> {
                    RecordingWave(
                        audioLevel = audioLevel,
                        color = waveColor,
                        modifier = Modifier.size(
                            width = PillLayout.MARK_WIDTH_DP.dp,
                            height = PillLayout.RECORDING_BOX_HEIGHT_DP.dp,
                        ),
                    )
                }

                MinimizedPillPhase.CLEANING -> {
                    CleaningDots(
                        color = cs.primary,
                        modifier = Modifier.size(
                            width = PillLayout.MARK_WIDTH_DP.dp,
                            height = 18.dp,
                        ),
                    )
                }

                MinimizedPillPhase.RESULT -> {
                    PillCheckMark(
                        color = cs.primary,
                        modifier = Modifier.size(
                            width = PillLayout.MARK_WIDTH_DP.dp,
                            height = 18.dp,
                        ),
                    )
                }

                MinimizedPillPhase.ERROR -> {
                    PillCrossMark(
                        color = cs.error,
                        modifier = Modifier.size(
                            width = PillLayout.MARK_WIDTH_DP.dp,
                            height = 18.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyWave(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phase = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos
            phase.floatValue = (phase.floatValue + dtSeconds * WaterSurface.OMEGA_READY) % TWO_PI
        }
    }

    Canvas(modifier) {
        drawWaterSurface(
            values = WaterSurface.readySamples(phase = phase.floatValue),
            color = color,
            strokeWidth = PillLayout.MARK_STROKE_DP.dp,
        )
    }
}

@Composable
private fun RecordingWave(
    audioLevel: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phase = remember { mutableFloatStateOf(0f) }
    val stagedLevel = remember { mutableFloatStateOf(0f) }
    val fastLevel = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos

            // Perceptual level (dB window), followed by two-pole filter (0.08s attack / 0.30s release)
            val target = VoiceLevel.perceptual(audioLevel())
            val tau = (if (target > stagedLevel.floatValue) LEVEL_ATTACK_SECONDS else LEVEL_RELEASE_SECONDS) *
                TWO_POLE_CORRECTION
            val follow = 1f - exp(-dtSeconds / tau)
            stagedLevel.floatValue += (target - stagedLevel.floatValue) * follow
            fastLevel.floatValue += (stagedLevel.floatValue - fastLevel.floatValue) * follow

            // Constant phase speed 7.5398 rad/s (1.2 crests/s)
            phase.floatValue = (phase.floatValue + dtSeconds * WaterSurface.OMEGA_RECORDING) % TWO_PI
        }
    }

    Canvas(modifier) {
        drawWaterSurface(
            values = WaterSurface.samples(
                phase = phase.floatValue,
                amplitude = WaterSurface.amplitudeFor(fastLevel.floatValue),
            ),
            color = color,
            strokeWidth = PillLayout.MARK_STROKE_DP.dp,
            glowAlpha = 0f,
        )
    }
}

@Composable
private fun CleaningDots(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val elapsedSeconds = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos
            elapsedSeconds.floatValue += dtSeconds
        }
    }

    Canvas(modifier) {
        val activeDot = ((elapsedSeconds.floatValue / 0.420f).toInt()) % 3
        val cx = size.width / 2f
        val cy = size.height / 2f
        val gapPx = 5.dp.toPx()
        val baseRadiusPx = 1.15.dp.toPx()

        for (i in 0 until 3) {
            val x = cx + (i - 1) * gapPx
            val isActive = i == activeDot
            val radius = if (isActive) baseRadiusPx * 1.5f else baseRadiusPx
            val alpha = if (isActive) 1f else 0.45f

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(x, cy),
            )
        }
    }
}

@Composable
private fun PillCheckMark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = 9.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path().apply {
            moveTo(cx - s * 0.55f, cy + s * 0.05f)
            lineTo(cx - s * 0.12f, cy + s * 0.50f)
            lineTo(cx + s * 0.62f, cy - s * 0.50f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = PillLayout.MARK_STROKE_DP.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun PillCrossMark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = 9.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val strokePx = PillLayout.MARK_STROKE_DP.dp.toPx()
        drawLine(
            color = color,
            start = Offset(cx - s * 0.45f, cy - s * 0.45f),
            end = Offset(cx + s * 0.45f, cy + s * 0.45f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(cx + s * 0.45f, cy - s * 0.45f),
            end = Offset(cx - s * 0.45f, cy + s * 0.45f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The resting pill's single input handler. The three gestures all start with the same finger-down,
 * so they cannot be separate modifiers; this arbitrates them by time and movement and hands the
 * result to [PillGestureRules] for the actual decision.
 *
 * While the finger is down inside the slop circle we race the hold threshold against movement:
 *  - release first → [PillGesture.TAP],
 *  - movement first → [PillGesture.DRAG] (then deltas are streamed to the service),
 *  - timeout → [PillGesture.QUICK_CAPTURE] (then we wait for the release that ends it).
 *
 * Once quick capture is armed the finger's drift is ignored — a finger held on a small target
 * always drifts, and cancelling a sentence because of it would be maddening.
 */
private fun Modifier.minimizedPillGestures(
    quickCaptureEnabled: () -> Boolean,
    slopPx: Float,
    onTap: () -> Unit,
    onQuickCaptureStart: () -> Unit,
    onQuickCaptureEnd: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(slopPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var movedBeyondSlop = false
        var lastPosition = down.position

        // The block returns as soon as the press is released or leaves the slop circle; if neither
        // happened, the timeout fires and `withTimeoutOrNull` yields null — that null *is* the hold.
        var resolved = false
        val heldPastThreshold = withTimeoutOrNull(PillGestureRules.HOLD_THRESHOLD_MS) {
            while (!resolved) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    resolved = true
                } else {
                    val position = change.position
                    if ((position - down.position).getDistance() > slopPx) {
                        movedBeyondSlop = true
                        resolved = true
                    }
                    lastPosition = position
                }
            }
        } == null

        when (PillGestureRules.classify(movedBeyondSlop, heldPastThreshold && quickCaptureEnabled())) {
            PillGesture.DRAG -> {
                onDragStart()
                if (dragLoop(down.id, lastPosition, onDragBy)) onDragEnd() else onDragCancel()
            }

            PillGesture.QUICK_CAPTURE -> {
                onQuickCaptureStart()
                awaitRelease(down.id)
                onQuickCaptureEnd()
            }

            PillGesture.TAP -> onTap()
        }
    }
}

/** Streams drag deltas (in window coordinates) until release; false when the gesture was cancelled. */
private suspend fun AwaitPointerEventScope.dragLoop(
    pointerId: PointerId,
    startPosition: Offset,
    onDragBy: (Float, Float) -> Unit,
): Boolean {
    var last = startPosition
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (!change.pressed) {
            change.consume()
            return true
        }
        val delta = change.position - last
        last = change.position
        change.consume()
        onDragBy(delta.x, delta.y)
    }
}

/** Waits for [pointerId] to lift, consuming its events; used to end an armed quick capture. */
private suspend fun AwaitPointerEventScope.awaitRelease(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/**
 * The expanded card: a **fixed-size two-sided view**. The dictation side (transcript + actions) and
 * the settings side (profiles + quick settings) are both exactly `CARD_WIDTH × CARD_HEIGHT`, so
 * flipping between them never resizes the overlay window.
 */
@Composable
fun ExpandedCardView(
    darkTheme: Boolean = isSystemInDarkTheme(),
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    audioLevel: () -> Float,
    cardGrowth: PillGrowth,
    cardExpandProgress: () -> Float,
    isProcessing: Boolean,
    statusNote: String?,
    errorMessage: String?,
    profiles: List<CleaningProfile>,
    activeProfileId: String,
    cleanupEnabled: Boolean,
    settingsOpen: Boolean,
    wordSelection: WordSelection?,
    wordForm: WordFormState?,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocess: () -> Unit,
    onToggleSettings: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onToggleCleanup: () -> Unit,
    onWordSelected: (WordSelection) -> Unit,
    onWordAction: (WordAction) -> Unit,
    onWordFormSubmit: (String) -> Unit,
    onWordDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val cardFill = cs.surfaceContainer
    val cardText = cs.onSurface

    Surface(
        modifier = Modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            // The container transform: the card is drawn at its final size but scaled from the
            // pill's rect to 1 about the pinned corner. Read inside the layer so a per-frame change
            // costs a layer update, never a recomposition (see `PillExpand`).
            .graphicsLayer {
                val progress = cardExpandProgress()
                val (sx, sy) = cardGrowth.scaleAt(progress)
                scaleX = sx
                scaleY = sy
                transformOrigin = TransformOrigin(cardGrowth.originX, cardGrowth.originY)
            }
            .clip(RoundedCornerShape(CardLayout.RADIUS_DP.dp)),
        color = cardFill,
        contentColor = cardText,
    ) {
        AnimatedContent(
            targetState = settingsOpen,
            // Direction is chosen from *where we are going*, not from the previous animation, so a
            // rapid double-tap cannot flip it: opening always slides the settings in from the right
            // (card turns right→left) and returning always slides the dictation in from the left.
            transitionSpec = {
                val incomingFromRight = targetState
                val incoming = fadeIn(tween(Motion.fastMs)) +
                    slideInHorizontally { if (incomingFromRight) it / 5 else -it / 5 }
                val outgoing = fadeOut(tween(Motion.fastMs)) +
                    slideOutHorizontally { if (incomingFromRight) -it / 5 else it / 5 }
                incoming togetherWith outgoing
            },
            modifier = Modifier
                .fillMaxSize()
                // The content fades in as the container grows: the container (the Surface) scales
                // from the pill, but the text must not be seen stretched at full opacity, or the
                // eye reads the far corner as the origin. Opacity is a pure draw read.
                .graphicsLayer { alpha = cardExpandProgress().coerceIn(0f, 1f) },
            label = "pill-card-view",
        ) { showSettings ->
            if (showSettings) {
                SettingsPanel(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    cleanupEnabled = cleanupEnabled,
                    onSelectProfile = onSelectProfile,
                    onToggleCleanup = onToggleCleanup,
                    onClose = onToggleSettings,
                )
            } else {
                DictationCard(
                    darkTheme = darkTheme,
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    audioLevel = audioLevel,
                    isProcessing = isProcessing,
                    statusNote = statusNote,
                    errorMessage = errorMessage,
                    wordSelection = wordSelection,
                    wordForm = wordForm,
                    onCancel = onCancel,
                    onToggleRecording = onToggleRecording,
                    onCommit = onCommit,
                    onReprocess = onReprocess,
                    onOpenSettings = onToggleSettings,
                    onWordSelected = onWordSelected,
                    onWordAction = onWordAction,
                    onWordFormSubmit = onWordFormSubmit,
                    onWordDismiss = onWordDismiss,
                )
            }
        }
    }
}

/** The dictation side of the card: transcript, status, and the ✕ · waveform · refresh · ✓ row. */
@Composable
private fun DictationCard(
    darkTheme: Boolean,
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    audioLevel: () -> Float,
    isProcessing: Boolean,
    statusNote: String?,
    errorMessage: String?,
    wordSelection: WordSelection?,
    wordForm: WordFormState?,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocess: () -> Unit,
    onOpenSettings: () -> Unit,
    onWordSelected: (WordSelection) -> Unit,
    onWordAction: (WordAction) -> Unit,
    onWordFormSubmit: (String) -> Unit,
    onWordDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val failed = errorMessage != null
    // The fallback: on an LLM failure we show what was actually heard so no words are lost.
    val displayText = if (failed) rawText else previewText
    // Dictation only appends, so keep the newest line visible unless the user has scrolled up.
    rememberPinnedToBottom(scrollState, displayText)
    // Long-press-to-fix is offered only on the settled cleaned text: while recording the preview
    // streams and rewrites, so the word under the finger can move, and the raw fallback is not the
    // text this feature trains the dictionary against.
    val canFixWords = uiState == FloatingUiState.REVIEW && !failed && !isProcessing &&
        displayText.isNotBlank()
    val transcriptLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val cs = MaterialTheme.colorScheme
    val accentColor = cs.primary
    val transcript = remember(displayText, wordSelection, accentColor) {
        highlightedTranscript(displayText, wordSelection, accentColor)
    }

    Column(modifier = Modifier.fillMaxSize().padding(CardLayout.PADDING_DP.dp)) {
        // Header row: status dot 8dp + gap 7dp + status text, more_horiz 20dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CardLayout.HEADER_HEIGHT_DP.dp)
                .offset(x = CardLayout.STATUS_OFFSET_X_DP.dp, y = CardLayout.STATUS_OFFSET_Y_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                val dotColor = if (failed) cs.error else cs.primary
                Box(
                    modifier = Modifier
                        .size(CardLayout.STATUS_DOT_SIZE_DP.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(modifier = Modifier.width(CardLayout.STATUS_GAP_DP.dp))
                val statusTitle = when {
                    failed -> "Error"
                    isProcessing -> "Polishing…"
                    uiState == FloatingUiState.RECORDING -> "Listening…"
                    displayText.isNotBlank() -> "Review"
                    else -> "Tap to speak"
                }
                Text(
                    text = statusTitle,
                    color = cs.onSurfaceVariant,
                    fontSize = CardLayout.STATUS_FONT_SP.sp,
                    lineHeight = CardLayout.HEADER_HEIGHT_DP.sp,
                )
            }
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = "Quick settings",
                tint = cs.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(CardLayout.MORE_GLYPH_SIZE_DP.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings),
            )
        }

        Spacer(modifier = Modifier.height(CardLayout.GAP_TOP_DP.dp))

        if (wordForm != null) {
            // A word action's form takes the whole body: a field and its buttons do not fit beside
            // the transcript in a 210 dp card, and the transcript is not what the user is reading
            // while they type the fix. Tapping the form's background — anything that is not a field
            // or a button — cancels it, the same click-away the action bar uses.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                WordDismissCatcher(onDismiss = onWordDismiss)
                WordFixForm(
                    form = wordForm,
                    onSubmit = onWordFormSubmit,
                    onCancel = onWordDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // Live preview — cleaned text only, unless cleanup failed. Fills the fixed-height card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
            ) {
                // The scroller is a child of this Box so the action bar can be pinned to the
                // transcript's bottom edge instead of scrolling away with the text.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    if (displayText.isNotBlank()) {
                        Text(
                            text = transcript,
                            color = if (darkTheme) Color(CardLayout.COLOR_TEXT_PRIMARY_DARK) else Color(CardLayout.COLOR_TEXT_PRIMARY_LIGHT),
                            fontSize = CardLayout.TRANSCRIPT_FONT_SP.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = CardLayout.TRANSCRIPT_LINE_HEIGHT_SP.sp,
                            onTextLayout = { transcriptLayout.value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (canFixWords) {
                                        Modifier.pointerInput(displayText) {
                                            detectTapGestures(onLongPress = { position ->
                                                val offset = transcriptLayout.value
                                                    ?.getOffsetForPosition(position)
                                                    ?: return@detectTapGestures
                                                WordFix.wordAt(displayText, offset)?.let(onWordSelected)
                                            })
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }

                if (wordSelection != null) {
                    // A tap anywhere but the bar itself dismisses the actions and returns to review.
                    // The catcher is drawn before the bar, so Compose's last-placed-first hit testing
                    // keeps the bar's buttons on top while the rest of the body swallows the tap.
                    WordDismissCatcher(onDismiss = onWordDismiss)
                    WordActionBar(
                        selection = wordSelection,
                        onAction = onWordAction,
                        onDismiss = onWordDismiss,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                    )
                }
            }

            if (failed) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (darkTheme) Color(CardLayout.COLOR_BAD_DARK) else Color(CardLayout.COLOR_BAD_LIGHT),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = cs.error,
                        fontSize = 12.sp,
                    )
                }
            }

            if (statusNote != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = statusNote,
                    color = cs.primary,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(CardLayout.GAP_BOTTOM_DP.dp))

            // Action row: cancel · waveform/state · reprocess · commit.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CardLayout.ACTION_ROW_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Reject (cancel)
                CircleAction(
                    icon = Icons.Default.Close,
                    description = "Cancel dictation",
                    filled = false,
                    color = cs.error,
                    onClick = onCancel,
                )

                // Middle capsule
                Surface(
                    modifier = Modifier
                        .height(CardLayout.ACTION_ROW_HEIGHT_DP.dp)
                        .weight(1f)
                        .padding(horizontal = CardLayout.CAPSULE_MARGIN_DP.dp)
                        .clip(CircleShape)
                        .border(
                            width = CardLayout.CAPSULE_OUTLINE_WIDTH_DP.dp,
                            color = cs.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable(enabled = !isProcessing, onClick = onToggleRecording),
                    color = Color.Transparent,
                    contentColor = cs.outline,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isProcessing -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = cs.primary,
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.sm))
                                    Text(
                                        text = "Polishing…",
                                        color = cs.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            uiState == FloatingUiState.RECORDING -> {
                                CardWaveform(
                                    audioLevel = audioLevel,
                                    color = cs.primary,
                                    modifier = Modifier.size(
                                        width = CardLayout.WAVEFORM_WIDTH_DP.dp,
                                        height = CardLayout.WAVEFORM_HEIGHT_DP.dp,
                                    ),
                                )
                            }

                            else -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = cs.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    Text(
                                        text = "Speak again",
                                        color = cs.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                // Refresh (reprocess)
                val refreshColor = cs.onSurfaceVariant
                CircleAction(
                    icon = Icons.Default.Refresh,
                    description = "Reprocess full text with AI",
                    filled = false,
                    color = if (!isProcessing && rawText.isNotBlank()) refreshColor else refreshColor.copy(alpha = 0.4f),
                    enabled = !isProcessing && rawText.isNotBlank(),
                    onClick = onReprocess,
                )

                Spacer(modifier = Modifier.width(CardLayout.CAPSULE_MARGIN_DP.dp))

                // Accept (commit)
                CircleAction(
                    icon = Icons.Default.Check,
                    description = "Insert text",
                    filled = true,
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                    enabled = previewText.isNotBlank() || rawText.isNotBlank(),
                    onClick = onCommit,
                )
            }
        }
    }
}

/**
 * The transcript as an [AnnotatedString], with the long-pressed word tinted so the user can see
 * which word the action bar refers to.
 */
private fun highlightedTranscript(
    text: String,
    selection: WordSelection?,
    highlight: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (selection != null && selection.start >= 0 && selection.end <= text.length &&
        selection.start < selection.end
    ) {
        addStyle(SpanStyle(background = highlight.copy(alpha = 0.28f)), selection.start, selection.end)
    }
}

/**
 * A transparent layer that turns any tap reaching it into a dismissal. Placed *before* the thing it
 * must not steal taps from (the action bar, the form's fields), so Compose's last-placed-first hit
 * testing keeps those interactive and everything else behind it dismisses.
 */
@Composable
private fun WordDismissCatcher(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    )
}

/**
 * The bar that appears over the transcript while a word is selected: the word itself, one button per
 * [WordAction], and a way out. The actions are laid out from the catalogue, so adding one needs no
 * change here; the row scrolls sideways so a long word plus several actions cannot clip.
 */
@Composable
private fun WordActionBar(
    selection: WordSelection,
    onAction: (WordAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u201C${selection.word}\u201D",
                color = scheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 92.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WordAction.entries.forEach { action ->
                    TextButton(
                        onClick = { onAction(action) },
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) {
                        Icon(
                            imageVector = wordActionIcon(action),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = action.label, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel word fix",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

/** The icon shown beside each action in the word bar. */
private fun wordActionIcon(action: WordAction): ImageVector = when (action) {
    WordAction.ADD_TO_DICTIONARY -> Icons.Default.BookmarkAdd
}

/**
 * The input form a word action opens, pre-filled with the pressed word so the user edits it into
 * what it should be. The window is made focusable by the service for the form's lifetime, which is
 * what lets the keyboard reach this field (see `FloatingOverlayService.setOverlayFocusable`).
 */
@Composable
private fun WordFixForm(
    form: WordFormState,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var value by remember(form) { mutableStateOf(form.word) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSave = value.isNotBlank() && value.trim() != form.word.trim()

    LaunchedEffect(form) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = form.action.formTitle(form.word),
            color = scheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(form.action.fieldLabel, fontSize = 12.sp) },
            singleLine = true,
            shape = RoundedCornerShape(Radius.sm),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSubmit(value) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(text = "Cancel", fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Button(
                onClick = { onSubmit(value) },
                enabled = canSave,
                shape = RoundedCornerShape(Radius.sm),
            ) {
                Text(text = "Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    description: String,
    filled: Boolean,
    color: Color = Color.Unspecified,
    containerColor: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val finalContainer = if (filled) containerColor else Color.Transparent
    val finalContent = if (filled) contentColor else color
    val borderModifier = if (filled) {
        Modifier
    } else {
        Modifier.border(
            width = CardLayout.CAPSULE_OUTLINE_WIDTH_DP.dp,
            color = color,
            shape = CircleShape,
        )
    }

    Surface(
        modifier = Modifier
            .requiredSize(CardLayout.ACTION_CIRCLE_SIZE_DP.dp)
            .clip(CircleShape)
            .then(borderModifier)
            .clickable(enabled = enabled, onClick = onClick),
        color = finalContainer,
        contentColor = finalContent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.requiredSize(CardLayout.ACTION_GLYPH_SIZE_DP.dp),
                tint = finalContent,
            )
        }
    }
}

/**
 * The card waveform: travels at 1.2 crests/s (7.5398 rad/s) and follows the mic level,
 * ranging from 7.04dp peak-to-peak at silence floor to 28.8dp at full voice, in a 32dp container.
 */
@Composable
private fun CardWaveform(
    audioLevel: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phase = remember { mutableFloatStateOf(0f) }
    val stagedLevel = remember { mutableFloatStateOf(0f) }
    val fastLevel = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos

            // Perceptual level (dB window), followed by two-pole filter (0.08s attack / 0.30s release)
            val target = VoiceLevel.perceptual(audioLevel())
            val tau = (if (target > stagedLevel.floatValue) LEVEL_ATTACK_SECONDS else LEVEL_RELEASE_SECONDS) *
                TWO_POLE_CORRECTION
            val follow = 1f - exp(-dtSeconds / tau)
            stagedLevel.floatValue += (target - stagedLevel.floatValue) * follow
            fastLevel.floatValue += (stagedLevel.floatValue - fastLevel.floatValue) * follow

            // Constant phase speed 7.5398 rad/s (1.2 crests/s)
            phase.floatValue = (phase.floatValue + dtSeconds * WaterSurface.OMEGA_RECORDING) % TWO_PI
        }
    }

    Canvas(modifier) {
        drawWaterSurface(
            values = WaterSurface.samples(
                phase = phase.floatValue,
                amplitude = WaterSurface.amplitudeFor(fastLevel.floatValue),
            ),
            color = color,
            strokeWidth = CardLayout.WAVEFORM_STROKE_DP.dp,
            glowAlpha = 0f,
        )
    }
}

/**
 * The settings side of the card: the active-profile switcher and the first quick settings. It fills
 * exactly the card's fixed size and scrolls, because a user can create many profiles (and more quick
 * settings are coming). Rendered inside the pill's own window, not a `ModalBottomSheet` — a real
 * modal needs a window token an overlay service does not have.
 */
@Composable
private fun SettingsPanel(
    profiles: List<CleaningProfile>,
    activeProfileId: String,
    cleanupEnabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onToggleCleanup: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Quick settings",
                color = scheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = "Back to dictation",
                tint = scheme.primary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            SectionLabel("Profile")
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProfile(profile.id) }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = profile.id == activeProfileId,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = profile.name,
                        color = scheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = if (profile.id == activeProfileId) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            SectionLabel("Cleanup")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI cleanup",
                        color = scheme.onSurface,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "Rewrite filler and false starts before inserting",
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Switch(checked = cleanupEnabled, onCheckedChange = { onToggleCleanup() })
            }
        }
    }
}

/** A small, quiet section heading inside the settings view. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * The recording waveform, driven by its own frame clock. [audioLevel] and the phase are read
 * inside the `Canvas` draw lambda, so a frame invalidates the draw pass only — the composition
 * never runs per frame. That is what keeps the ripple at display rate (120 Hz) instead of
 * recomposition rate.
 */
@Composable
fun WaterSurfaceWave(
    audioLevel: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val phase = remember { mutableFloatStateOf(0f) }
    val stagedLevel = remember { mutableFloatStateOf(0f) }
    val fastLevel = remember { mutableFloatStateOf(0f) }
    val swellLevel = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNanos = nowNanos

            // Perceptual level (dB window), then two cascaded one-poles for a soft, damped swell...
            val target = VoiceLevel.perceptual(audioLevel())
            val tau = (if (target > stagedLevel.floatValue) LEVEL_ATTACK_SECONDS else LEVEL_RELEASE_SECONDS) *
                TWO_POLE_CORRECTION
            val follow = 1f - exp(-dtSeconds / tau)
            stagedLevel.floatValue += (target - stagedLevel.floatValue) * follow
            fastLevel.floatValue += (stagedLevel.floatValue - fastLevel.floatValue) * follow

            // ...plus a much slower follower that the visual mixes in, so the wave breathes.
            val swellFollow = 1f - exp(-dtSeconds / SWELL_SECONDS)
            swellLevel.floatValue += (fastLevel.floatValue - swellLevel.floatValue) * swellFollow

            // Constant speed: the wave's tempo never reacts to the voice, only its height does.
            phase.floatValue = (phase.floatValue + dtSeconds * RIPPLE_RADIANS_PER_SECOND) % TWO_PI
        }
    }

    Canvas(modifier) {
        // Read the states inside the draw lambda so a frame invalidates drawing only.
        val level = fastLevel.floatValue * (1f - SWELL_MIX) + swellLevel.floatValue * SWELL_MIX
        drawWaterSurface(
            values = WaterSurface.samples(
                phase = phase.floatValue,
                amplitude = WaterSurface.amplitudeFor(level),
            ),
            color = color,
            // The halo follows the slow swell, so it breathes rather than flickers.
            glowAlpha = swellLevel.floatValue * 0.9f,
        )
    }
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
