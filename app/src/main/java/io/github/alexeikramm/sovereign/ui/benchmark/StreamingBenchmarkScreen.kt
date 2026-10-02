package io.github.alexeikramm.sovereign.ui.benchmark

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.ui.components.SettingsGroupCard
import io.github.alexeikramm.sovereign.ui.components.SettingsIconBadge
import io.github.alexeikramm.sovereign.ui.components.TopAppBarWithScrollShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val appSettings = remember { AppSettings(context) }

    var installedModels by remember { mutableStateOf<List<AsrModelSpec>>(emptyList()) }
    var selectedModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFixture by remember { mutableStateOf(DictationFixtures.FIXTURES.first()) }
    var pauseSeconds by remember { mutableFloatStateOf(0.75f) }
    var healing by remember { mutableIntStateOf(3) }
    var cleanupOn by remember { mutableStateOf(true) }
    var cleanupOff by remember { mutableStateOf(false) }

    var isRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var results by remember { mutableStateOf<List<StreamingCellResult>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val models = withContext(Dispatchers.IO) {
            AsrModels.CATALOG.filter { it.isInstalled(it.directory(context.filesDir)) }
        }
        installedModels = models
        val preferred = models.firstOrNull { it.id == appSettings.asrModelId } ?: models.firstOrNull()
        selectedModelIds = setOfNotNull(preferred?.id)
    }

    val cells = remember(installedModels, selectedModelIds, pauseSeconds, healing, cleanupOn, cleanupOff) {
        val models = installedModels.filter { it.id in selectedModelIds }
        val cleanupOptions = buildList {
            if (cleanupOn) add(true)
            if (cleanupOff) add(false)
        }
        StreamingMatrix.cells(models, cleanupOptions, listOf(pauseSeconds), listOf(healing))
    }

    fun startRun() {
        if (isRunning || cells.isEmpty()) return
        isRunning = true
        errorMessage = null
        results = emptyList()
        progressFraction = 0f
        statusText = "Starting…"
        scope.launch {
            val engine = StreamingBenchmarkEngine(context)
            val collected = mutableListOf<StreamingCellResult>()
            try {
                cells.forEachIndexed { index, cell ->
                    statusText = "Cell ${index + 1}/${cells.size}: ${cell.label}"
                    val result = engine.runCell(cell, selectedFixture) { note -> statusText = note }
                    StreamingCsvLogger.log(context, result)
                    collected.add(result)
                    results = collected.toList()
                    progressFraction = (index + 1).toFloat() / cells.size
                }
                statusText = "Complete — ${cells.size} cells"
            } catch (e: Exception) {
                Log.e("StreamingBenchmark", "Streaming run failed", e)
                errorMessage = e.message ?: "Run failed"
            } finally {
                isRunning = false
            }
        }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            installedModels.forEach { model ->
                                val selected = model.id in selectedModelIds
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedModelIds = if (selected) selectedModelIds - model.id else selectedModelIds + model.id
                                    },
                                    label = { Text(model.label.substringBefore(' ')) },
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StreamingMatrix.PAUSES.forEach { pause ->
                                FilterChip(
                                    selected = pause == pauseSeconds,
                                    onClick = { pauseSeconds = pause },
                                    label = { Text("${pause}s") },
                                )
                            }
                        }

                        Label("Seam healing (cleanup on only)")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StreamingMatrix.HEALINGS.forEach { count ->
                                FilterChip(
                                    selected = count == healing,
                                    onClick = { healing = count },
                                    label = { Text("$count") },
                                )
                            }
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

                        Button(
                            onClick = { startRun() },
                            enabled = !isRunning && cells.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.primary,
                                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp).width(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Running…")
                            } else {
                                Text("Run ${cells.size} cells", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (isRunning) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            items(results, key = { it.cell.label + it.fixtureId }) { result ->
                StreamingResultCard(result)
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
                    InfoLine("Transcription time", "Active on-device recognition compute for the run.")
                    InfoLine("Cleanup time", "Active LLM cleanup compute for the run.")
                    InfoLine("Wall time", "Total run duration, including model loads.")
                    InfoLine("Cold / warm", "Cold loads the transcription model during the run, so a short utterance feels it. Warm loads it beforehand.")
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
private fun StreamingResultCard(result: StreamingCellResult) {
    SettingsGroupCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = result.cell.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
            MetricRow("Transcription time", ms(result.asrActiveMs))
            MetricRow("Cleanup time", ms(result.llmActiveMs))
            MetricRow("Wall time", ms(result.wallMs))
            if (result.committedText.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                Text(
                    text = result.committedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
