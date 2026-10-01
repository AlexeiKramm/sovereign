package fi.ak.smartvoice.ui.home

import fi.ak.smartvoice.R
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModelType
import fi.ak.smartvoice.ui.components.PermissionItem
import fi.ak.smartvoice.ui.components.StatusConfigurationCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.data.DictationRecord
import fi.ak.smartvoice.data.UsageStatsData
import fi.ak.smartvoice.data.WordDiffCalculator
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.ui.DictationUiState
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.components.DiffModeCycleButton
import fi.ak.smartvoice.ui.components.InlineDiffMode
import fi.ak.smartvoice.ui.components.InlineDiffText
import fi.ak.smartvoice.ui.components.TranscriptMetadata
import fi.ak.smartvoice.ui.components.TranscriptResultCard
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Material 3 Home screen matching SPEC.md Section 3.1:
 * - Centered brand hero with 4-bar waveform mark
 * - Card 1: Status & Active Configuration (green checkmark, LLM polish toggle, Profile/Model tiles, permission alerts)
 * - Card 2: Try Dictation (header with transcribe file pill, animated mic action, raw vs cleaned comparison box)
 * - Card 3: Usage Statistics (header with today's words, understated net time saved banner, 2x2 metric tiles)
 * - Card 4: Last Dictation (header with word count and history link, last transcript snippet)
 */
@Composable
fun HomeScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onNavigateToModels: () -> Unit = {},
    onNavigateToProfiles: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenTranscribe: () -> Unit = {},
    onSetUpModels: () -> Unit = {},
) {
    val context = LocalContext.current

    val micGranted by viewModel.isMicPermissionGranted.collectAsStateWithLifecycle()
    val overlayGranted by viewModel.isOverlayPermissionGranted.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.isAccessibilityPermissionGranted.collectAsStateWithLifecycle()
    val cleanupEnabled by viewModel.cleanupEnabled.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedAsrModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
    val dictationState by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.usageStats.collectAsStateWithLifecycle()
    val historyData by viewModel.dictationHistory.collectAsStateWithLifecycle()

    var showTimeSavedDialog by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshDashboard() }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDashboard()
        onPauseOrDispose { }
    }

    val missingPermissions = listOfNotNull(
        if (!micGranted) PermissionItem.Microphone else null,
        if (!overlayGranted) PermissionItem.Overlay else null,
        if (!accessibilityGranted) PermissionItem.Accessibility else null,
    )

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    val activeProfileName = activeProfile?.name ?: "Smart Clean"

    val activeModelLabel = AsrModels.getTierTitle(selectedAsrModel.id)

    if (showTimeSavedDialog) {
        TimeSavedExplanationDialog(onDismiss = { showTimeSavedDialog = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // Centered Brand Hero
        BrandHero(modifier = Modifier.fillMaxWidth())

        // Card 1: Status & Active Configuration
        StatusConfigurationCard(
            allGreen = missingPermissions.isEmpty(),
            missingPermissions = missingPermissions,
            onGrantPermission = { item ->
                when (item) {
                    PermissionItem.Microphone ->
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

                    PermissionItem.Overlay -> runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }.onFailure {
                        Toast.makeText(context, "Could not open display settings", Toast.LENGTH_SHORT).show()
                    }

                    PermissionItem.Accessibility -> runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.onFailure {
                        Toast.makeText(context, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            selectedAsrModel = selectedAsrModel,
            asrModels = viewModel.asrCatalog,
            onSelectAsrModel = { viewModel.selectAsrModel(it) },
            onNavigateToModels = onNavigateToModels,
            cleanupEnabled = cleanupEnabled,
            onToggleCleanup = { viewModel.toggleCleanupEnabled() },
            activeProfileId = activeProfileId,
            profiles = profiles,
            onSelectProfile = { viewModel.setActiveProfile(it.id) },
            onNavigateToProfiles = onNavigateToProfiles,
        )

        // Card 2: Usage Statistics
        UsageStatisticsCard(
            stats = stats,
            onOpenTimeSaved = { showTimeSavedDialog = true },
        )

        // Card 3: Last Dictation Snippet
        LastDictationCard(
            lastRecord = historyData.records.firstOrNull(),
            onOpenHistory = onOpenHistory,
        )

        // Card 4: Try Dictation
        TryDictationCard(
            state = dictationState,
            micGranted = micGranted,
            onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = { viewModel.stopAndTranscribe() },
            onOpenTranscribe = onOpenTranscribe,
            selectedAsrModel = selectedAsrModel,
            cleanupEnabled = cleanupEnabled,
        )

        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

// ----------------------------------------------------------------------------
// A. Centered Brand Hero
// ----------------------------------------------------------------------------

@Composable
private fun BrandHero(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Official Sovereign Brand Stencil
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )

            Text(
                text = "Sovereign",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = "On-device speech-to-text with LLM cleanup",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------------
// B. Card 2: Try Dictation
// ----------------------------------------------------------------------------

@Composable
private fun TryDictationCard(
    state: DictationUiState,
    micGranted: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenTranscribe: () -> Unit,
    selectedAsrModel: AsrModelSpec,
    cleanupEnabled: Boolean,
) {
    val isRecording = state is DictationUiState.Recording
    val isWorking = state is DictationUiState.Working
    val isDone = state is DictationUiState.Done
    val isFailed = state is DictationUiState.Failed

    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Try dictation",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Speak a sentence to test recognition and polish",
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Action Pill: [🎙️ Transcribe file]
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable(onClick = onOpenTranscribe),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "Transcribe file",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Central Mic Action
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Left animated waveform bars
                    WaveformBars(isRecording = isRecording)

                    // 56dp Mic Button
                    val micBg = if (isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    val micTint = if (isRecording) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(micBg)
                            .clickable {
                                when {
                                    isRecording -> onStopRecording()
                                    isWorking -> {}
                                    !micGranted -> onRequestMicPermission()
                                    else -> onStartRecording()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Start recording",
                            tint = micTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // Right animated waveform bars
                    WaveformBars(isRecording = isRecording)
                }

                val statusText = when (state) {
                    is DictationUiState.Recording -> "Listening to speech..."
                    is DictationUiState.Working -> state.stage
                    is DictationUiState.Done -> "Transcribed and cleaned"
                    is DictationUiState.Failed -> "Dictation failed: ${state.message}"
                    else -> "Tap mic to speak"
                }

                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Result Area
            val done = state as? DictationUiState.Done
            if (done != null && done.outcome.text.isNotBlank()) {
                val diff = done.diff
                val raw = done.rawText?.ifBlank { done.outcome.text } ?: done.outcome.text
                val cleaned = done.outcome.text

                TranscriptResultCard(
                    rawText = raw,
                    cleanedText = cleaned,
                    title = "TRANSCRIPT",
                    titleBadge = if (diff != null && diff.wordsRemoved > 0) {
                        {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = "${diff.wordsRemoved} fillers removed",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    softWrap = false,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    } else null,
                    metadata = TranscriptMetadata(
                        audioSeconds = done.outcome.audioSeconds,
                        elapsedMs = done.outcome.elapsedMs,
                        asrModelName = selectedAsrModel.id,
                        llmEnabled = cleanupEnabled,
                    ),
                )
            } else {
                // Empty state with dashed border
                val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedBorder(
                            width = 1.dp,
                            color = outlineColor,
                            cornerRadius = 16.dp,
                        )
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Recognition and polish results will appear here",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Tap the microphone to speak a test sentence",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveformBars(isRecording: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")
    val anim1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "anim1",
    )
    val anim2 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(320, delayMillis = 80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "anim2",
    )
    val anim3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, delayMillis = 40, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "anim3",
    )

    val primary = MaterialTheme.colorScheme.primary
    val h1 = if (isRecording) (12 + 16 * anim1).dp else 12.dp
    val h2 = if (isRecording) (16 + 20 * anim2).dp else 18.dp
    val h3 = if (isRecording) (10 + 14 * anim3).dp else 10.dp
    val alpha = if (isRecording) 0.9f else 0.25f

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(36.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = h1)
                .clip(CircleShape)
                .background(primary.copy(alpha = alpha)),
        )
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = h2)
                .clip(CircleShape)
                .background(primary.copy(alpha = alpha)),
        )
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = h3)
                .clip(CircleShape)
                .background(primary.copy(alpha = alpha)),
        )
    }
}

// ----------------------------------------------------------------------------
// D. Card 3: Usage Statistics
// ----------------------------------------------------------------------------

@Composable
private fun UsageStatisticsCard(
    stats: UsageStatsData,
    onOpenTimeSaved: () -> Unit,
) {
    // Calculate today's words
    val todayMidnight = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val todayWords = stats.recentRecords
        .filter { it.timestampMs >= todayMidnight }
        .sumOf { it.deliveredWords }

    val totalWords = if (stats.totalDeliveredWords > 0) stats.totalDeliveredWords else stats.totalCleanedWords
    val audioMinutes = stats.totalAudioSeconds / 60.0
    val avgWpm = if (audioMinutes > 0.05 && totalWords > 0) (totalWords / audioMinutes).roundToInt() else 0
    val fixesApplied = stats.totalWordsRemoved + stats.totalWordsChanged
    val audioMins = (stats.totalAudioSeconds / 60.0).roundToInt()
    val timeSavedHours = stats.typingMinutesSaved / 60.0
    val timeSavedStr = String.format(Locale.US, "%.1f hrs", timeSavedHours)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Usage statistics",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "$todayWords words today",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Net Time Saved Tile (Understated Banner)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Net time saved",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenTimeSaved),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Time saved explanation",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = timeSavedStr,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 4-Grid Metric Tiles (2x2 Grid)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricTile(
                        label = "Words dictated",
                        value = formatCount(totalWords),
                        subtext = "${stats.totalUtterances} sessions",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "Speaking speed",
                        value = "$avgWpm WPM",
                        subtext = if (avgWpm == 0) "Ready to measure" else "Average pace",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricTile(
                        label = "Fixes applied",
                        value = formatCount(fixesApplied),
                        subtext = "${stats.totalWordsRemoved} fillers stripped",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "Audio spoken",
                        value = "${audioMins}m",
                        subtext = "$audioMins mins recorded",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (stats.asrRealTimeSpeed > 0.0 || stats.llmRealTimeSpeed > 0.0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "On-device processing speed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val speedText = buildList {
                                if (stats.asrRealTimeSpeed > 0.0) {
                                    add("ASR: %.1f×".format(Locale.US, stats.asrRealTimeSpeed))
                                }
                                if (stats.llmRealTimeSpeed > 0.0) {
                                    add("LLM: %.1f×".format(Locale.US, stats.llmRealTimeSpeed))
                                }
                                val pipelineSpeed = if (stats.pipelineRealTimeSpeed > 0.0) stats.pipelineRealTimeSpeed else stats.e2eRealTimeSpeed
                                if (pipelineSpeed > 0.0) {
                                    add("Pipeline: %.1f×".format(Locale.US, pipelineSpeed))
                                }
                            }.joinToString("  •  ")
                            Text(
                                text = speedText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtext,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------------
// E. Card 4: Last Dictation Snippet
// ----------------------------------------------------------------------------

@Composable
private fun LastDictationCard(
    lastRecord: DictationRecord?,
    onOpenHistory: () -> Unit,
) {
    val wordCount = if (lastRecord != null) {
        WordDiffCalculator.tokenizeWords(lastRecord.cleanedText).size
    } else 0

    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Last dictation · $wordCount words",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable(onClick = onOpenHistory),
                ) {
                    Text(
                        text = "All history →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Body
            if (lastRecord == null) {
                val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedBorder(
                            width = 1.dp,
                            color = outlineColor,
                            cornerRadius = 14.dp,
                        )
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No dictations yet. Tap the floating pill in any app to get started.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TranscriptResultCard(
                    rawText = lastRecord.rawText,
                    cleanedText = lastRecord.cleanedText.ifBlank { lastRecord.rawText },
                    title = "DELIVERED TEXT",
                    initialMode = InlineDiffMode.CLEAN,
                    metadata = TranscriptMetadata(
                        audioSeconds = lastRecord.durationSeconds,
                        asrModelName = lastRecord.asrModel,
                        llmEnabled = lastRecord.llmModel != null && lastRecord.llmModel.isNotBlank(),
                    ),
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Helpers & Dialogs
// ----------------------------------------------------------------------------

private fun formatCount(value: Int): String =
    if (value >= 10_000) "${(value / 1000.0).roundToInt()}k" else value.toString()

private fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp,
    dashLength: Dp = 6.dp,
    gapLength: Dp = 4.dp,
): Modifier = drawBehind {
    val strokeWidth = width.toPx()
    val stroke = Stroke(
        width = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(dashLength.toPx(), gapLength.toPx()),
            0f,
        ),
    )
    val r = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(r, r),
        style = stroke,
    )
}

@Composable
private fun TimeSavedExplanationDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Net time saved",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Sovereign measures productivity by comparing manual mobile typing time against spoken dictation:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Net Time Saved =\n(Words Dictated ÷ 40 WPM) − Audio Spoken",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Text(
                    text = "• Average mobile typing speed is ~40 words per minute.\n" +
                        "• Normal speaking pace is ~130–160 words per minute.\n" +
                        "• Voice dictation with on-device LLM cleanup is approximately 3–4x faster than manual thumb typing.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

