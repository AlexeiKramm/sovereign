package io.github.alexeikramm.sovereign.ui.cleanuplab

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModelStore
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.CleaningProfile
import io.github.alexeikramm.sovereign.data.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * The Cleanup Lab's screen. It configures one cleanup cell — fixture, recogniser, model, `maxTurns`,
 * seam healing, profile — runs the real pipeline over it, and shows the result against the
 * **context-window position** rather than the transcript position.
 *
 * Three actions, matching the engine: **Build units** (Phase A), **Run cleanup** (Phase B, cached
 * units — the fast apples-to-apples sweep), and **Full replay** (audio → cleaned, latency included).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupLabScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
) {
    if (onNavigateUp != null) BackHandler { onNavigateUp() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    val engine = remember { CleanupLabEngine(context) }
    val fixtures = remember { CleanupLabFixtures.all(context) }
    val profiles = remember { ProfileStore(context).allProfiles() }
    val asrModels = remember {
        AsrModels.CATALOG.filter { it.isInstalled(it.directory(context.filesDir)) }
    }
    val llmModels = remember { LiteRtLmModels.ALL.filter { it.isInstalled(context) } }

    var fixtureId by remember { mutableStateOf(fixtures.first().id) }
    var asrModelId by remember { mutableStateOf(asrModels.firstOrNull()?.id.orEmpty()) }
    var llmModelId by remember { mutableStateOf(llmModels.firstOrNull()?.id.orEmpty()) }
    var profileId by remember { mutableStateOf(profiles.first().id) }
    var maxTurns by remember { mutableIntStateOf(0) }
    var healing by remember { mutableIntStateOf(3) }

    var unitSet by remember { mutableStateOf<LabUnitSet?>(null) }
    var runs by remember { mutableStateOf<List<LabRunResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<LabProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun launch(block: suspend () -> Unit) {
        if (running) return
        running = true
        error = null
        progress = null
        job = scope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("CleanupLab", "run failed", e)
                error = e.message ?: "Run failed"
            } finally {
                running = false
                job = null
            }
        }
    }

    fun fixture(): LabFixture = fixtures.first { it.id == fixtureId }
    fun asr(): AsrModelSpec = asrModels.first { it.id == asrModelId }

    fun config(pauseSeconds: Float): LabConfig {
        val spec = llmModels.firstOrNull { it.id == llmModelId } ?: llmModels.first()
        val profile: CleaningProfile = profiles.firstOrNull { it.id == profileId } ?: profiles.first()
        return LabConfig(
            fixtureId = fixture().id,
            fixtureName = fixture().name,
            asrModelId = asrModelId,
            pauseSeconds = pauseSeconds,
            llmModelId = spec.id,
            llmLabel = spec.label,
            maxTurns = maxTurns,
            healingSentences = healing,
            profileId = profile.id,
            profileName = profile.name,
            rules = profile.rules,
        )
    }

    val fixtureReady = fixtures.firstOrNull { it.id == fixtureId }?.exists(context) == true

    fun buildUnitsThenRun(full: Boolean) {
        // Snapshot every selection up front so changing a chip mid-run cannot record a config the run
        // did not actually use, or pair Phase-A units from one fixture with another's id.
        val fixture = fixture()
        val asrModel = asr()
        val pause = settings.pauseSilenceSeconds
        val runConfig = config(pause)
        launch {
            val set = unitSet ?: engine.buildUnitSet(fixture, asrModel, pause) { progress = it }
                .also { unitSet = it }
            val result = if (full) {
                engine.runFullReplay(fixture, asrModel, pause, runConfig) { progress = it }
            } else {
                engine.runCleanup(set, runConfig) { progress = it }
            }
            runs = listOf(result) + runs
            CleanupLabCsv.append(context, result)
            progress = LabProgress("Done", 1f, CleanupLabMetrics.describe(result.metrics))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Cleanup Lab", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onNavigateUp != null) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        ChipGroup("Fixture") {
                            fixtures.forEach { f ->
                                FilterChip(
                                    selected = f.id == fixtureId,
                                    onClick = { fixtureId = f.id; unitSet = null },
                                    enabled = f.exists(context),
                                    label = { Text(if (f.exists(context)) f.name else "${f.name} (not staged)") },
                                )
                            }
                        }
                        ChipGroup("Recogniser") {
                            if (asrModels.isEmpty()) {
                                Text("No ASR model installed.", style = MaterialTheme.typography.bodySmall)
                            }
                            asrModels.forEach { m ->
                                FilterChip(
                                    selected = m.id == asrModelId,
                                    onClick = { asrModelId = m.id; unitSet = null },
                                    label = { Text(m.shortLabel) },
                                )
                            }
                        }
                        ChipGroup("Cleanup model") {
                            if (llmModels.isEmpty()) {
                                Text("No LiteRT-LM model installed.", style = MaterialTheme.typography.bodySmall)
                            }
                            llmModels.forEach { m ->
                                FilterChip(
                                    selected = m.id == llmModelId,
                                    onClick = { llmModelId = m.id },
                                    label = { Text(m.label) },
                                )
                            }
                        }
                        ChipGroup("Profile") {
                            profiles.forEach { p ->
                                FilterChip(
                                    selected = p.id == profileId,
                                    onClick = { profileId = p.id },
                                    label = { Text(p.name) },
                                )
                            }
                        }
                        ChipGroup("maxTurns (0 = no turn limit)") {
                            listOf(0, 1, 2, 3, 4, 6, 8).forEach { n ->
                                FilterChip(
                                    selected = n == maxTurns,
                                    onClick = { maxTurns = n },
                                    label = { Text(if (n == 0) "off" else n.toString()) },
                                )
                            }
                        }
                        ChipGroup("Seam healing (sentences)") {
                            (0..5).forEach { n ->
                                FilterChip(
                                    selected = n == healing,
                                    onClick = { healing = n },
                                    label = { Text(n.toString()) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val fixture = fixture()
                            val asrModel = asr()
                            val pause = settings.pauseSilenceSeconds
                            launch {
                                progress = LabProgress("Preparing", 0f, "Phase A")
                                unitSet = engine.buildUnitSet(fixture, asrModel, pause) { progress = it }
                                progress = LabProgress("Done", 1f, "${unitSet?.units?.size ?: 0} units cached")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        enabled = !running && fixtureReady && asrModels.isNotEmpty(),
                    ) { Text("Build units (Phase A)", fontWeight = FontWeight.SemiBold) }

                    Button(
                        onClick = { buildUnitsThenRun(full = false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        enabled = !running && fixtureReady && llmModels.isNotEmpty() && asrModels.isNotEmpty(),
                    ) { Text("Run cleanup on cached units (Phase B)", fontWeight = FontWeight.SemiBold) }

                    OutlinedButton(
                        onClick = { buildUnitsThenRun(full = true) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        enabled = !running && fixtureReady && llmModels.isNotEmpty() && asrModels.isNotEmpty(),
                    ) { Text("Full replay (audio → cleaned)", fontWeight = FontWeight.SemiBold) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { share(context, CleanupLabCsv.runsFile(context), "cleanup_lab_runs.csv") },
                            enabled = runs.isNotEmpty(),
                        ) { Text("Share runs CSV") }
                        OutlinedButton(
                            onClick = { share(context, CleanupLabCsv.unitsFile(context), "cleanup_lab_units.csv") },
                            enabled = CleanupLabCsv.unitsFile(context) != null,
                        ) { Text("Share units CSV") }
                    }
                    TextButton(onClick = { runs = emptyList() }, enabled = runs.isNotEmpty() && !running) {
                        Text("Clear results")
                    }

                    Text(
                        text = unitSet?.let { "Cached: ${it.units.size} units · ${it.asrModelId} · pause ${it.pauseSeconds}s" }
                            ?: "No cached units yet — Phase B will build them first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (running) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = progress?.let { "${it.phase} — ${it.detail}" } ?: "Starting…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(
                            progress = { progress?.fraction ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (runs.isNotEmpty()) {
                item {
                    Text("Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            itemsIndexed(runs, key = { index, run -> "$index:${run.config.label}:${run.wallMs}" }) { _, result ->
                RunCard(result)
            }
        }
    }
}

@Composable
private fun ChipGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun RunCard(result: LabRunResult) {
    var expanded by remember { mutableStateOf(false) }
    val m = result.metrics
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(result.config.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(CleanupLabMetrics.describe(m), style = MaterialTheme.typography.bodySmall)
            Text(
                text = "units ${m.totalUnits} · raw ${m.rawWords} → cleaned ${m.cleanedWords} words · " +
                    "wall ${"%.1f".format(java.util.Locale.US, result.wallMs / 1000.0)}s · " +
                    "asr ${result.asrActiveMs}ms · llm ${result.llmActiveMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide units" else "Show units (${result.units.size})")
            }
            if (expanded) {
                result.units.take(40).forEach { unit ->
                    HorizontalDivider()
                    UnitRow(unit)
                }
                if (result.units.size > 40) {
                    Text(
                        "… ${result.units.size - 40} more — share the units CSV for the full table.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (result.cleanedText.isNotBlank()) {
                Text("Cleaned text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(result.cleanedText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UnitRow(unit: LabUnitResult) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val flags = buildString {
            append("#${unit.index} · ctx ${unit.tokensBefore}→${unit.tokensAfter} tok · turn ${unit.turnsInConversation}")
            if (unit.rebuiltAfter) append(" · rebuild ${unit.rebuildReason}")
            if (!unit.cleanedOk) append(" · FAILED")
            append(" · ${unit.activeMs}ms")
        }
        Text(flags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Text("raw: ${unit.rawUnit}", style = MaterialTheme.typography.bodySmall)
        Text("out: ${unit.cleanedUnit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

private fun share(context: android.content.Context, file: File?, subject: String) {
    if (file == null) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share $subject"))
}
