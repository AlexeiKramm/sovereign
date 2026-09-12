package fi.ak.smartvoice.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.engine.DictationOutcome
import fi.ak.smartvoice.engine.Engine
import fi.ak.smartvoice.engine.EnginePreference
import fi.ak.smartvoice.net.TranscribeResponse

@Composable
fun DictationScreen(
    modifier: Modifier = Modifier,
    viewModel: DictationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val enginePreference by viewModel.enginePreference.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val loadProbe by viewModel.loadProbe.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Smart Voice", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Permissions and settings the keyboard cannot handle itself, and a diagnostics run " +
                "through whichever tier is selected.",
            style = MaterialTheme.typography.bodySmall,
        )

        EngineSection(
            preference = enginePreference,
            summary = viewModel.engineSummary(),
            modelStatus = modelStatus,
            loadProbe = loadProbe,
            modelBytes = viewModel.modelBytes,
            onPreferenceChange = viewModel::onEnginePreferenceChange,
            onDownload = viewModel::downloadModel,
            onCancelDownload = viewModel::cancelModelDownload,
            onDeleteModel = viewModel::deleteModel,
            onProbe = viewModel::probeLocalEngine,
        )

        HorizontalDivider()

        ServerSection(
            url = serverUrl,
            status = serverStatus,
            onUrlChange = viewModel::onServerUrlChange,
            onCheck = viewModel::checkServer,
        )

        HorizontalDivider()

        when (val s = state) {
            DictationUiState.Idle -> {
                RecordButton(micGranted) {
                    if (micGranted) viewModel.startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            is DictationUiState.Recording -> {
                Text(
                    "Recording — %.1f s of 16 kHz mono audio · engine: %s".format(
                        s.seconds,
                        s.engine.label,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::stopAndTranscribe) { Text("Stop and transcribe") }
                    OutlinedButton(onClick = viewModel::cancelRecording) { Text("Cancel") }
                }
                Text(
                    "Cancel discards the audio and commits nothing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is DictationUiState.Working -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(s.stage)
                }
            }

            is DictationUiState.Done -> {
                if (s.outcome.engine == Engine.LOCAL) {
                    LocalResultCard(s.outcome)
                } else {
                    s.outcome.response?.let { ResultCard(it, s.roundTripMs ?: s.outcome.elapsedMs) }
                }
                TextButton(onClick = viewModel::reset) { Text("Record another") }
            }

            is DictationUiState.Failed -> {
                FailureCard(code = s.code, message = s.message)
                TextButton(onClick = viewModel::reset) { Text("Back") }
            }
        }

        if (!micGranted) {
            Text(
                "Microphone permission is not granted. Android only shows the dialog twice — after " +
                    "that, grant it from the app's system settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // SPIKE ONLY (branch spike/geniex-npu, D-029): opens the NPU probe. Delete with the spike.
        TextButton(
            onClick = {
                context.startActivity(
                    android.content.Intent(context, fi.ak.smartvoice.spike.NpuProbeActivity::class.java),
                )
            },
        ) { Text("NPU probe (spike, D-029)") }
    }
}

/**
 * Engine choice and model state. Both tiers are first-class here: picking local without the model
 * downloaded is allowed, and the summary line then says out loud that the next utterance will go
 * to the server instead (D-027's fallback, made visible).
 */
@Composable
private fun EngineSection(
    preference: EnginePreference,
    summary: String,
    modelStatus: ModelStatus,
    loadProbe: String?,
    modelBytes: Long,
    onPreferenceChange: (EnginePreference) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onProbe: () -> Unit,
) {
    Text("Engine", style = MaterialTheme.typography.titleMedium)

    EnginePreference.entries.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = preference == option, onClick = { onPreferenceChange(option) }),
        ) {
            RadioButton(selected = preference == option, onClick = { onPreferenceChange(option) })
            Text(option.label)
        }
    }
    Text("next utterance: $summary", style = MaterialTheme.typography.bodySmall)

    Text("On-device model", style = MaterialTheme.typography.titleSmall)
    Text(
        "Nemotron 3.5 streaming 0.6B int8, 560 ms chunks — %.0f MB, %d files.".format(
            modelBytes / 1_000_000f,
            fi.ak.smartvoice.asr.AsrModels.FILES.size,
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    when (modelStatus) {
        ModelStatus.Missing -> {
            Text(
                "Not downloaded. Nothing on the device can transcribe until it is, and the local " +
                    "tier will fall back to the server.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onDownload) { Text("Download model") }
        }

        is ModelStatus.Downloading -> {
            val p = modelStatus.progress
            Text(
                "%s — %.0f %% (file %d/%d)".format(p.fileName, p.overall * 100, p.fileIndex + 1, p.totalFiles),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { p.overall },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onCancelDownload) { Text("Cancel download") }
            Text(
                "Cancelling keeps what has already been fetched, so resuming does not start over.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ModelStatus.Installed -> {
            Text("Downloaded and verified (size + sha256 per file).", style = MaterialTheme.typography.bodySmall)
            loadProbe?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onProbe) { Text("Test load") }
                OutlinedButton(onClick = onDeleteModel) { Text("Delete model (~681 MB)") }
            }
            Text(
                "Test load runs the same code path the first dictation takes, and reports how long " +
                    "the cold load took (D-010).",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is ModelStatus.Failed -> {
            Text("Download failed: ${modelStatus.message}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onDownload) { Text("Retry download") }
        }
    }
}

@Composable
private fun ServerSection(
    url: String,
    status: ServerStatus,
    onUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text("Server URL (home PC, LAN or Tailscale)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onCheck) { Text("Check server") }
        Text(
            text = when (status) {
                ServerStatus.Unknown -> "not checked"
                is ServerStatus.Ready -> "ready — contract ${status.contractVersion}"
                is ServerStatus.VersionMismatch ->
                    "version mismatch: server says ${status.contractVersion}, app expects 1.0"
                is ServerStatus.Unreachable -> "unreachable — ${status.message}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RecordButton(micGranted: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(if (micGranted) "Start recording" else "Grant microphone and record")
    }
}

/**
 * The local tier's result. It deliberately does not reuse the server's card: there is no stage
 * list, no latency split and no corrections, because there is no second stage on the device yet
 * (D-024). Showing empty server fields would suggest a stage ran and found nothing to do.
 */
@Composable
private fun LocalResultCard(outcome: DictationOutcome) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Transcript (on-device)", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    outcome.text.ifBlank {
                        "(no text returned — if the language is pinned to something you were not " +
                            "speaking, the model returns nothing rather than guessing; use auto)"
                    },
                )
            }

            HorizontalDivider()

            Text(
                "engine: local · %.1f s of audio · tail (stop → final text) ${outcome.elapsedMs} ms".format(
                    outcome.audioSeconds,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(outcome.note, style = MaterialTheme.typography.bodySmall)
            Text(
                "No cleanup stage ran: the on-device LLM is not built yet (D-024), so this is the " +
                    "model's own text with its own punctuation.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ResultCard(result: TranscribeResponse, roundTripMs: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Transcript (server)", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(result.text.ifBlank { "(empty text returned)" })
            }

            HorizontalDivider()

            Text(
                "stages: ${result.stagesRun.joinToString(", ").ifBlank { "none reported" }}" +
                    " · language: ${result.detectedLanguage ?: "unknown"}" +
                    " · round trip: $roundTripMs ms",
                style = MaterialTheme.typography.bodySmall,
            )
            result.latencyMs?.let { l ->
                Text(
                    "server latency — stt ${l.sttMs()} ms" +
                        (l.cleanupMs()?.let { " · cleanup $it ms" } ?: " · cleanup not run") +
                        " · total ${l.totalMs()} ms",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (result.cleanupUnavailable) {
                // Degraded success per contract §2: say so, do not present it as a failure.
                Text(
                    "Cleanup was not applied — the server had no LLM available (warnings: cleanup_unavailable).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (result.corrections.isNotEmpty()) {
                Text("Corrections (${result.corrections.size})", style = MaterialTheme.typography.titleSmall)
                result.corrections.forEach { c ->
                    Text("“${c.from}” → “${c.to}”", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (result.warnings.isNotEmpty()) {
                Text("Warnings: ${result.warnings.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FailureCard(code: String, message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Failed — $code", style = MaterialTheme.typography.titleMedium)
            Text(message)
        }
    }
}
