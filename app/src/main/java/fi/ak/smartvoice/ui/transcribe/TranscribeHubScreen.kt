package fi.ak.smartvoice.ui.transcribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import fi.ak.smartvoice.cleanup.CleanupPrompt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.audio.AudioDecoder
import fi.ak.smartvoice.data.ProfileStore
import fi.ak.smartvoice.engine.Engine
import fi.ak.smartvoice.ui.DictationUiState
import fi.ak.smartvoice.ui.DictationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TranscribeHubScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedAsrModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
    val selectedLitertModel by viewModel.selectedLitertModel.collectAsStateWithLifecycle()
    val cleanupVariant by viewModel.cleanupVariant.collectAsStateWithLifecycle()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    var pickedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var pickedAudioName by remember { mutableStateOf<String?>(null) }
    var isDecodingAudio by remember { mutableStateOf(false) }

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

    var selectedResultTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- HEADER & MODEL BADGES ---
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Transcribe Hub",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Fast local voice transcription and AI cleanup",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text("ASR: ${selectedAsrModel.label}") },
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text("LLM: ${selectedLitertModel.label}") },
                )
            }
        }

        // --- PROFILE QUICK SWITCHER ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Prompt Rules & Cleanup Profile",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CleanupPrompt.Variant.entries.forEach { variant ->
                        FilterChip(
                            selected = cleanupVariant == variant,
                            onClick = { viewModel.onCleanupVariantChange(variant) },
                            label = {
                                Text(
                                    variant.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                }
            }
        }

        // --- HERO LIVE RECORDING CARD ---
        val isRecording = state is DictationUiState.Recording
        val isWorking = state is DictationUiState.Working

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isRecording) 1.15f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRecording) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Timer / Status
                when (val current = state) {
                    is DictationUiState.Recording -> {
                        val totalSec = current.seconds.toInt()
                        val mins = totalSec / 60
                        val secs = totalSec % 60
                        Text(
                            text = "%02d:%02d".format(mins, secs),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Listening… Tap stop when finished",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is DictationUiState.Working -> {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            text = current.stage,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    is DictationUiState.Failed -> {
                        Text(
                            text = "Transcription Failed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> {
                        Text(
                            text = "00:00",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Text(
                            text = "Tap the microphone to dictate",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Big Record Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .scale(pulseScale),
                ) {
                    Surface(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (!micGranted) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@clickable
                                }
                                if (isRecording) {
                                    viewModel.stopAndTranscribe()
                                } else if (!isWorking) {
                                    viewModel.startRecording()
                                }
                            },
                        color = if (isRecording) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        shadowElevation = 8.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isRecording) {
                                // Stop icon (square)
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color.White, RoundedCornerShape(4.dp)),
                                )
                            } else {
                                // Mic icon
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Start Recording",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                    }
                }

                if (isRecording) {
                    OutlinedButton(onClick = { viewModel.cancelRecording() }) {
                        Text("Cancel")
                    }
                }
            }
        }

        // --- LOCAL AUDIO FILE PICKER CARD ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Transcribe Local Audio File",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Pick any audio file (WAV, M4A, MP3, AAC, FLAC) to transcribe and clean offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { audioPickerLauncher.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (pickedAudioName != null) "Change File" else "Choose Audio File")
                    }

                    if (pickedAudioUri != null && !isDecodingAudio && !isWorking) {
                        Button(
                            onClick = {
                                val uri = pickedAudioUri ?: return@Button
                                val name = pickedAudioName ?: "audio"
                                isDecodingAudio = true
                                scope.launch {
                                    try {
                                        val pcmSamples = AudioDecoder.decodeToPcm(context, uri)
                                        val durationSec = pcmSamples.size / 16000f
                                        isDecodingAudio = false
                                        viewModel.transcribeAudioFile(pcmSamples, name, durationSec)
                                    } catch (e: Exception) {
                                        isDecodingAudio = false
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

                if (isDecodingAudio) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = "Decoding audio to 16kHz PCM…",
                            style = MaterialTheme.typography.bodySmall,
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

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Transcription Result",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Copy button
                            IconButton(onClick = {
                                val textToCopy = if (selectedResultTab == 1) rawText else cleanedText
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("transcription", textToCopy))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                )
                            }
                        }
                    }

                    // Result Tabs
                    TabRow(selectedTabIndex = selectedResultTab) {
                        Tab(
                            selected = selectedResultTab == 0,
                            onClick = { selectedResultTab = 0 },
                            text = { Text("Cleaned") },
                        )
                        Tab(
                            selected = selectedResultTab == 1,
                            onClick = { selectedResultTab = 1 },
                            text = { Text("Raw ASR") },
                        )
                        Tab(
                            selected = selectedResultTab == 2,
                            onClick = { selectedResultTab = 2 },
                            text = { Text("Changes (${diff?.wordsChanged ?: 0})") },
                        )
                    }

                    // Content View
                    SelectionContainer {
                        when (selectedResultTab) {
                            0 -> Text(
                                text = cleanedText.ifBlank { "(empty text)" },
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = 24.sp,
                            )
                            1 -> Text(
                                text = rawText.ifBlank { "(empty text)" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 22.sp,
                            )
                            2 -> {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (diff != null) {
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
                                                    Text("Raw", style = MaterialTheme.typography.labelSmall)
                                                    Text("${diff.rawWords} w", fontWeight = FontWeight.Bold)
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("Final", style = MaterialTheme.typography.labelSmall)
                                                    Text("${diff.deliveredWords} w", fontWeight = FontWeight.Bold)
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("Removed", style = MaterialTheme.typography.labelSmall)
                                                    Text("${diff.wordsRemoved}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("Changed", style = MaterialTheme.typography.labelSmall)
                                                    Text("${diff.wordsChanged}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }

                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Raw Speech (ASR):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                            Text(
                                                text = rawText.ifBlank { "(empty)" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("Cleaned Output (LLM):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(
                                                text = cleanedText.ifBlank { "(empty)" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "No diff metrics recorded for this run.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Diagnostics line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = outcome.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "%.1fs • %d ms".format(outcome.audioSeconds, outcome.elapsedMs),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
