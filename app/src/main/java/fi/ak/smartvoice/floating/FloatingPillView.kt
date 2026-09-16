package fi.ak.smartvoice.floating

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.theme.Motion
import fi.ak.smartvoice.theme.Radius
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.theme.SmartVoiceTheme

enum class FloatingUiState {
    RESTING_PILL,
    RECORDING,
    REVIEW,
}

/** Radians per second the recording wave travels. Slow enough to stay calm, fast enough to live. */
private const val RIPPLE_RADIANS_PER_SECOND = 5.2f

/** Width of the expanded card (and the ceiling for the quick-settings panel that hangs above it). */
private val CARD_WIDTH = 340.dp

/** The resting pill's waveform, and therefore the resting pill's whole content. */
private val RESTING_WAVE_WIDTH = 34.dp
private val RESTING_WAVE_HEIGHT = 16.dp

/** The resting pill's outer width — the quick-settings panel's floor, so it is never narrower. */
private val RESTING_PILL_MIN_WIDTH = RESTING_WAVE_WIDTH + Spacing.lg * 2

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
    quickSettingsOpen: Boolean,
    onPillClicked: () -> Unit,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocessFullText: () -> Unit,
    onToggleQuickSettings: () -> Unit,
    onDismissQuickSettings: () -> Unit,
    onSelectProfile: (String) -> Unit,
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
            Column(
                horizontalAlignment = Alignment.End,
                // The panel is listed first so that, with the window anchored bottom/end, opening it
                // grows the window upward and leaves the pill/card exactly where it was.
                verticalArrangement = Arrangement.Bottom,
            ) {
                AnimatedVisibility(
                    visible = quickSettingsOpen,
                    enter = fadeIn(tween(Motion.fastMs)) +
                        expandVertically(tween(Motion.standardMs), expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(Motion.fastMs)) +
                        shrinkVertically(tween(Motion.fastMs), shrinkTowards = Alignment.Bottom),
                ) {
                    QuickSettingsPanel(
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        onSelectProfile = onSelectProfile,
                    )
                }

                when (uiState) {
                    FloatingUiState.RESTING_PILL -> RestingPillView(
                        modelReady = modelReady,
                        dragModifier = dragModifier,
                        onClick = onPillClicked,
                    )

                    FloatingUiState.RECORDING, FloatingUiState.REVIEW -> ExpandedCardView(
                        uiState = uiState,
                        previewText = previewText,
                        rawText = rawText,
                        audioLevel = audioLevel,
                        isProcessing = isProcessing,
                        statusNote = statusNote,
                        errorMessage = errorMessage,
                        quickSettingsOpen = quickSettingsOpen,
                        dragModifier = dragModifier,
                        onCancel = onCancel,
                        onToggleRecording = onToggleRecording,
                        onCommit = onCommit,
                        onReprocess = onReprocessFullText,
                        onQuickSettings = onToggleQuickSettings,
                        onDismissQuickSettings = onDismissQuickSettings,
                    )
                }
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
 * Resting pill: a small waveform that *breathes* while the cleanup model is loaded, and sits
 * still and dimmer while it is not.
 *
 * CHUNK-3B removed the `···` from here: on the collapsed pill it was lost screen space, and quick
 * settings are now reached by opening the card first. What is left is just the waveform (still
 * tappable to start), drawn with its own deliberate calm amplitude rather than the near-silent
 * recording surface.
 */
@Composable
fun RestingPillView(
    modelReady: Boolean,
    dragModifier: Modifier,
    onClick: () -> Unit,
) {
    // `breath` is a State, not a value: reading it inside the layer/draw lambdas below means the
    // pulse costs a layer update per frame instead of a recomposition per frame.
    val breath = rememberBreath(modelReady)
    val waveColor = MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        modifier = Modifier
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(Radius.lg), ambientColor = Color.Black)
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.lg))
            .clickable(onClick = onClick)
            .then(dragModifier),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Canvas(
            modifier = Modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
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
                color = waveColor.copy(alpha = 0.7f + breath.value * 0.3f),
            )
        }
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
 * Expanded Wispr Flow-style card: live (cleaned) preview, the water-surface waveform, and the
 * action row. The quick-settings `···` is deliberately low-weight.
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
    quickSettingsOpen: Boolean,
    dragModifier: Modifier,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocess: () -> Unit,
    onQuickSettings: () -> Unit,
    onDismissQuickSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    val failed = errorMessage != null
    // The fallback: on an LLM failure we show what was actually heard so no words are lost.
    val displayText = if (failed) rawText else previewText

    // A tap anywhere on the card that a button did not consume closes the `···` panel but keeps the
    // card (the user's "press on the text → the menu closes"). Buttons consume their own taps, so
    // this only fires on the dead space, the text and the grip.
    val dismissPanelModifier = if (quickSettingsOpen) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { onDismissQuickSettings() })
        }
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .width(CARD_WIDTH)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(Radius.lg))
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Radius.lg))
            .then(dismissPanelModifier),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            // Drag grip on the left, quick settings on the right. Dragging the text or the buttons
            // would fight scrolling and tapping, so only this strip moves the pill.
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
                        .clickable(onClick = onQuickSettings),
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Live preview — cleaned text only, unless cleanup failed.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 160.dp)
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
                        text = displayText,
                        color = scheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 24.sp,
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
                                    .height(26.dp),
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
 * Compact quick-settings panel: the active profile switcher, and room for a silence quick-adjust
 * later. Rendered inside the pill's own window rather than as a `ModalBottomSheet`, because a
 * real modal needs a window token an overlay service does not have (and its full-screen scrim
 * would swallow touches meant for the app underneath).
 */
@Composable
fun QuickSettingsPanel(
    profiles: List<CleaningProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .widthIn(min = RESTING_PILL_MIN_WIDTH, max = CARD_WIDTH) // hug content between these bounds
            .padding(bottom = Spacing.sm)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(Radius.lg))
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Radius.lg)),
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.md)) {
            Text(
                text = "Quick settings",
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Profile",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(scrollState),
            ) {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .clickable { onSelectProfile(profile.id) }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
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
            }
        }
    }
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

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSeconds = (nowNanos - lastNanos) / 1_000_000_000f
            lastNanos = nowNanos
            phase.value += dtSeconds * RIPPLE_RADIANS_PER_SECOND
        }
    }

    Canvas(modifier) {
        drawWaterSurface(
            values = WaterSurface.samples(phase.value, WaterSurface.amplitudeFor(audioLevel())),
            color = color,
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

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
    )
}
