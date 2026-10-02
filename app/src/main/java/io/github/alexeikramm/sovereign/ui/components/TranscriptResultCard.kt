package io.github.alexeikramm.sovereign.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeikramm.sovereign.asr.AsrModels
import java.util.Locale

/**
 * Standardized metadata attached to a speech transcription result.
 */
data class TranscriptMetadata(
    val audioSeconds: Double? = null,
    val elapsedMs: Long? = null,
    val asrModelName: String? = null,
    val llmEnabled: Boolean? = null,
)

/**
 * Standardized badge for model / feature tags in the metadata footer.
 */
@Composable
fun ModelBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Standardized oval copy pill button used across Home, Transcribe File, and History.
 */
@Composable
fun CopyPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Copy",
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy text",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * Standardized single-card container for dictation and transcription results.
 *
 * Provides:
 * - Header: Title + optional badge on left; [ ⇄ Diff ] pill + [ Copy ] pill + optional Delete on right.
 * - Body: [InlineDiffText] with mode cycling (Diff, Clean, Raw).
 * - Optional edit stats (e.g. Spoken/Polished/Removed/Corrected metrics).
 * - Footer: Standardized duration, real-time speed factor, and model badges.
 */
@Composable
fun TranscriptResultCard(
    rawText: String?,
    cleanedText: String,
    modifier: Modifier = Modifier,
    initialMode: InlineDiffMode = InlineDiffMode.DIFF,
    title: String = "TRANSCRIPT",
    titleBadge: @Composable (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCopyFeedback: (() -> Unit)? = null,
    editStatsContent: @Composable (() -> Unit)? = null,
    metadata: TranscriptMetadata? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    textLineHeight: TextUnit = 20.sp,
) {
    val context = LocalContext.current
    val raw = rawText?.trim().orEmpty()
    val clean = cleanedText.trim()
    val hasEdits = raw.isNotBlank() && raw != clean

    var diffMode by remember(raw, clean) {
        mutableStateOf(if (hasEdits) initialMode else InlineDiffMode.CLEAN)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Title + badge on the left; Controls (Cycle pill + Copy pill) on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    titleBadge?.invoke()
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hasEdits) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { diffMode = diffMode.next() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = "View mode: ${diffMode.label}. Tap to cycle.",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = diffMode.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    // Standardized Copy pill (always copies cleanedText)
                    CopyPillButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Sovereign", clean.ifBlank { raw }))
                            if (onCopyFeedback != null) {
                                onCopyFeedback()
                            } else {
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            // Optional custom edit breakdown chips (e.g. in Transcribe Hub)
            editStatsContent?.invoke()

            // Main Text Content: Selectable Inline Diff
            InlineDiffText(
                rawText = raw,
                cleanedText = clean,
                mode = diffMode,
                style = textStyle,
                lineHeight = textLineHeight,
            )

            // Standardized Metadata & Action Footer
            if (metadata != null || onDelete != null) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left side: Duration & Speed
                    val speedText = buildString {
                        if (metadata?.audioSeconds != null && metadata.audioSeconds > 0) {
                            append(String.format(Locale.US, "Audio: %.1fs", metadata.audioSeconds))
                        }
                        if (metadata?.elapsedMs != null && metadata.elapsedMs > 0 && metadata.audioSeconds != null && metadata.audioSeconds > 0) {
                            val speedMultiplier = (metadata.audioSeconds * 1000.0) / metadata.elapsedMs
                            append(String.format(Locale.US, " • %.1f× speed", speedMultiplier))
                        }
                    }

                    if (speedText.isNotBlank()) {
                        Text(
                            text = speedText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    // Right side: Model tags + optional Delete button
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (metadata != null) {
                            val asrTitle = AsrModels.getTierTitle(metadata.asrModelName)
                            ModelBadge(text = asrTitle)

                            if (metadata.llmEnabled != null) {
                                val llmText = if (metadata.llmEnabled) "LLM Cleanup ON" else "LLM Cleanup OFF"
                                ModelBadge(
                                    text = llmText,
                                    color = if (metadata.llmEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (onDelete != null) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete item",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
