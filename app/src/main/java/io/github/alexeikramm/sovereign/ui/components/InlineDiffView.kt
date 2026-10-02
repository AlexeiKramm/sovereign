package io.github.alexeikramm.sovereign.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeikramm.sovereign.data.InlineDiffToken
import io.github.alexeikramm.sovereign.data.WordDiffCalculator
import io.github.alexeikramm.sovereign.theme.BadgeGreen

/**
 * Display modes for the unified speech comparison view.
 */
enum class InlineDiffMode(val label: String) {
    DIFF("Diff"),
    CLEAN("Clean"),
    RAW("Raw");

    fun next(): InlineDiffMode = when (this) {
        DIFF -> CLEAN
        CLEAN -> RAW
        RAW -> DIFF
    }
}

/**
 * Compact, tactile cycle button that toggles between Diff, Clean, and Raw transcript views.
 */
@Composable
fun DiffModeCycleButton(
    currentMode: InlineDiffMode,
    onCycleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onCycleMode),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "View mode: ${currentMode.label}. Tap to cycle.",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = currentMode.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Builds an [AnnotatedString] rendering an inline diff between [rawText] and [cleanedText].
 *
 * - [InlineDiffMode.CLEAN]: Returns the cleaned text verbatim.
 * - [InlineDiffMode.RAW]: Returns the raw speech recognition text verbatim.
 * - [InlineDiffMode.DIFF]: Renders unified inline annotations:
 *   - Removed words (fillers): `[~~filler~~]` in strikethrough error color.
 *   - Substituted words: `[~~old~~ → new]` with old struck through and new bold green.
 *   - Inserted words: `[+ added]` in bold green.
 *   - Matched words: standard flowing text with proper casing and punctuation.
 */
@Composable
fun rememberInlineDiffAnnotatedString(
    rawText: String?,
    cleanedText: String,
    mode: InlineDiffMode,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    errorColor: Color = MaterialTheme.colorScheme.error,
    successColor: Color = BadgeGreen,
    bracketColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
): AnnotatedString {
    return remember(rawText, cleanedText, mode, textColor, errorColor, successColor, bracketColor) {
        when (mode) {
            InlineDiffMode.CLEAN -> {
                buildAnnotatedString {
                    append(cleanedText.ifBlank { rawText.orEmpty() })
                }
            }
            InlineDiffMode.RAW -> {
                buildAnnotatedString {
                    append(rawText?.ifBlank { cleanedText } ?: cleanedText)
                }
            }
            InlineDiffMode.DIFF -> {
                val raw = rawText?.trim().orEmpty()
                val clean = cleanedText.trim()

                if (raw.isBlank() || raw == clean) {
                    buildAnnotatedString {
                        append(clean.ifBlank { raw })
                    }
                } else {
                    val diff = WordDiffCalculator.computeInlineDiff(raw, clean)
                    buildAnnotatedString {
                        diff.tokens.forEachIndexed { index, token ->
                            if (index > 0) append(" ")

                            when (token) {
                                is InlineDiffToken.Match -> {
                                    append(token.text)
                                }
                                is InlineDiffToken.Removal -> {
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append("[")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = errorColor,
                                            textDecoration = TextDecoration.LineThrough,
                                        )
                                    ) {
                                        append(token.text)
                                    }
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append("]")
                                    }
                                }
                                is InlineDiffToken.Substitution -> {
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append("[")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = errorColor,
                                            textDecoration = TextDecoration.LineThrough,
                                        )
                                    ) {
                                        append(token.original)
                                    }
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append(" → ")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = successColor,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    ) {
                                        append(token.replacement)
                                    }
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append("]")
                                    }
                                }
                                is InlineDiffToken.Insertion -> {
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append("[+ ")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = successColor,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    ) {
                                        append(token.text)
                                    }
                                    withStyle(SpanStyle(color = bracketColor)) {
                                        append("]")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Standardized single-box component for displaying raw speech vs cleaned output.
 * Selectable text container supporting inline diffs with selectable text and custom styling.
 */
@Composable
fun InlineDiffText(
    rawText: String?,
    cleanedText: String,
    mode: InlineDiffMode,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    lineHeight: TextUnit = 20.sp,
) {
    val annotatedString = rememberInlineDiffAnnotatedString(
        rawText = rawText,
        cleanedText = cleanedText,
        mode = mode,
    )

    SelectionContainer(modifier = modifier) {
        Text(
            text = annotatedString,
            style = style,
            lineHeight = lineHeight,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
