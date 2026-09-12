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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.ak.smartvoice.cleanup.CachedModel
import fi.ak.smartvoice.cleanup.CleanupModel
import fi.ak.smartvoice.cleanup.CleanupModels
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.data.AppSettings
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
    val unloadOnScreenOff by viewModel.unloadOnScreenOff.collectAsStateWithLifecycle()
    val idleTimeoutMinutes by viewModel.modelIdleTimeoutMinutes.collectAsStateWithLifecycle()
    val enginePreference by viewModel.enginePreference.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val cleanupStatus by viewModel.cleanupStatus.collectAsStateWithLifecycle()
    val cleanupCache by viewModel.cleanupCache.collectAsStateWithLifecycle()
    val cleanupCases by viewModel.cleanupCases.collectAsStateWithLifecycle()
    val cleanupBusy by viewModel.cleanupBusy.collectAsStateWithLifecycle()
    val cleanupLoad by viewModel.cleanupLoad.collectAsStateWithLifecycle()
    val pauseField by viewModel.pauseField.collectAsStateWithLifecycle()
    val stableField by viewModel.stableField.collectAsStateWithLifecycle()
    val stopField by viewModel.stopField.collectAsStateWithLifecycle()
    val timingNote by viewModel.timingNote.collectAsStateWithLifecycle()
    val cleanupVariant by viewModel.cleanupVariant.collectAsStateWithLifecycle()
    val cleanupCustomUnit by viewModel.cleanupCustomUnit.collectAsStateWithLifecycle()
    val loadProbe by viewModel.loadProbe.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }

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
        Text(
            "Settings & Diagnostics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Text(
            "build ${buildVersion(context)} — engine preferences, timings, and live mic diagnostics",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        TimingSection(
            pause = pauseField,
            stable = stableField,
            stop = stopField,
            note = timingNote,
            onPause = viewModel::onPauseFieldChange,
            onStable = viewModel::onStableFieldChange,
            onStop = viewModel::onStopFieldChange,
        )

        HorizontalDivider()

        PowerManagementSection(
            unloadOnScreenOff = unloadOnScreenOff,
            idleTimeoutMinutes = idleTimeoutMinutes,
            onUnloadOnScreenOffChange = viewModel::onUnloadOnScreenOffChange,
            onIdleTimeoutMinutesChange = viewModel::onModelIdleTimeoutMinutesChange,
        )

        HorizontalDivider()

        CleanupSection(
            model = viewModel.cleanupModel,
            status = cleanupStatus,
            cache = cleanupCache,
            onPull = viewModel::pullCleanupModel,
            onCancel = viewModel::cancelCleanupPull,
            onDelete = viewModel::deleteCleanupModel,
            onDeleteCached = viewModel::deleteCachedModel,
            onRefresh = viewModel::refreshCleanupStatus,
        )

        HorizontalDivider()

        CleanupBenchSection(
            busy = cleanupBusy,
            load = cleanupLoad,
            cases = cleanupCases,
            variant = cleanupVariant,
            customUnit = cleanupCustomUnit,
            onRun = viewModel::runCleanupCases,
            onPrefixCheck = viewModel::checkPrefixReuse,
            onLoad = viewModel::loadCleanupModel,
            onUnload = viewModel::unloadCleanupModel,
            onVariantChange = viewModel::onCleanupVariantChange,
            onCustomUnitChange = viewModel::onCleanupCustomUnitChange,
            onCleanCustom = viewModel::cleanCustomUnit,
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

/**
 * The listener's timings (D-035), as settings rather than constants — because these are the numbers a
 * person tunes by _speaking_, and every adjustment used to cost an APK build, a hand-install and a
 * pasted report.
 *
 * They are three different questions and are labelled as such:
 * - **the unit pause** — when the transcript is cut into a unit and committed (the recogniser's own rule);
 * - **the fallback** — how long the text must be unchanged before the app overrules a silent recogniser;
 * - **the auto-stop** — how long a silence ends the whole recording, or `off`.
 */
@Composable
private fun TimingSection(
    pause: TimingField,
    stable: TimingField,
    stop: TimingField,
    note: String?,
    onPause: (String) -> Unit,
    onStable: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    Text("Listener timings", style = MaterialTheme.typography.titleMedium)
    Text(
        "Seconds, decimals welcome — a tunable number should not be limited to a few choices. All three " +
            "are measured, not on a fixed timer.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "The recogniser ends a unit when it hears silence (the first field) — and that is now the only " +
            "rule that can cut: nothing else ends a unit early, at any length of speech. The fallback is for when it " +
            "says nothing at all: it ends a unit once the audio has been quiet *and* the text has stopped " +
            "changing for that long — both, so a recogniser that is merely slow cannot chop a sentence. " +
            "The third field ends the recording after that much quiet; 0 means never.",
        style = MaterialTheme.typography.bodySmall,
    )

    TimingField(
        label = "Pause that ends a unit (s)",
        hint = "the recogniser's own rule, and the only one — changing it rebuilds the model; 0.2 min",
        field = pause,
        onChange = onPause,
    )
    TimingField(
        label = "Fallback: quiet audio for (s)",
        hint = "only when the recogniser reports no endpoint of its own; needs quiet audio too",
        field = stable,
        onChange = onStable,
    )
    TimingField(
        label = "Stop recording after silence (s)",
        hint = "0 = never stop on its own — the recording runs until you close it",
        field = stop,
        onChange = onStop,
    )
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun TimingField(
    label: String,
    hint: String,
    field: TimingField,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = field.text,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = {
            Text(if (field.invalid) "not a number of seconds — the previous value stays in force" else hint)
        },
        isError = field.invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The cleanup model's own download — the *second* download path, by decision rather than accident
 * (D-030): GenieX's model manager knows which files its runtime needs for a GGUF, while the ASR
 * model above stays on our own sha256-pinned downloader, which is the only thing that can promise
 * byte-exact artifacts.
 *
 * The stage that consumes this model is not wired into dictation yet, and the screen says so rather
 * than implying that installing it changes what the keyboard does.
 */
@Composable
private fun CleanupSection(
    model: CleanupModel,
    status: CleanupStatus,
    cache: List<CachedModel>,
    onPull: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDeleteCached: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Text("On-device cleanup model", style = MaterialTheme.typography.titleMedium)
    Text(
        "%s · %s · %s — pulled by the GenieX model manager and run on the Hexagon NPU (D-030).".format(
            model.label,
            model.precision,
            CleanupModels.sizeLabel(model.approximateBytes),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(model.note, style = MaterialTheme.typography.bodySmall)

    when (status) {
        CleanupStatus.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)

        is CleanupStatus.Absent -> {
            Text(
                "Not downloaded. The local tier still dictates without it and commits the " +
                    "recogniser's own text; cleanup is what this model is for.",
                style = MaterialTheme.typography.bodySmall,
            )
            status.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onPull) { Text("Download cleanup model") }
        }

        is CleanupStatus.Pulling -> {
            val p = status.progress
            Text(
                if (p.totalFiles > 0) {
                    "%s of %s — %.0f %% (%s, file %d of %d)".format(
                        CleanupModels.sizeLabel(p.downloadedBytes),
                        CleanupModels.sizeLabel(p.totalBytes),
                        p.overall * 100,
                        p.fileName.ifBlank { "…" },
                        p.fileIndex + 1,
                        p.totalFiles,
                    )
                } else {
                    "starting…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { p.overall }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("Cancel download") }
            Text(
                "Cancelling keeps what the SDK has already fetched, so a later download resumes.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is CleanupStatus.Installed -> {
            Text(
                "Downloaded — %s on disk, all of it managed by the SDK's model manager.".format(
                    CleanupModels.sizeLabel(status.bytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("Re-check") }
                OutlinedButton(onClick = onDelete) { Text("Delete cleanup model") }
            }
            Text(
                "Not feeding dictation yet — the cleanup stage inside the streaming pipeline is the " +
                    "next slice (D-031), so nothing dictated today is cleaned by this model.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is CleanupStatus.Failed -> {
            Text("Cleanup model: ${status.message}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPull) { Text("Retry download") }
                OutlinedButton(onClick = onRefresh) { Text("Re-check") }
            }
        }
    }

    if (cache.isNotEmpty()) {
        Text("GenieX model cache", style = MaterialTheme.typography.titleSmall)
        cache.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "%s — %s%s".format(
                        entry.id,
                        CleanupModels.sizeLabel(entry.bytes),
                        if (entry.inCatalogue) "" else " (not in this app's catalogue)",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onDeleteCached(entry.id) }) { Text("Delete") }
            }
        }
        Text(
            "Everything the SDK's model manager holds, each size measured from that model's own files " +
                "— a leftover from the NPU spike shows up here so it can be reclaimed, and the sizes " +
                "add up to what the models actually cost on the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The cleanup test bench. Two questions could not be answered from documentation and were too
 * valuable to leave to the first real dictation: **does Gemma clean Finnish**, and **does the carried
 * context do anything**. Each case's cleaned output becomes the next case's context, which is exactly
 * how the streaming pipeline will carry the tail (D-032).
 *
 * The prompt actually sent is shown, because a chat template that drops the system message would be
 * invisible otherwise — and this is the screen where that has to be visible.
 */
@Composable
private fun CleanupBenchSection(
    busy: String?,
    load: String?,
    cases: List<CleanupCase>,
    variant: CleanupPrompt.Variant,
    customUnit: String,
    onRun: () -> Unit,
    onPrefixCheck: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onVariantChange: (CleanupPrompt.Variant) -> Unit,
    onCustomUnitChange: (String) -> Unit,
    onCleanCustom: () -> Unit,
) {
    Text("Cleanup test bench", style = MaterialTheme.typography.titleMedium)
    Text(
        "Runs the real prompt through the real runtime on this phone and reports the SDK's own " +
            "timings. Nothing here changes dictation — it is a bench, not the pipeline.",
        style = MaterialTheme.typography.bodySmall,
    )

    // The rules are the variable worth comparing: the first Finnish run came back unchanged except for
    // one capital letter, which is a property of the wording rather than of the model.
    Text("Editing rules", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CleanupPrompt.Variant.entries.forEach { candidate ->
            FilterChip(
                selected = candidate == variant,
                onClick = { onVariantChange(candidate) },
                label = { Text(candidate.name.lowercase()) },
            )
        }
    }
    Text(variant.label, style = MaterialTheme.typography.bodySmall)

    OutlinedTextField(
        value = customUnit,
        onValueChange = onCustomUnitChange,
        label = { Text("Your own unit (Finnish or English)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCleanCustom, enabled = busy == null && customUnit.isNotBlank()) {
            Text("Clean this unit")
        }
        Text(
            "uses the cleaned text so far as context",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }

    load?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRun, enabled = busy == null) { Text("Run cases") }
        OutlinedButton(onClick = onPrefixCheck, enabled = busy == null) { Text("Prefix-cache check") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onLoad, enabled = busy == null) { Text("Load model") }
        OutlinedButton(onClick = onUnload, enabled = busy == null) { Text("Unload") }
    }

    busy?.let {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(it)
        }
    }

    cases.forEach { case ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(case.label, style = MaterialTheme.typography.titleSmall)
                Text("in:  ${case.input}", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text("out: ${case.output}")
                }
                Text(case.detail, style = MaterialTheme.typography.bodySmall)
                if (case.prompt.isNotBlank()) {
                    Text(
                        "prompt sent (first 400 chars): ${case.prompt.take(400)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
/**
 * The build name, read from the package manager rather than `BuildConfig` (this project disables it),
 * and shown because APKs are installed by hand here: "which build is on the phone?" has to be
 * answerable without adb. One round trip was already spent on that question.
 */
private fun buildVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull()
        ?: "unknown"

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
                "This diagnostics run does not clean: the cleanup stage is wired into the keyboard " +
                    "panel, where dictation actually happens (D-021/D-032). What you see here is the " +
                    "recogniser's own text and its timings, which is what this screen is for.",
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

@Composable
private fun PowerManagementSection(
    unloadOnScreenOff: Boolean,
    idleTimeoutMinutes: Int,
    onUnloadOnScreenOffChange: (Boolean) -> Unit,
    onIdleTimeoutMinutesChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Power & Memory Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Automatically unloads ~4 GB of neural models (ASR and LLM) from RAM/NPU to prevent background battery drain and keep the phone cool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Unload on screen off", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "Immediately frees models when screen is locked or turned off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = unloadOnScreenOff,
                    onCheckedChange = onUnloadOnScreenOffChange,
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Auto-unload on inactivity",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Frees weights if no dictation occurs for the chosen time (reloads on next dictation)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppSettings.IDLE_TIMEOUT_OPTIONS.forEach { option ->
                        val selected = idleTimeoutMinutes == option
                        FilterChip(
                            selected = selected,
                            onClick = { onIdleTimeoutMinutesChange(option) },
                            label = {
                                Text(if (option == 0) "Never" else "${option}m")
                            },
                        )
                    }
                }
            }
        }
    }
}
