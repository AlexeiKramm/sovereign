package fi.ak.smartvoice.floating

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

enum class FloatingUiState {
    RESTING_PILL,
    RECORDING,
    REVIEW,
}

@Composable
fun FloatingDictationOverlay(
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    audioLevel: Float,
    isProcessing: Boolean,
    statusNote: String?,
    onPillClicked: () -> Unit,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocessFullText: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .wrapContentSize()
            .padding(8.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        when (uiState) {
            FloatingUiState.RESTING_PILL -> {
                RestingPillView(
                    onClick = onPillClicked,
                    onDrag = onDrag,
                )
            }
            FloatingUiState.RECORDING, FloatingUiState.REVIEW -> {
                ExpandedCardView(
                    uiState = uiState,
                    previewText = previewText,
                    rawText = rawText,
                    audioLevel = audioLevel,
                    isProcessing = isProcessing,
                    statusNote = statusNote,
                    onCancel = onCancel,
                    onToggleRecording = onToggleRecording,
                    onCommit = onCommit,
                    onReprocess = onReprocessFullText,
                    onDrag = onDrag,
                )
            }
        }
    }
}

/**
 * Resting state: A sleek, docked pill with audio waveform / mic icon.
 */
@Composable
fun RestingPillView(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Surface(
        modifier = Modifier
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(24.dp), ambientColor = Color.Black)
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color(0x66FFFFFF), Color(0x22FFFFFF))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        color = Color(0xFFE8DEF8), // Soft lilac pastel
        contentColor = Color(0xFF1D192B),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = "Start Voice Dictation",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF1D192B),
            )
        }
    }
}

/**
 * Expanded Wispr Flow card showing live preview, dynamic waveform, and action buttons.
 */
@Composable
fun ExpandedCardView(
    uiState: FloatingUiState,
    previewText: String,
    rawText: String,
    audioLevel: Float,
    isProcessing: Boolean,
    statusNote: String?,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit,
    onCommit: () -> Unit,
    onReprocess: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .width(340.dp)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color(0x44FFFFFF), Color(0x11FFFFFF))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        color = Color(0xFF1F1F24), // Dark sleek surface
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Live Preview Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 160.dp)
                    .verticalScroll(scrollState),
            ) {
                if (previewText.isBlank() && !isProcessing) {
                    Text(
                        text = if (uiState == FloatingUiState.RECORDING) "Listening..." else "Tap mic to speak",
                        color = Color(0x88FFFFFF),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                } else {
                    Text(
                        text = previewText,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 22.sp,
                    )
                }
            }

            if (statusNote != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = statusNote,
                    color = Color(0xFFD0BCFF),
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Controls Row (Wispr Flow style)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Cancel (X) Button
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancel),
                    color = Color(0xFF2C2B30),
                    contentColor = Color(0xFFE6E1E5),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Dictation",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Center Dynamic Waveform / Mic
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(enabled = !isProcessing, onClick = onToggleRecording),
                    color = Color(0xFFE8DEF8),
                    contentColor = Color(0xFF1D192B),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1D192B),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Polishing...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1D192B),
                            )
                        } else if (uiState == FloatingUiState.RECORDING) {
                            DynamicWaveform(
                                audioLevel = audioLevel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(26.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Recording Finished",
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Speak again",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1D192B),
                            )
                        }
                    }
                }

                // Reprocess Full Text Button
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isProcessing && rawText.isNotBlank(), onClick = onReprocess),
                    color = Color(0xFF2C2B30),
                    contentColor = if (rawText.isNotBlank()) Color(0xFFD0BCFF) else Color(0x44FFFFFF),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reprocess Full Text with LLM",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Commit (Checkmark) Button
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(enabled = previewText.isNotBlank() || rawText.isNotBlank(), onClick = onCommit),
                    color = Color(0xFFD0BCFF), // Light accent
                    contentColor = Color(0xFF381E72),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Accept & Inject Text",
                            modifier = Modifier.size(22.dp),
                            tint = Color(0xFF381E72),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Canvas-drawn dynamic responsive waveform with 7 pulsing bars reacting in real time to speech amplitude.
 */
@Composable
fun DynamicWaveform(
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    val barCount = 7
    val levelClamped = audioLevel.coerceIn(0.05f, 1.0f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = 4.dp.toPx()
        val spacing = (width - (barCount * barWidth)) / (barCount + 1)
        val cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)

        // Waveform weights that peak in the center
        val multipliers = floatArrayOf(0.4f, 0.7f, 0.95f, 1.1f, 0.95f, 0.7f, 0.4f)

        for (i in 0 until barCount) {
            val mult = multipliers[i]
            val barHeight = (height * (levelClamped * mult).coerceIn(0.15f, 0.95f))
            val x = spacing + i * (barWidth + spacing)
            val y = (height - barHeight) / 2f

            drawRoundRect(
                color = Color(0xFF1D192B),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}
