package io.github.alexeikramm.sovereign.ui.benchmark

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModelType
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModelSpec
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.ui.components.SettingsGroupCard
import io.github.alexeikramm.sovereign.ui.components.SettingsIconBadge
import io.github.alexeikramm.sovereign.ui.components.TopAppBarWithScrollShadow
import io.github.alexeikramm.sovereign.ui.components.asrModelTierTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The user-facing cleanup tier name, matching the Models screen. */
private val LiteRtLmModelSpec.tierTitle: String
    get() = if (id == LiteRtLmModels.GEMMA_4_E2B.id) "Good Polish" else label

private class SampleAudioPlayer(private val context: android.content.Context) {
    private var mediaPlayer: MediaPlayer? = null
    var isPlaying by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set

    fun playOrPause(assetPath: String) {
        val player = mediaPlayer
        if (player != null && isPlaying) {
            try {
                player.pause()
            } catch (_: Exception) {}
            isPlaying = false
            isPaused = true
            return
        }
        if (player != null && isPaused) {
            try {
                player.start()
                isPlaying = true
                isPaused = false
            } catch (_: Exception) {
                startFresh(assetPath)
            }
            return
        }
        startFresh(assetPath)
    }

    private fun startFresh(assetPath: String) {
        release()
        try {
            val newPlayer = MediaPlayer()
            var openedWithAfd = false
            try {
                val afd = context.assets.openFd(assetPath)
                newPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                openedWithAfd = true
            } catch (_: Exception) {}

            if (!openedWithAfd) {
                val cacheFile = File(context.cacheDir, "benchmark_audio_preview.wav")
                context.assets.open(assetPath).use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                newPlayer.setDataSource(cacheFile.absolutePath)
            }

            newPlayer.prepare()
            newPlayer.setOnCompletionListener {
                isPlaying = false
                isPaused = false
            }
            newPlayer.start()
            mediaPlayer = newPlayer
            isPlaying = true
            isPaused = false
        } catch (_: Exception) {
            release()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        release()
    }

    fun release() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlaying = false
        isPaused = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
) {
    if (onNavigateUp != null) {
        BackHandler { onNavigateUp() }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettings(context) }
    val audioPlayer = remember { SampleAudioPlayer(context) }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    // Benchmark Modes
    var selectedMode by remember { mutableStateOf(BenchmarkMode.FULL_PIPELINE) }

    // Installed Models
    var installedAsrModels by remember { mutableStateOf<List<AsrModelSpec>>(emptyList()) }
    var selectedAsrModel by remember { mutableStateOf<AsrModelSpec?>(null) }
    var installedLlmModels by remember { mutableStateOf<List<LiteRtLmModelSpec>>(emptyList()) }
    var selectedLlmModel by remember { mutableStateOf<LiteRtLmModelSpec?>(null) }

    // Audio Fixtures & Prompt
    var selectedAudioSample by remember { mutableStateOf(BenchmarkAudioSample.FIXTURES[0]) }
    var samplePromptText by remember {
        mutableStateOf(
            "Basically I was just thinking that we could maybe like test the model rewrite capability and see how it performs."
        )
    }

    // Execution Settings
    var selectedComputeUnit by remember { mutableStateOf("gpu") }
    var speculativeDecoding by remember { mutableStateOf(appSettings.litertSpeculativeDecoding) }
    var repetitions by remember { mutableIntStateOf(3) }
    var coldReloadEachRep by remember { mutableStateOf(false) }

    // Execution State
    var isRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var summaryResult by remember { mutableStateOf<BenchmarkSummary?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val asrList = withContext(Dispatchers.IO) {
            AsrModels.CATALOG.filter { it.isInstalled(it.directory(context.filesDir)) }
        }
        installedAsrModels = asrList
        selectedAsrModel = asrList.firstOrNull { it.id == appSettings.asrModelId } ?: asrList.firstOrNull()

        val llmList = withContext(Dispatchers.IO) {
            LiteRtLmModels.ALL.filter { it.isInstalled(context) }
        }
        installedLlmModels = llmList
        val initialLlm = llmList.firstOrNull { it.id == appSettings.litertModelId } ?: llmList.firstOrNull()
        selectedLlmModel = initialLlm
        if (initialLlm?.supportsGpu == false) {
            selectedComputeUnit = "cpu"
        }
    }

    fun startBenchmark() {
        if (isRunning) return
        if (selectedMode != BenchmarkMode.LLM_ONLY && selectedAsrModel == null) {
            errorMessage = "Please download or select an ASR model first."
            return
        }
        if (selectedMode != BenchmarkMode.ASR_ONLY && selectedLlmModel == null) {
            errorMessage = "Please download or select an LLM cleanup model first."
            return
        }

        audioPlayer.stop()
        isRunning = true
        errorMessage = null
        summaryResult = null
        progressFraction = 0f
        statusText = "Initializing benchmark..."

        val config = BenchmarkConfig(
            mode = selectedMode,
            asrModel = selectedAsrModel,
            llmModel = selectedLlmModel,
            audioSample = selectedAudioSample,
            textPrompt = samplePromptText,
            computeTarget = selectedComputeUnit,
            speculativeDecoding = speculativeDecoding,
            repetitions = repetitions,
            coldReloadEachRep = coldReloadEachRep,
        )

        scope.launch {
            try {
                val engine = BenchmarkEngine(context)
                val summary = engine.run(config) { prog, status ->
                    scope.launch(Dispatchers.Main) {
                        progressFraction = prog
                        statusText = status
                    }
                }
                summaryResult = summary
            } catch (t: Throwable) {
                errorMessage = t.message ?: t.toString()
            } finally {
                isRunning = false
                progressFraction = 0f
                statusText = ""
            }
        }
    }

    val content = @Composable { contentPadding: PaddingValues ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onNavigateUp == null) {
                item {
                    Column {
                        Text(
                            text = "Model Benchmarks",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Measure on-device speech recognition, LLM cleanup throughput, and RAM.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ================= UNIFIED BENCHMARK RUNNER CARD =================
            item {
                SettingsGroupCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Default.Speed,
                                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                contentDescription = "Benchmark Runner",
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Unified Pipeline Benchmark",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Standard fixtures • Statistical throughput • RAM tracking",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // 3-WAY MODE SELECTOR
                        Text(
                            text = "Benchmark Mode:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BenchmarkMode.entries.forEach { mode ->
                                val isSelected = selectedMode == mode
                                val shortLabel = when (mode) {
                                    BenchmarkMode.FULL_PIPELINE -> "Full Pipeline"
                                    BenchmarkMode.ASR_ONLY -> "ASR Only"
                                    BenchmarkMode.LLM_ONLY -> "LLM Only"
                                }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedMode = mode },
                                    label = { Text(shortLabel) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )

                        // DYNAMIC CONTROLS: AUDIO FIXTURE (for Pipeline & ASR)
                        if (selectedMode != BenchmarkMode.LLM_ONLY) {
                            AudioFixtureDropdownSelector(
                                selectedFixture = selectedAudioSample,
                                onFixtureSelected = { selectedAudioSample = it },
                                audioPlayer = audioPlayer,
                            )
                            AsrModelDropdownSelector(
                                label = "Select ASR Model:",
                                installedModels = installedAsrModels,
                                selectedModel = selectedAsrModel,
                                onModelSelected = { selectedAsrModel = it },
                            )
                        }

                        // DYNAMIC CONTROLS: LLM & PROMPT (for Pipeline & LLM)
                        if (selectedMode != BenchmarkMode.ASR_ONLY) {
                            LlmModelDropdownSelector(
                                label = "Select LLM Cleanup Model:",
                                installedModels = installedLlmModels,
                                selectedModel = selectedLlmModel,
                                onModelSelected = { m ->
                                    selectedLlmModel = m
                                    if (!m.supportsGpu) selectedComputeUnit = "cpu"
                                },
                            )

                            // Compute Target
                            val supportsGpu = selectedLlmModel?.supportsGpu ?: true
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (supportsGpu) "Compute Target:" else "Compute Target (CPU Only):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (supportsGpu) {
                                        FilterChip(
                                            selected = selectedComputeUnit == "gpu",
                                            onClick = { selectedComputeUnit = "gpu" },
                                            label = { Text("GPU") },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ),
                                        )
                                    }
                                    FilterChip(
                                        selected = selectedComputeUnit == "cpu",
                                        onClick = { selectedComputeUnit = "cpu" },
                                        label = { Text("CPU") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }

                            // Speculative Decoding (MTP)
                            val modelSupportsMtp = selectedLlmModel?.supportsSpeculativeDecoding == true
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        "Speculative Decoding (MTP)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        if (modelSupportsMtp) "Drafting heads for parallel candidate tokens" else "Unsupported by this model",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (modelSupportsMtp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Switch(
                                    checked = speculativeDecoding && modelSupportsMtp,
                                    enabled = modelSupportsMtp,
                                    onCheckedChange = { speculativeDecoding = it },
                                )
                            }
                        }

                        // LLM ONLY PROMPT SELECTOR
                        if (selectedMode == BenchmarkMode.LLM_ONLY) {
                            Text(
                                text = "Sample Text Source:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BenchmarkAudioSample.FIXTURES.forEach { fixture ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            samplePromptText = BenchmarkAudioSample.readGroundTruth(context, fixture)
                                        },
                                        label = { Text(fixture.name.substringBefore(" ")) },
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = samplePromptText,
                                onValueChange = { samplePromptText = it },
                                label = { Text("Input Text for LLM Cleanup") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                ),
                            )
                        }

                        // COMMON SETTINGS: REPETITIONS
                        RepetitionsDropdownSelector(
                            repetitions = repetitions,
                            onRepetitionsSelected = { repetitions = it },
                        )

                        // COLD RELOAD EACH REP SWITCH
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    "Reload Models Each Rep (Cold Benchmark)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    if (coldReloadEachRep) {
                                        "Measures fresh model disk load variance across all reps"
                                    } else {
                                        "1 cold load recorded, followed by warm steady-state reps"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Switch(
                                checked = coldReloadEachRep,
                                onCheckedChange = { coldReloadEachRep = it },
                            )
                        }

                        // ACTION BUTTON
                        Button(
                            onClick = { startBenchmark() },
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp).width(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(statusText.ifEmpty { "Benchmarking..." })
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run Benchmark", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (isRunning && progressFraction > 0f) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        errorMessage?.let { msg ->
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // ================= SUMMARY RESULTS CARD =================
            summaryResult?.let { summary ->
                item {
                    SettingsGroupCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Benchmark Summary (${summary.repsCount} reps • ${if (summary.coldReloadEachRep) "Cold" else "Warm"})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            // TOP-LEVEL PIPELINE OVERVIEW (When running Full Pipeline)
                            if (summary.mode == BenchmarkMode.FULL_PIPELINE) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                SectionHeader("Pipeline Overview")
                                ResultRow(
                                    "End-to-End Latency:",
                                    "%.1f ± %.1f ms".format(Locale.US, summary.e2eWallStat.mean, summary.e2eWallStat.stdDev),
                                )
                            }

                            // 1) MODEL LOAD TIMES SECTION
                            val showAsrLoad = (summary.coldReloadEachRep && summary.mode != BenchmarkMode.LLM_ONLY) ||
                                    (!summary.coldReloadEachRep && summary.initialAsrColdLoadMs > 0)
                            val showLlmLoad = (summary.coldReloadEachRep && summary.mode != BenchmarkMode.ASR_ONLY) ||
                                    (!summary.coldReloadEachRep && summary.initialLlmColdLoadMs > 0)

                            if (showAsrLoad || showLlmLoad) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                SectionHeader("1. Model Load Times")

                                if (summary.coldReloadEachRep) {
                                    if (summary.mode != BenchmarkMode.LLM_ONLY) {
                                        ResultRow("ASR Cold Load:", "%.1f ± %.1f ms".format(Locale.US, summary.asrColdLoadStat.mean, summary.asrColdLoadStat.stdDev))
                                    }
                                    if (summary.mode != BenchmarkMode.ASR_ONLY) {
                                        ResultRow("LLM Cold Load:", "%.1f ± %.1f ms".format(Locale.US, summary.llmColdLoadStat.mean, summary.llmColdLoadStat.stdDev))
                                    }
                                } else {
                                    if (summary.initialAsrColdLoadMs > 0) {
                                        ResultRow("ASR Cold Load:", "${summary.initialAsrColdLoadMs} ms")
                                    }
                                    if (summary.initialLlmColdLoadMs > 0) {
                                        ResultRow("LLM Cold Load:", "${summary.initialLlmColdLoadMs} ms")
                                    }
                                }
                            }

                            // 2) ASR SECTION (Speed, RTF, Raw Accuracy)
                            if (summary.mode != BenchmarkMode.LLM_ONLY) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                SectionHeader("2. ASR Performance (Speech → Text)")

                                ResultRow(
                                    "ASR Wall Time:",
                                    "%.1f ± %.1f ms".format(Locale.US, summary.asrWallStat.mean, summary.asrWallStat.stdDev),
                                )
                                val speedup = if (summary.asrRtfStat.mean > 0.0) 1.0 / summary.asrRtfStat.mean else 0.0
                                ResultRow(
                                    "Real-Time Factor (RTF):",
                                    "%.3f (%.1fx realtime)".format(Locale.US, summary.asrRtfStat.mean, speedup),
                                )
                                if (summary.groundTruth.isNotBlank()) {
                                    ResultRow(
                                        "Raw ASR Accuracy:",
                                        "WER %.1f%% | CER %.1f%%".format(Locale.US, summary.rawWer * 100, summary.rawCer * 100),
                                    )
                                }
                            }

                            // 3) LLM SECTION (Speed, Latency, Decode, Cleaned Accuracy)
                            if (summary.mode != BenchmarkMode.ASR_ONLY) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                SectionHeader(
                                    if (summary.mode == BenchmarkMode.FULL_PIPELINE) "3. LLM Cleanup Performance" else "2. LLM Cleanup Performance",
                                )

                                ResultRow(
                                    "Time to First Token (TTFT):",
                                    "%.1f ± %.1f ms".format(Locale.US, summary.llmTtftStat.mean, summary.llmTtftStat.stdDev),
                                )
                                if (summary.llmPrefillStat.mean > 0.0) {
                                    ResultRow(
                                        "Prefill Speed:",
                                        "%.1f tok/s".format(Locale.US, summary.llmPrefillStat.mean),
                                    )
                                }
                                ResultRow(
                                    "Decode Speed:",
                                    "%.1f ± %.1f tok/s".format(Locale.US, summary.llmDecodeStat.mean, summary.llmDecodeStat.stdDev),
                                )
                                ResultRow(
                                    "LLM Wall Time:",
                                    "%.1f ± %.1f ms".format(Locale.US, summary.llmWallStat.mean, summary.llmWallStat.stdDev),
                                )
                                if (summary.mode == BenchmarkMode.FULL_PIPELINE && summary.groundTruth.isNotBlank()) {
                                    ResultRow(
                                        "Cleaned Accuracy:",
                                        "WER %.1f%% | CER %.1f%%".format(Locale.US, summary.cleanedWer * 100, summary.cleanedCer * 100),
                                        valueColor = if (summary.cleanedWer <= summary.rawWer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    val werDelta = (summary.rawWer - summary.cleanedWer) * 100
                                    ResultRow(
                                        "Accuracy Delta vs Raw:",
                                        if (werDelta >= 0) "+%.1f%% gain".format(Locale.US, werDelta) else "%.1f%%".format(Locale.US, werDelta),
                                        valueColor = if (werDelta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            // CSV NOTE
                            Text(
                                "Saved to filesDir/benchmarks.csv and logged to Logcat (tag: SovereignBenchmark).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                // TRANSCRIPTION DIFF COMPARISON CARD (Replacing redundant repetition cards)
                item {
                    val displayHypothesis = if (summary.mode == BenchmarkMode.ASR_ONLY) {
                        summary.rawAsrText
                    } else {
                        summary.cleanedText.ifBlank { summary.rawAsrText }
                    }
                    TranscriptionDiffCard(
                        groundTruth = summary.groundTruth,
                        hypothesisText = displayHypothesis,
                        mode = summary.mode,
                    )
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val isScrolled = scrollBehavior.state.overlappedFraction > 0.01f

    if (onNavigateUp != null) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBarWithScrollShadow(scrollBehavior = scrollBehavior) {
                    TopAppBar(
                        title = { Text("Model Benchmarks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
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
}

@Composable
private fun ResultRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
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
            color = valueColor,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun AudioFixtureDropdownSelector(
    selectedFixture: BenchmarkAudioSample,
    onFixtureSelected: (BenchmarkAudioSample) -> Unit,
    audioPlayer: SampleAudioPlayer,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Benchmark Audio Sample:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedCard(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        cardWidthDp = with(density) { coordinates.size.width.toDp() }
                    },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = { audioPlayer.playOrPause(selectedFixture.assetWavPath) },
                        modifier = Modifier
                            .height(36.dp)
                            .width(36.dp),
                    ) {
                        Icon(
                            imageVector = if (audioPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (audioPlayer.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedFixture.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            selectedFixture.durationDesc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Audio Fixture",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(if (cardWidthDp > 0.dp) cardWidthDp else 280.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                BenchmarkAudioSample.FIXTURES.forEach { sample ->
                    val isSelected = sample.id == selectedFixture.id
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    sample.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    sample.durationDesc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onFixtureSelected(sample)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RepetitionsDropdownSelector(
    repetitions: Int,
    onRepetitionsSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val repOptions = listOf(
        1 to "1 run (Quick test)",
        3 to "3 runs (Fast average)",
        5 to "5 runs (Standard benchmark)",
        10 to "10 runs (Thermal check)",
        20 to "20 runs (Deep stress test)",
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Benchmark Repetitions:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedCard(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        cardWidthDp = with(density) { coordinates.size.width.toDp() }
                    },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val currentDesc = repOptions.firstOrNull { it.first == repetitions }?.second ?: "$repetitions runs"
                        Text(
                            "$repetitions ${if (repetitions == 1) "run" else "runs"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(currentDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select repetitions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(if (cardWidthDp > 0.dp) cardWidthDp else 280.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                repOptions.forEach { (count, desc) ->
                    val isSelected = count == repetitions
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "$count ${if (count == 1) "run" else "runs"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            onRepetitionsSelected(count)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AsrModelDropdownSelector(
    label: String,
    installedModels: List<AsrModelSpec>,
    selectedModel: AsrModelSpec?,
    onModelSelected: (AsrModelSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (installedModels.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
            ) {
                Text(
                    text = "No ASR models are downloaded. Please download an ASR model in Models page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedCard(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            cardWidthDp = with(density) { coordinates.size.width.toDp() }
                        },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                selectedModel?.let { asrModelTierTitle(it.id, it.label) } ?: "Select an ASR model",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val badge = when (selectedModel?.type) {
                                AsrModelType.STREAMING_TRANSDUCER -> "Streaming Transducer"
                                AsrModelType.OFFLINE_WHISPER -> "Offline Whisper"
                                AsrModelType.OFFLINE_TRANSDUCER -> "Offline Transducer (TDT)"
                                AsrModelType.OFFLINE_CTC -> "Offline CTC"
                                null -> ""
                            }
                            Text(badge, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select ASR model", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .width(if (cardWidthDp > 0.dp) cardWidthDp else 280.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    installedModels.forEach { model ->
                        val isSelected = model.id == selectedModel?.id
                        val badge = when (model.type) {
                            AsrModelType.STREAMING_TRANSDUCER -> "Streaming Transducer"
                            AsrModelType.OFFLINE_WHISPER -> "Offline Whisper"
                            AsrModelType.OFFLINE_TRANSDUCER -> "Offline Transducer (TDT)"
                            AsrModelType.OFFLINE_CTC -> "Offline CTC"
                        }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        asrModelTierTitle(model.id, model.label),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                onModelSelected(model)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmModelDropdownSelector(
    label: String,
    installedModels: List<LiteRtLmModelSpec>,
    selectedModel: LiteRtLmModelSpec?,
    onModelSelected: (LiteRtLmModelSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (installedModels.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
            ) {
                Text(
                    text = "No LLM models are downloaded. Please download an LLM model in Models page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedCard(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            cardWidthDp = with(density) { coordinates.size.width.toDp() }
                        },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                selectedModel?.tierTitle ?: "Select an LLM model",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val badge = if (selectedModel?.supportsGpu == true) "LiteRT • GPU/CPU" else "LiteRT • CPU Only"
                            Text(badge, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select LLM model", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .width(if (cardWidthDp > 0.dp) cardWidthDp else 280.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    installedModels.forEach { item ->
                        val isSelected = item.id == selectedModel?.id
                        val badge = if (item.supportsGpu) "GPU/CPU" else "CPU Only"
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.tierTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        ) {
                                            Text(
                                                badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        "${item.approximateBytes / 1_000_000} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onModelSelected(item)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionDiffCard(
    groundTruth: String,
    hypothesisText: String,
    mode: BenchmarkMode,
) {
    SettingsGroupCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (mode == BenchmarkMode.ASR_ONLY) "ASR Output vs Ground Truth" else "Cleaned Output vs Ground Truth",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (groundTruth.isNotBlank()) {
                val diff = remember(groundTruth, hypothesisText) {
                    WerCalculator.computeDiff(groundTruth, hypothesisText)
                }
                val errorCount = diff.count { it.op != DiffOp.MATCH }

                if (errorCount == 0) {
                    Text(
                        text = "✓ Perfect Match (100% accurate)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    val substitutions = diff.count { it.op == DiffOp.SUBSTITUTION }
                    val deletions = diff.count { it.op == DiffOp.DELETION }
                    val insertions = diff.count { it.op == DiffOp.INSERTION }
                    Text(
                        text = "Differences: $substitutions substitutions, $deletions missing, $insertions extra",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                // Reference (Ground Truth) display
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        "Expected (Ground Truth):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        groundTruth,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Annotated Cleaned / Output Text
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        if (mode == BenchmarkMode.ASR_ONLY) "Actual ASR Output (with corrections):" else "Actual Cleaned Output (with corrections):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val errorColor = MaterialTheme.colorScheme.error
                    val successColor = MaterialTheme.colorScheme.primary
                    val annotatedText = remember(diff, errorColor, successColor) {
                        buildAnnotatedString {
                            diff.forEach { item ->
                                when (item.op) {
                                    DiffOp.MATCH -> {
                                        append(item.text)
                                        append(" ")
                                    }
                                    DiffOp.SUBSTITUTION -> {
                                        append("[")
                                        withStyle(
                                            SpanStyle(
                                                color = errorColor,
                                                textDecoration = TextDecoration.LineThrough,
                                            )
                                        ) {
                                            append(item.text)
                                        }
                                        append(" → ")
                                        withStyle(
                                            SpanStyle(
                                                color = successColor,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        ) {
                                            append(item.expected ?: "")
                                        }
                                        append("] ")
                                    }
                                    DiffOp.DELETION -> {
                                        append("[+ ")
                                        withStyle(
                                            SpanStyle(
                                                color = successColor,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        ) {
                                            append(item.expected ?: "")
                                        }
                                        append("] ")
                                    }
                                    DiffOp.INSERTION -> {
                                        append("[")
                                        withStyle(
                                            SpanStyle(
                                                color = errorColor,
                                                textDecoration = TextDecoration.LineThrough,
                                            )
                                        ) {
                                            append(item.text)
                                        }
                                        append("] ")
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                if (hypothesisText.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            "Model Output:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            hypothesisText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
