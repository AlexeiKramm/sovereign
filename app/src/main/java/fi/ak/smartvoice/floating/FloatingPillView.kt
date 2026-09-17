package fi.ak.smartvoice.floating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.theme.Motion
import fi.ak.smartvoice.theme.Radius
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.theme.SmartVoiceTheme
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
 * How the minimized pill looks. The resting pill and the quick-capture pill are one composable
 * driven by this phase, on purpose: switching composables mid-hold would dispose the pointer node
 * and cancel the gesture that is still waiting for the finger to lift.
 */
enum class MinimizedPillPhase {
    RESTING,
    LISTENING,
    PROCESSING,
    RESULT,
}

/**
 * The recording wave's phase speed, in radians per second — deliberately a **constant**. Modulating
 * it with the level was the source of the "accelerates to max then slams down" feel: speed is the
 * integral of the signal, so level wobble becomes visible position wobble. Only the *amplitude*
 * reacts to the voice (see `VoiceLevel`).
 */
private const val RIPPLE_RADIANS_PER_SECOND = 4.5f

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

/** Width of the expanded card (and of the settings view that replaces it). */
private val CARD_WIDTH = 340.dp

/**
 * Fixed height of the expanded card. The dictation view and the settings view are both exactly this
 * tall, so flipping between them never resizes the overlay window — resizing it is what made the pill
 * shake, because a `WRAP_CONTENT` overlay's new bounds and its Compose content are not applied on the
 * same frame. The transcript and the settings list scroll inside this fixed box.
 */
private val CARD_HEIGHT = 210.dp

/** The resting pill's waveform, and therefore the resting pill's whole content. */
private val RESTING_WAVE_WIDTH = 34.dp
private val RESTING_WAVE_HEIGHT = 26.dp

@Composable
fun FloatingDictationOverlay(
    darkTheme: Boolean,
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    /**
     * A **provider**, not a value: the level changes on every audio frame, and reading it inside
     * the waveform's draw pass means a new frame invalidates drawing only — never a recomposition
     * of the whole card.
     */
    audioLevel: () -> Float,
    isProcessing: Boolean,
    statusNote: String?,
    /** Non-null when cleanup failed; the card switches to the raw-text fallback treatment. */
    errorMessage: String?,
    /** True once the cleanup model is loaded, which is what the resting pill's breathing signals. */
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
) {
    // The overlay lives in a service window, so it does not inherit the Activity's theme: the
    // service resolves the app's theme mode (falling back to the system) and passes it in.
    SmartVoiceTheme(themeMode = if (darkTheme) AppThemeMode.DARK else AppThemeMode.LIGHT) {
        val dragModifier = Modifier.pillDragModifier(
            onDragStart = onDragStart,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
        )

        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(8.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            when (uiState) {
                // Resting and quick-capture deliberately share one call site: the hold gesture is
                // still waiting for the finger when the state flips to QUICK_CAPTURE, and a
                // different composable would dispose the pointer node and lose the release.
                FloatingUiState.RESTING_PILL, FloatingUiState.QUICK_CAPTURE -> MinimizedPillView(
                    phase = when {
                        uiState == FloatingUiState.RESTING_PILL -> MinimizedPillPhase.RESTING
                        isProcessing -> MinimizedPillPhase.PROCESSING
                        quickStatus != null -> MinimizedPillPhase.RESULT
                        else -> MinimizedPillPhase.LISTENING
                    },
                    modelReady = modelReady,
                    audioLevel = audioLevel,
                    statusText = quickStatus,
                    statusIsError = quickStatusIsError,
                    quickCaptureEnabled = quickCaptureEnabled,
                    onTap = onPillClicked,
                    onQuickCaptureStart = onQuickCaptureStart,
                    onQuickCaptureEnd = onQuickCaptureEnd,
                    onDragStart = onDragStart,
                    onDragBy = onDragBy,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )

                FloatingUiState.RECORDING, FloatingUiState.REVIEW -> ExpandedCardView(
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    audioLevel = audioLevel,
                    isProcessing = isProcessing,
                    statusNote = statusNote,
                    errorMessage = errorMessage,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    cleanupEnabled = cleanupEnabled,
                    settingsOpen = settingsOpen,
                    wordSelection = wordSelection,
                    wordForm = wordForm,
                    dragModifier = dragModifier,
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
    }
}

/**
 * Drag handling shared by the pill and the card: pointer deltas go straight to the service, which
 * owns the window position and therefore the fling velocity (see `DragVelocityTracker` — the
 * pointer's own coordinates are useless here, because the window moves under the finger).
 */
private fun Modifier.pillDragModifier(
    onDragStart: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier {
    return this.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onDragStart() },
            onDrag = { change, dragAmount ->
                change.consume()
                onDragBy(dragAmount.x, dragAmount.y)
            },
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
        )
    }
}

/**
 * The minimized pill: at rest it is a small waveform that *breathes* while the cleanup model is
 * loaded and sits still while it is not; during quick capture the same box becomes a live
 * listening surface, then a processing spinner, then a one-line result.
 *
 * All four phases are one composable on purpose — see [MinimizedPillPhase]. Nothing here changes
 * the window's height, so the pill never resizes during a hold.
 *
 * CHUNK-3B removed the `···` from here: on the collapsed pill it was lost screen space, and quick
 * settings are now reached by opening the card first.
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
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val showError = phase == MinimizedPillPhase.RESULT && statusIsError
    val container = when {
        showError -> scheme.errorContainer
        phase == MinimizedPillPhase.LISTENING -> scheme.primary
        else -> scheme.primaryContainer
    }
    val content = when {
        showError -> scheme.onErrorContainer
        phase == MinimizedPillPhase.LISTENING -> scheme.onPrimary
        else -> scheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Radius.lg))
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
        contentColor = content,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .height(RESTING_WAVE_HEIGHT)
                .widthIn(min = RESTING_WAVE_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                MinimizedPillPhase.RESTING -> {
                    // `breath` is a State, not a value: reading it inside the layer/draw lambdas
                    // below means the pulse costs a layer update per frame instead of a
                    // recomposition per frame. It is only requested at rest, so listening does not
                    // pay for an animation it does not read.
                    val breath = rememberBreath(modelReady)
                    Canvas(
                        modifier = Modifier
                            .width(RESTING_WAVE_WIDTH)
                            .height(RESTING_WAVE_HEIGHT)
                            .graphicsLayer {
                                val pulse = 1f + breath.value * 0.03f
                                scaleX = pulse
                                scaleY = pulse
                            },
                    ) {
                        drawWaterSurface(
                            values = WaterSurface.restingSamples(),
                            color = content.copy(alpha = 0.7f + breath.value * 0.3f),
                            strokeWidth = 1.8.dp,
                        )
                    }
                }

                MinimizedPillPhase.LISTENING -> WaterSurfaceWave(
                    audioLevel = audioLevel,
                    color = content,
                    modifier = Modifier
                        .width(RESTING_WAVE_WIDTH)
                        .height(RESTING_WAVE_HEIGHT),
                )

                MinimizedPillPhase.PROCESSING -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )

                MinimizedPillPhase.RESULT -> Text(
                    text = statusText.orEmpty(),
                    color = content,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
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
 * Slow, low-frequency breathing. While no model is loaded the state is a constant zero, so a
 * resting pill with nothing to signal costs no animation frames at all — it is not merely faded.
 */
@Composable
private fun rememberBreath(modelReady: Boolean): State<Float> {
    if (!modelReady) {
        return remember { mutableFloatStateOf(0f) }
    }
    val transition = rememberInfiniteTransition(label = "pill-breath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pill-breath-value",
    )
}

/**
 * The expanded card: a **fixed-size two-sided view**. The dictation side (transcript + actions) and
 * the settings side (profiles + quick settings) are both exactly `CARD_WIDTH × CARD_HEIGHT`, so
 * flipping between them never resizes the overlay window.
 */
@Composable
fun ExpandedCardView(
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    audioLevel: () -> Float,
    isProcessing: Boolean,
    statusNote: String?,
    errorMessage: String?,
    profiles: List<CleaningProfile>,
    activeProfileId: String,
    cleanupEnabled: Boolean,
    settingsOpen: Boolean,
    wordSelection: WordSelection?,
    wordForm: WordFormState?,
    dragModifier: Modifier,
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
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(Radius.lg))
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Radius.lg)),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
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
            modifier = Modifier.fillMaxSize(),
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
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    audioLevel = audioLevel,
                    isProcessing = isProcessing,
                    statusNote = statusNote,
                    errorMessage = errorMessage,
                    wordSelection = wordSelection,
                    wordForm = wordForm,
                    dragModifier = dragModifier,
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
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    audioLevel: () -> Float,
    isProcessing: Boolean,
    statusNote: String?,
    errorMessage: String?,
    wordSelection: WordSelection?,
    wordForm: WordFormState?,
    dragModifier: Modifier,
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
    val scheme = MaterialTheme.colorScheme
    val failed = errorMessage != null
    // The fallback: on an LLM failure we show what was actually heard so no words are lost.
    val displayText = if (failed) rawText else previewText
    // Long-press-to-fix is offered only on the settled cleaned text: while recording the preview
    // streams and rewrites, so the word under the finger can move, and the raw fallback is not the
    // text this feature trains the dictionary against.
    val canFixWords = uiState == FloatingUiState.REVIEW && !failed && !isProcessing &&
        displayText.isNotBlank()
    val transcriptLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val transcript = remember(displayText, wordSelection, scheme.primary) {
        highlightedTranscript(displayText, wordSelection, scheme.primary)
    }

    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg)) {
        // Drag grip on the left, settings on the right. Dragging the text or the buttons would fight
        // scrolling and tapping, so only this strip moves the pill.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .then(dragModifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(scheme.outlineVariant),
                )
            }
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = "Quick settings",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

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
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // The scroller is a child of this Box so the action bar can be pinned to the
                // transcript's bottom edge instead of scrolling away with the text.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    if (displayText.isBlank() && !isProcessing) {
                        Text(
                            text = if (uiState == FloatingUiState.RECORDING) "Listening…" else "Tap to speak",
                            color = scheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                    } else {
                        Text(
                            text = transcript,
                            color = scheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 24.sp,
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
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = scheme.error,
                        fontSize = 12.sp,
                    )
                }
            }

            if (statusNote != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = statusNote,
                    color = scheme.primary,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Action row: cancel · waveform/state · reprocess · commit.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CircleAction(
                    icon = Icons.Default.Close,
                    description = "Cancel dictation",
                    container = scheme.surfaceContainerHighest,
                    content = scheme.onSurfaceVariant,
                    onClick = onCancel,
                )

                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .weight(1f)
                        .padding(horizontal = Spacing.sm)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(enabled = !isProcessing, onClick = onToggleRecording),
                    color = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        when {
                            isProcessing -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = scheme.onPrimaryContainer,
                                )
                                Spacer(modifier = Modifier.width(Spacing.sm))
                                Text(
                                    text = "Polishing…",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            uiState == FloatingUiState.RECORDING -> WaterSurfaceWave(
                                audioLevel = audioLevel,
                                // Error: the surface darkens instead of going silent.
                                color = if (failed) {
                                    scheme.onPrimaryContainer.copy(alpha = 0.35f)
                                } else {
                                    scheme.onPrimaryContainer
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp),
                            )

                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(
                                    text = "Speak again",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                CircleAction(
                    icon = Icons.Default.Refresh,
                    description = "Reprocess full text with AI",
                    container = scheme.surfaceContainerHighest,
                    content = if (!isProcessing && rawText.isNotBlank()) {
                        scheme.primary
                    } else {
                        scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    enabled = !isProcessing && rawText.isNotBlank(),
                    onClick = onReprocess,
                )

                Spacer(modifier = Modifier.width(Spacing.sm))

                CircleAction(
                    icon = Icons.Default.Check,
                    description = "Insert text",
                    container = scheme.primary,
                    content = scheme.onPrimary,
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
    container: Color,
    content: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = content,
            )
        }
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
            val tau = (if (target > stagedLevel.value) LEVEL_ATTACK_SECONDS else LEVEL_RELEASE_SECONDS) *
                TWO_POLE_CORRECTION
            val follow = 1f - exp(-dtSeconds / tau)
            stagedLevel.value += (target - stagedLevel.value) * follow
            fastLevel.value += (stagedLevel.value - fastLevel.value) * follow

            // ...plus a much slower follower that the visual mixes in, so the wave breathes.
            val swellFollow = 1f - exp(-dtSeconds / SWELL_SECONDS)
            swellLevel.value += (fastLevel.value - swellLevel.value) * swellFollow

            // Constant speed: the wave's tempo never reacts to the voice, only its height does.
            phase.value = (phase.value + dtSeconds * RIPPLE_RADIANS_PER_SECOND) % TWO_PI
        }
    }

    Canvas(modifier) {
        // Read the states inside the draw lambda so a frame invalidates drawing only.
        val level = fastLevel.value * (1f - SWELL_MIX) + swellLevel.value * SWELL_MIX
        drawWaterSurface(
            values = WaterSurface.samples(
                phase = phase.value,
                amplitude = WaterSurface.amplitudeFor(level),
            ),
            color = color,
            // The halo follows the slow swell, so it breathes rather than flickers.
            glowAlpha = swellLevel.value * 0.9f,
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
