package io.github.alexeikramm.sovereign.ui.benchmark

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.ui.components.InlineDiffMode
import io.github.alexeikramm.sovereign.ui.components.InlineDiffText
import io.github.alexeikramm.sovereign.ui.components.SettingsGroupCard
import io.github.alexeikramm.sovereign.ui.components.SettingsIconBadge
import io.github.alexeikramm.sovereign.ui.components.TopAppBarWithScrollShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The streaming dictation harness (SB-4b). For each selected matrix cell it streams a dictation
 * fixture through the real `DictationPipeline` at 1× and reports the felt latencies — dead air,
 * cleanup lag, tail latency — rather than the batch benchmark's isolated model speeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingBenchmarkScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
) {
    if (onNavigateUp != null) {
        BackHandler { onNavigateUp() }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installedModels by remember { mutableStateOf<List<AsrModelSpec>>(emptyList()) }
    var selectedModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFixture by remember { mutableStateOf(DictationFixtures.FIXTURES.first()) }
    // Every matrix axis is a multi-select set, so "the whole matrix" and "one cell at a time" are the
    // same code path: a run is the cross-product of whatever is selected.
    var selectedPauses by remember { mutableStateOf(StreamingMatrix.PAUSES.toSet()) }
    var selectedHealings by remember { mutableStateOf(StreamingMatrix.HEALINGS.toSet()) }
    var selectedTemps by remember { mutableStateOf(setOf(false, true)) }
    var cleanupOn by remember { mutableStateOf(true) }
    var cleanupOff by remember { mutableStateOf(true) }

    var isRunning by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var currentCell by remember { mutableStateOf<StreamingCell?>(null) }
    var currentProgress by remember { mutableStateOf<StreamingProgress?>(null) }
    var completedCells by remember { mutableIntStateOf(0) }
    var runStartedAtMs by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var results by remember { mutableStateOf<List<StreamingCellResult>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    // The cell whose raw/cleaned diff is open in the inspector; null = the run table.
    var inspected by remember { mutableStateOf<StreamingCellResult?>(null) }

    /** Restore the whole matrix: every installed model, all pauses and healing windows, cold+warm. */
    fun selectFullMatrix() {
        selectedModelIds = installedModels.map { it.id }.toSet()
        selectedPauses = StreamingMatrix.PAUSES.toSet()
        selectedHealings = StreamingMatrix.HEALINGS.toSet()
        selectedTemps = setOf(false, true)
        cleanupOn = true
        cleanupOff = true
    }

    LaunchedEffect(Unit) {
        installedModels = withContext(Dispatchers.IO) {
            AsrModels.CATALOG.filter { it.isInstalled(it.directory(context.filesDir)) }
        }
        selectFullMatrix()
        // Review results across app restarts: the CSV is the source of truth, not this run's memory.
        val previous = withContext(Dispatchers.IO) {
            StreamingCsv.file(context)?.readText()?.let { StreamingCsv.parse(it) }
        }
        if (!previous.isNullOrEmpty()) results = previous
    }

    val cells = remember(installedModels, selectedModelIds, selectedPauses, selectedHealings, selectedTemps, cleanupOn, cleanupOff) {
        val models = installedModels.filter { it.id in selectedModelIds }
        val cleanupOptions = buildList {
            if (cleanupOn) add(true)
            if (cleanupOff) add(false)
        }
        StreamingMatrix.cells(
            models = models,
            cleanupOptions = cleanupOptions,
            pauses = selectedPauses.sorted(),
            healings = selectedHealings.sorted(),
            temps = selectedTemps.sorted(),
        )
    }

    fun startRun() {
        if (isRunning || cells.isEmpty()) return
        isRunning = true
        errorMessage = null
        results = emptyList()
        completedCells = 0
        currentCell = null
        currentProgress = null
        runStartedAtMs = SystemClock.elapsedRealtime()
        elapsedMs = 0L
        runJob = scope.launch {
            val engine = StreamingBenchmarkEngine(context)
            val collected = mutableListOf<StreamingCellResult>()
            try {
                cells.forEachIndexed { index, cell ->
                    currentCell = cell
                    currentProgress = StreamingProgress(StreamingPhase.PREPARING_AUDIO)
                    val result = engine.runCell(cell, selectedFixture) { progress -> currentProgress = progress }
                    StreamingCsv.append(context, result)
                    collected.add(result)
                    results = collected.toList()
                    completedCells = index + 1
                }
                currentCell = null
                currentProgress = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StreamingBenchmark", "Streaming run failed", e)
                errorMessage = e.message ?: "Run failed"
            } finally {
                isRunning = false
                runJob = null
            }
        }
    }

    /** Hand the accumulated CSV to any app via the system share sheet (no adb needed). */
    fun shareCsv() {
        val file = StreamingCsv.file(context)
        if (file == null) {
            errorMessage = "No CSV yet — run at least one cell first."
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Streaming benchmark CSV")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share benchmark CSV"))
    }

    // A half-second tick so the elapsed time and ETA move while a cell runs.
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - runStartedAtMs
            delay(500L)
        }
    }

    // Keep the display awake for an unattended run, so a long matrix is not throttled by screen sleep.
    val view = LocalView.current
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }

    val withinCell = currentProgress?.let { StreamingMetrics.cellFraction(it) } ?: 0f
    val overall = if (cells.isEmpty()) 0f else ((completedCells + withinCell) / cells.size).coerceIn(0f, 1f)
    val etaMs = if (completedCells > 0) {
        val avgPerCell = elapsedMs.toFloat() / completedCells
        (avgPerCell * (cells.size - completedCells) - withinCell * avgPerCell).coerceAtLeast(0f).toLong()
    } else {
        0L
    }

    val content: @Composable (PaddingValues) -> Unit = { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onNavigateUp == null) {
                item {
                    Column {
                        Text(
                            text = "Streaming Benchmark",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Real-time dictation through the live pipeline: dead air, lag, tail latency.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SettingsGroupCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Default.Speed,
                                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                contentDescription = "Streaming benchmark",
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Real-Time Harness",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${cells.size} cells · 1× pacing · ${selectedFixture.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { showInfo = true }) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "What these measurements mean",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Label("Audio sample")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DictationFixtures.FIXTURES.take(4).forEach { fixture ->
                                FilterChip(
                                    selected = fixture.id == selectedFixture.id,
                                    onClick = { selectedFixture = fixture },
                                    label = { Text(fixture.id.substringBefore('_')) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DictationFixtures.FIXTURES.drop(4).forEach { fixture ->
                                FilterChip(
                                    selected = fixture.id == selectedFixture.id,
                                    onClick = { selectedFixture = fixture },
                                    label = { Text(fixture.id.substringBefore('_')) },
                                )
                            }
                        }

                        Label("Transcription model")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            installedModels.forEach { model ->
                                FilterChip(
                                    selected = model.id in selectedModelIds,
                                    onClick = { selectedModelIds = selectedModelIds.toggled(model.id) },
                                    label = { Text(model.shortLabel) },
                                )
                            }
                        }
                        if (installedModels.isEmpty()) {
                            Text(
                                text = "No ASR models installed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Label("Pause sensitivity")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StreamingMatrix.PAUSES.forEach { pause ->
                                FilterChip(
                                    selected = pause in selectedPauses,
                                    onClick = { selectedPauses = selectedPauses.toggled(pause) },
                                    label = { Text("${pause}s") },
                                )
                            }
                        }

                        Label("Seam healing (cleanup on only)")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StreamingMatrix.HEALINGS.forEach { count ->
                                FilterChip(
                                    selected = count in selectedHealings,
                                    onClick = { selectedHealings = selectedHealings.toggled(count) },
                                    label = { Text("$count") },
                                )
                            }
                        }

                        Label("Engine state")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = false in selectedTemps,
                                onClick = { selectedTemps = selectedTemps.toggled(false) },
                                label = { Text("Warm") },
                            )
                            FilterChip(
                                selected = true in selectedTemps,
                                onClick = { selectedTemps = selectedTemps.toggled(true) },
                                label = { Text("Cold") },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Include cleanup-on cells", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = cleanupOn, onCheckedChange = { cleanupOn = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Include cleanup-off cells", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = cleanupOff, onCheckedChange = { cleanupOff = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${cells.size} cells selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { selectFullMatrix() }) { Text("Full matrix") }
                        }

                        if (isRunning) {
                            OutlinedButton(
                                onClick = { runJob?.cancel() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape,
                            ) {
                                Text("Stop run", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = { startRun() },
                                enabled = cells.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text("Run ${cells.size} cells", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        OutlinedButton(
                            onClick = { shareCsv() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                        ) {
                            Text("Share CSV", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (isRunning) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        currentCell?.let { cell ->
                            Text(
                                text = cell.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = currentProgress?.let { StreamingMetrics.describeProgress(it) } ?: "Starting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { overall },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val cellNumber = (completedCells + 1).coerceAtMost(cells.size).coerceAtLeast(1)
                        val eta = if (etaMs > 0L) " · ~${formatDuration(etaMs)} left" else ""
                        Text(
                            text = "Cell $cellNumber of ${cells.size} · ${formatDuration(elapsedMs)} elapsed$eta",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            errorMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            itemsIndexed(results, key = { index, result -> "$index:${result.cell.label}:${result.fixtureId}" }) { index, result ->
                StreamingResultCard(result, onClick = { inspected = result })
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    if (onNavigateUp != null) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBarWithScrollShadow(scrollBehavior = scrollBehavior) {
                    TopAppBar(
                        title = { Text("Streaming Benchmark", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onNavigateUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                        ),
                    )
                }
            },
        ) { innerPadding ->
            content(
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
            )
        }
    } else {
        content(PaddingValues(horizontal = 16.dp, vertical = 8.dp))
    }

    if (inspected != null) {
        CellInspectorDialog(
            result = inspected!!,
            onDismiss = { inspected = null },
            onPrev = { results.previousOf(inspected!!)?.let { inspected = it } },
            onNext = { results.nextOf(inspected!!)?.let { inspected = it } },
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("Got it") }
            },
            title = { Text("What these measurements mean") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoLine("Time to first text", "How long after the audio starts before anything appears.")
                    InfoLine("Dead air", "Words had already been recognised but the screen still showed nothing.")
                    InfoLine("Pause gating", "How much of the dead air is Pause sensitivity waiting for a pause before cutting a segment.")
                    InfoLine("Cleanup lag", "The extra time cleanup adds after a segment is cut, before it appears.")
                    InfoLine("Tail latency", "From when the audio stops to the final text — what hold-to-dictate feels like.")
                    InfoLine("Visible cadence", "The gaps between visible updates while speaking.")
                    InfoLine("Partial cadence", "How often the raw recognition text would refresh — what the live streaming preview would show.")
                    InfoLine("Tap a result", "Opens the raw / cleaned / diff view for that cell, with arrows to step through the run.")
                    InfoLine("Transcription time", "Active on-device recognition compute for the run.")
                    InfoLine("Cleanup time", "Active LLM cleanup compute for the run.")
                    InfoLine("Wall time", "Total run duration, including model loads.")
                    InfoLine("Cold / warm", "Cold loads the transcription model during the run, so a short utterance feels it. Warm loads it beforehand.")
                    InfoLine("Full matrix", "Selects every axis (all models, pauses, healing windows, cold and warm) in one tap. Deselect chips to run a single cell.")
                    InfoLine("Share CSV", "Exports every row from every run so far, so the results can be read without a cable.")
                }
            },
        )
    }
}

@Composable
private fun InfoLine(term: String, meaning: String) {
    Column {
        Text(
            text = term,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = meaning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StreamingResultCard(result: StreamingCellResult, onClick: () -> Unit) {
    SettingsGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.cell.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "View ▸",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "${result.fixtureId} · ${String.format(Locale.US, "%.2f", result.audioDurationSec)}s audio · ${result.unitCount} units",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            MetricRow("Time to first text", ms(result.timeToFirstTextMs))
            MetricRow("Dead air", ms(result.deadAirMs))
            MetricRow("Pause gating", ms(result.endpointGatingMs))
            MetricRow("Cleanup lag", ms(result.cleanupLagMs))
            MetricRow("Tail latency", ms(result.tailLatencyMs))
            MetricRow("Visible cadence", StreamingMetrics.describeCadence(result.visibleCadenceMs))
            MetricRow("Partial cadence", StreamingMetrics.describeCadence(result.partialCadenceMs))
            MetricRow("Transcription time", ms(result.asrActiveMs))
            MetricRow("Cleanup time", ms(result.llmActiveMs))
            MetricRow("Wall time", ms(result.wallMs))
        }
    }
}

/** The SB-5 inspector: raw / cleaned / inline diff for one cell, with arrows to step the run. */
@Composable
private fun CellInspectorDialog(
    result: StreamingCellResult,
    onDismiss: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    var mode by remember(result) { mutableStateOf(InlineDiffMode.DIFF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (mode == InlineDiffMode.DIFF) {
                TextButton(onClick = { mode = InlineDiffMode.CLEAN }) { Text("Cleaned") }
            } else {
                TextButton(onClick = { mode = InlineDiffMode.DIFF }) { Text("Diff") }
            }
        },
        title = {
            Text(
                text = result.cell.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${result.fixtureId} · ${String.format(Locale.US, "%.2f", result.audioDurationSec)}s · " +
                        "${result.unitCount} units" +
                        if (result.cell.cleanup) " · heal ${result.cell.healingSentences}" else " · raw only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (result.rawText.isBlank() && result.committedText.isBlank()) {
                    Text(
                        text = "No text was captured for this cell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LabelledText("Cleaned output", result.committedText.ifBlank { "(nothing committed)" })
                    if (result.cell.cleanup && result.rawText.isNotBlank()) {
                        LabelledText("Raw recognition")
                        InlineDiffText(
                            rawText = result.rawText,
                            cleanedText = result.committedText,
                            mode = mode,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onPrev) { Text("◀ Previous") }
                    TextButton(onClick = onNext) { Text("Next ▶") }
                }
            }
        },
    )
}

@Composable
private fun LabelledText(label: String, text: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        text?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun ms(value: Long): String = if (value < 0L) "—" else "$value ms"

/** `m:ss` for elapsed/ETA readouts. */
private fun formatDuration(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000L).coerceAtLeast(0L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/** Adds or removes one value, so a multi-select chip is a pure set operation. */
private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

/** Neighbours in the run, so the inspector can step through results in order. */
private fun List<StreamingCellResult>.nextOf(current: StreamingCellResult): StreamingCellResult? {
    val index = indexOf(current)
    return if (index in 0 until lastIndex) get(index + 1) else null
}

private fun List<StreamingCellResult>.previousOf(current: StreamingCellResult): StreamingCellResult? {
    val index = indexOf(current)
    return if (index > 0) get(index - 1) else null
}
