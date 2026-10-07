package io.github.alexeikramm.sovereign.ui.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import io.github.alexeikramm.sovereign.asr.AsrModelType
import io.github.alexeikramm.sovereign.cleanup.CleanupPrompt
import io.github.alexeikramm.sovereign.ui.components.DiffModeCycleButton
import io.github.alexeikramm.sovereign.ui.components.InlineDiffMode
import io.github.alexeikramm.sovereign.ui.components.InlineDiffText
import io.github.alexeikramm.sovereign.ui.components.StatusConfigurationCard
import io.github.alexeikramm.sovereign.ui.components.TranscriptMetadata
import io.github.alexeikramm.sovereign.ui.components.TranscriptResultCard
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
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexeikramm.sovereign.audio.AudioDecoder
import io.github.alexeikramm.sovereign.ui.DictationUiState
import io.github.alexeikramm.sovereign.ui.DictationViewModel
import io.github.alexeikramm.sovereign.ui.components.SettingsGroupCard
import io.github.alexeikramm.sovereign.ui.components.SettingsIconBadge
import kotlinx.coroutines.launch

@Composable
fun TranscribeHubScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    onNavigateUp: (() -> Unit)? = null,
) {
    if (onNavigateUp != null) {
        BackHandler { onNavigateUp() }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedAsrModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
    val cleanupEnabled by viewModel.cleanupEnabled.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()

    var pickedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var pickedAudioName by remember { mutableStateOf<String?>(null) }
    var isDecodingAudio by remember { mutableStateOf(false) }
    var isTranscribingFile by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state !is DictationUiState.Working) {
            isTranscribingFile = false
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        pickedAudioUri = uri
        if (uri != null) {
            var name = "audio_file"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
            pickedAudioName = name
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // --- HEADER ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onNavigateUp != null) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            }
            Text(
                text = "Transcribe Hub",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        // --- STANDARDIZED STATUS & ENGINE CONFIGURATION CARD ---
        StatusConfigurationCard(
            allGreen = true,
            missingPermissions = emptyList(),
            onGrantPermission = {},
            selectedAsrModel = selectedAsrModel,
            asrModels = viewModel.asrCatalog,
            onSelectAsrModel = { viewModel.selectAsrModel(it) },
            onNavigateToModels = {},
            cleanupEnabled = cleanupEnabled,
            onToggleCleanup = { viewModel.toggleCleanupEnabled() },
            activeProfileId = activeProfileId,
            profiles = profiles,
            onSelectProfile = { viewModel.setActiveProfile(it.id) },
            onNavigateToProfiles = {},
            statusSubtitle = "On-device speech engine & cleanup active",
        )

        val isWorking = state is DictationUiState.Working

        // --- LOCAL AUDIO FILE PICKER CARD ---
        val workingState = state as? DictationUiState.Working
        val isActivelyTranscribing = isTranscribingFile && (isDecodingAudio || workingState != null)

        if (isActivelyTranscribing) {
            val currentProgress = if (isDecodingAudio) 0.05f else (workingState?.progress ?: 0.0f)
            val stageTitle = if (isDecodingAudio) "Preparing audio…" else (workingState?.stage ?: "Processing speech…")
            val stageDetails = if (isDecodingAudio) {
                "Converting audio file to 16 kHz format"
            } else {
                workingState?.details ?: "Transcribing and cleaning speech chunks"
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsIconBadge(
                        icon = Icons.Default.GraphicEq,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.primary,
                        size = 52.dp,
                        iconSize = 28.dp,
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stageTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stageDetails,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { currentProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = pickedAudioName ?: "Audio file",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                text = "${(currentProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.cancelFileTranscription()
                            isTranscribingFile = false
                            isDecodingAudio = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.PlayArrow,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column {
                            Text(
                                text = "Transcribe Audio File",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Select any audio recording to transcribe and polish on device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = { audioPickerLauncher.launch("audio/*") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (pickedAudioName != null) "Change File" else "Select Audio File")
                        }

                        if (pickedAudioUri != null && !isDecodingAudio && !isWorking) {
                            Button(
                                onClick = {
                                    val uri = pickedAudioUri ?: return@Button
                                    val name = pickedAudioName ?: "audio"
                                    isDecodingAudio = true
                                    isTranscribingFile = true
                                    scope.launch {
                                        try {
                                            val pcmSamples = AudioDecoder.decodeToPcm(context, uri)
                                            val durationSec = pcmSamples.size / 16000f
                                            isDecodingAudio = false
                                            viewModel.transcribeAudioFile(pcmSamples, name, durationSec)
                                        } catch (e: Exception) {
                                            isDecodingAudio = false
                                            isTranscribingFile = false
                                            Toast.makeText(context, "Audio decode failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            ) {
                                Text("Transcribe")
                            }
                        }
                    }

                    pickedAudioName?.let { name ->
                        Text(
                            text = "Selected: $name",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // --- TRANSCRIPTION RESULTS CARD ---
        val doneState = state as? DictationUiState.Done
        if (doneState != null) {
            val outcome = doneState.outcome
            val cleanedText = outcome.text
            val rawText = doneState.rawText ?: outcome.text
            val diff = doneState.diff

            TranscriptResultCard(
                rawText = rawText,
                cleanedText = cleanedText,
                title = "Transcription Result",
                textStyle = MaterialTheme.typography.bodyLarge,
                textLineHeight = 24.sp,
                editStatsContent = if (diff != null && diff.wordsEdited > 0) {
                    {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Spoken", style = MaterialTheme.typography.labelSmall)
                                    Text("${diff.rawWords} w", fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Polished", style = MaterialTheme.typography.labelSmall)
                                    Text("${diff.deliveredWords} w", fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Removed", style = MaterialTheme.typography.labelSmall)
                                    Text("${diff.wordsRemoved}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Corrected", style = MaterialTheme.typography.labelSmall)
                                    Text("${diff.wordsChanged}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                } else null,
                metadata = TranscriptMetadata(
                    audioSeconds = outcome.audioSeconds,
                    elapsedMs = outcome.elapsedMs,
                    asrModelName = selectedAsrModel.id,
                    llmEnabled = cleanupEnabled,
                ),
            )
        }
    }
}
