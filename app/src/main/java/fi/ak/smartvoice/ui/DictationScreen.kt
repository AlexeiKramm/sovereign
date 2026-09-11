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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.net.TranscribeResponse

@Composable
fun DictationScreen(
    modifier: Modifier = Modifier,
    viewModel: DictationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
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
            "Chunk 0: record → POST /v1/transcribe → show what came back. Server tier only.",
            style = MaterialTheme.typography.bodySmall,
        )

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
                Text("Recording — %.1f s of 16 kHz mono audio".format(s.seconds))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::stopAndTranscribe) { Text("Stop and transcribe") }
                    OutlinedButton(onClick = viewModel::cancelRecording) { Text("Cancel") }
                }
                Text(
                    "Cancel discards the audio and commits nothing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is DictationUiState.Uploading -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Uploading %.1f s of audio, cleaned on the PC…".format(s.audioSeconds))
                }
            }

            is DictationUiState.Done -> {
                ResultCard(result = s.result, roundTripMs = s.roundTripMs)
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
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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

@Composable
private fun ResultCard(result: TranscribeResponse, roundTripMs: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(result.text.ifBlank { "(empty text returned)" })
            }

            HorizontalDivider()

            Text(
                "stages: ${result.stagesRun.joinToString(", ").ifBlank { "none reported" }}" +
                    " · language: ${result.detectedLanguage ?: "unknown"}" +
                    " · round trip: ${roundTripMs} ms",
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
