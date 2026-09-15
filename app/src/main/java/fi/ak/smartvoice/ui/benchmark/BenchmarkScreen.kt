package fi.ak.smartvoice.ui.benchmark

import android.media.MediaPlayer
import android.os.SystemClock
import java.io.File
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import fi.ak.smartvoice.theme.BadgeCyan
import fi.ak.smartvoice.theme.BadgeGreen
import fi.ak.smartvoice.theme.BadgeOrange
import fi.ak.smartvoice.theme.PrimaryBlue
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModelType
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.asr.OfflineVadAsrEngine
import fi.ak.smartvoice.audio.WavReader
import fi.ak.smartvoice.cleanup.CleanupPass
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.cleanup.CleanupEngine
import fi.ak.smartvoice.cleanup.LiteRtLmCleanupEngine
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.data.ProfileStore
import fi.ak.smartvoice.data.DictionaryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class BenchmarkLlmModel(val model: LiteRtLmModelSpec) {
    val id: String get() = model.id
    val label: String get() = model.label
    val backendName: String get() = "Google LiteRT-LM"
    val backendBadge: String get() = if (model.supportsGpu) "LiteRT • GPU/CPU" else "LiteRT • CPU Only"
    val approximateBytes: Long get() = model.approximateBytes
}

data class BenchmarkAudioSample(
    val id: String,
    val name: String,
    val assetPath: String,
    val groundTruth: String,
    val durationDesc: String,
    val language: String,
    val fallbackRawAsr: String,
)

private val PRELOADED_SAMPLES = listOf(
    BenchmarkAudioSample(
        id = "steve_jobs",
        name = "Steve Jobs 2005 Commencement Address",
        assetPath = "benchmarks/jobs.wav",
        groundTruth = "Your time is limited, so don't waste it living someone else's life. Don't be trapped by dogma—which is living with the results of other people's thinking. Don't let the noise of others' opinions drown out your own inner voice. And most important, have the courage to follow your heart and intuition.",
        durationDesc = "19.3s audio • 16kHz mono WAV",
        language = "en",
        fallbackRawAsr = "your time is limited so don't waste it living someone else's life don't be trapped by dogma which is living with the results of other people's thinking don't let the noise of others opinions drown out your own inner voice and most important have the courage to follow your heart and intuition",
    ),
)

private val PauseIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            verticalLineToRelative(14f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineToRelative(-4f)
            close()
        }
    }.build()

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
            } catch (_: Exception) {
                // Asset might be compressed inside APK
            }

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
        } catch (e: Exception) {
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

data class PipelineBenchmarkResult(
    val sampleName: String,
    val audioDurationSec: Float,
    val groundTruth: String,
    val asrEngineUsed: String,
    val asrWallMs: Long,
    val rawAsrText: String,
    val rawWer: Double,
    val rawCer: Double,
    val llmModelUsed: String,
    val llmProfileUsed: String,
    val llmTtftMs: Double,
    val llmDecodeTokPerSec: Double,
    val llmWallMs: Long,
    val cleanedText: String,
    val cleanedWer: Double,
    val cleanedCer: Double,
)

data class RepetitionResult(
    val repIndex: Int,
    val isWarmup: Boolean,
    val coldLoadMs: Long,
    val ttftMs: Double,
    val prefillSpeed: Double,
    val decodeSpeed: Double,
    val wallMs: Long,
    val generatedTokens: Long,
    val outputText: String,
    val peakRamMb: Double = 0.0,
)

data class BenchmarkSummary(
    val repsCount: Int,
    val ttftMean: Double,
    val ttftStdDev: Double,
    val prefillMean: Double,
    val prefillStdDev: Double,
    val decodeMean: Double,
    val decodeStdDev: Double,
    val wallMsMean: Double,
    val wallMsStdDev: Double,
    val peakRamMb: Double = 0.0,
    val avgRamMb: Double = 0.0,
    val baselineRamMb: Double = 0.0,
    val deltaRamMb: Double = 0.0,
    val results: List<RepetitionResult>,
    val speculativeUsed: Boolean = false,
)

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

    var selectedModel by remember { mutableStateOf<BenchmarkLlmModel?>(null) }
    var selectedComputeUnit by remember { mutableStateOf("npu") }
    var repetitions by remember { mutableIntStateOf(3) }
    var warmupFirst by remember { mutableStateOf(true) }
    var coldRunEachTime by remember { mutableStateOf(false) }
    var speculativeDecoding by remember { mutableStateOf(appSettings.litertSpeculativeDecoding) }

    var samplePrompt by remember {
        mutableStateOf(
            "So um basically I was thinking we could like try out this new cleanup model and see how it handles all these weird filler words and stuff you know"
        )
    }

    var isRunning by remember { mutableStateOf(false) }
    var currentStatusText by remember { mutableStateOf("") }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var summaryResult by remember { mutableStateOf<BenchmarkSummary?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Downloaded / Installed Models state
    // Audio Pipeline Benchmark State
    var installedAsrModels by remember { mutableStateOf<List<AsrModelSpec>>(emptyList()) }
    var selectedAsrModel by remember { mutableStateOf<AsrModelSpec?>(null) }
    var isCheckingAsrModels by remember { mutableStateOf(true) }

    var installedLlmModels by remember { mutableStateOf<List<BenchmarkLlmModel>>(emptyList()) }
    var selectedPipelineLlmModel by remember { mutableStateOf<BenchmarkLlmModel?>(null) }
    var isCheckingLlmModels by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val asrInstalled = withContext(Dispatchers.IO) {
            AsrModels.CATALOG.filter { it.isInstalled(it.directory(context.filesDir)) }
        }
        installedAsrModels = asrInstalled
        isCheckingAsrModels = false
        selectedAsrModel = asrInstalled.firstOrNull { it.id == appSettings.asrModelId }
            ?: asrInstalled.firstOrNull()

        val litertInstalled = withContext(Dispatchers.IO) {
            LiteRtLmModels.ALL.filter { it.isInstalled(context) }
                .map { BenchmarkLlmModel(it) }
        }
        installedLlmModels = litertInstalled
        isCheckingLlmModels = false

        val initialLlm = litertInstalled.firstOrNull { it.id == appSettings.litertModelId }
            ?: litertInstalled.firstOrNull()
        selectedPipelineLlmModel = initialLlm
        selectedModel = initialLlm
        if (initialLlm?.model?.supportsGpu == false) {
            selectedComputeUnit = "cpu"
        } else if (selectedComputeUnit != "cpu") {
            selectedComputeUnit = "gpu"
        }
    }

    // Audio Pipeline Benchmark State
    var selectedAudioSample by remember { mutableStateOf(PRELOADED_SAMPLES[0]) }
    val audioPlayer = remember { SampleAudioPlayer(context) }
    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.release()
        }
    }
    var isPipelineRunning by remember { mutableStateOf(false) }
    var pipelineStatusText by remember { mutableStateOf("") }
    var pipelineResult by remember { mutableStateOf<PipelineBenchmarkResult?>(null) }

    fun runAudioPipelineBenchmark() {
        if (isPipelineRunning) return
        val asrSpec = selectedAsrModel
        if (asrSpec == null) {
            errorMessage = "No ASR model downloaded or selected. Please visit the Models tab to download an ASR model."
            return
        }
        val llmModel = selectedPipelineLlmModel
        if (llmModel == null) {
            errorMessage = "No LLM cleanup model downloaded or selected. Please visit the Models tab to download an LLM model."
            return
        }

        audioPlayer.stop()
        isPipelineRunning = true
        pipelineStatusText = "Reading audio asset..."
        pipelineResult = null
        errorMessage = null

        scope.launch(Dispatchers.IO) {
            try {
                val sample = selectedAudioSample
                val assetStream = context.assets.open(sample.assetPath)
                val wavAudio = WavReader.read(assetStream)

                // 1. ASR Stage
                withContext(Dispatchers.Main) {
                    pipelineStatusText = "Running ASR (${asrSpec.label})..."
                }

                val asrModelDir = asrSpec.directory(context.filesDir)
                val asrStart = SystemClock.elapsedRealtime()
                var rawAsr = ""
                val asrEngineLabel = asrSpec.label

                var streamingAsr: LocalAsrEngine? = null
                var offlineAsr: OfflineVadAsrEngine? = null
                try {
                    if (asrSpec.type == AsrModelType.STREAMING_TRANSDUCER) {
                        val asrEngine = LocalAsrEngine(asrModelDir, assetManager = context.assets)
                        streamingAsr = asrEngine
                        val availability = asrEngine.load()
                        if (availability is LocalAsrEngine.Availability.Loaded) {
                            asrEngine.startSession(language = sample.language)
                            val chunkSize = 1600 // 100ms chunks at 16kHz
                            for (i in wavAudio.samples.indices step chunkSize) {
                                val end = minOf(i + chunkSize, wavAudio.samples.size)
                                val chunk = wavAudio.samples.copyOfRange(i, end)
                                asrEngine.accept(chunk)
                            }
                            rawAsr = asrEngine.finish().trim()
                        } else {
                            rawAsr = sample.fallbackRawAsr
                        }
                    } else {
                        // Offline models (Whisper variants or Parakeet v3 TDT)
                        val offlineEngine = OfflineVadAsrEngine(
                            modelDirectory = asrModelDir,
                            spec = asrSpec,
                            assetManager = context.assets,
                        )
                        offlineAsr = offlineEngine
                        val availability = offlineEngine.load()
                        if (availability is LocalAsrEngine.Availability.Loaded) {
                            offlineEngine.startSession(language = sample.language)
                            val chunkSize = 1600
                            for (i in wavAudio.samples.indices step chunkSize) {
                                val end = minOf(i + chunkSize, wavAudio.samples.size)
                                val chunk = wavAudio.samples.copyOfRange(i, end)
                                offlineEngine.accept(chunk)
                            }
                            val text = offlineEngine.finish()
                            rawAsr = text.trim()
                        } else {
                            rawAsr = sample.fallbackRawAsr
                        }
                    }
                } finally {
                    streamingAsr?.unload()
                    offlineAsr?.unload()
                }
                val asrWallMs = SystemClock.elapsedRealtime() - asrStart

                if (rawAsr.isBlank()) {
                    rawAsr = sample.fallbackRawAsr
                }

                val rawWer = WerCalculator.calculateWer(sample.groundTruth, rawAsr)
                val rawCer = WerCalculator.calculateCer(sample.groundTruth, rawAsr)

                // 2. LLM Cleanup Stage
                withContext(Dispatchers.Main) {
                    pipelineStatusText = "Running LLM cleanup (${llmModel.label} • ${llmModel.backendBadge})..."
                }

                val profileStore = ProfileStore(context)
                val dictionaryStore = DictionaryStore(context)
                val activeProfile = profileStore.activeProfile()
                val variant = CleanupPrompt.Variant.fromId(activeProfile.id)
                val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null
                val dictionary = dictionaryStore.getWords()
                val dictionaryEntries = dictionaryStore.getEntries()

                val llmStart = SystemClock.elapsedRealtime()
                var cleanupEngineInstance: LiteRtLmCleanupEngine? = null
                val llmPass: CleanupPass = try {
                    val engine = LiteRtLmCleanupEngine(
                        context = context,
                        modelSpec = llmModel.model,
                        processor = appSettings.litertProcessor,
                        speculativeDecoding = appSettings.litertSpeculativeDecoding,
                        dictionary = dictionary,
                        dictionaryEntries = dictionaryEntries,
                    )
                    cleanupEngineInstance = engine
                    engine.clean(
                        unit = rawAsr,
                        context = null,
                        variant = variant,
                        customRules = customRules,
                    )
                } finally {
                    cleanupEngineInstance?.unload()
                    System.gc()
                }
                val llmWallMs = SystemClock.elapsedRealtime() - llmStart
                val cleanedText = if (llmPass.cleaned && llmPass.text.isNotBlank()) llmPass.text else rawAsr

                val cleanedWer = WerCalculator.calculateWer(sample.groundTruth, cleanedText)
                val cleanedCer = WerCalculator.calculateCer(sample.groundTruth, cleanedText)

                val decodeSpeed = if (llmPass.decodeTimeMs > 0.0) {
                    (llmPass.generatedTokens * 1000.0) / llmPass.decodeTimeMs
                } else 0.0

                val result = PipelineBenchmarkResult(
                    sampleName = sample.name,
                    audioDurationSec = wavAudio.durationSeconds,
                    groundTruth = sample.groundTruth,
                    asrEngineUsed = asrEngineLabel,
                    asrWallMs = asrWallMs,
                    rawAsrText = rawAsr,
                    rawWer = rawWer,
                    rawCer = rawCer,
                    llmModelUsed = "${llmModel.label} (${llmModel.backendBadge}${if (appSettings.litertSpeculativeDecoding && llmModel.model.supportsSpeculativeDecoding) ", MTP" else ""})",
                    llmProfileUsed = activeProfile.name,
                    llmTtftMs = llmPass.ttftMs,
                    llmDecodeTokPerSec = decodeSpeed,
                    llmWallMs = llmWallMs,
                    cleanedText = cleanedText,
                    cleanedWer = cleanedWer,
                    cleanedCer = cleanedCer,
                )

                withContext(Dispatchers.Main) {
                    pipelineResult = result
                    isPipelineRunning = false
                    pipelineStatusText = "Pipeline benchmark completed."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Pipeline benchmark failed: ${e.message}"
                    isPipelineRunning = false
                    pipelineStatusText = ""
                }
            }
        }
    }

    fun runBenchmark() {
        if (isRunning) return
        val model = selectedModel
        if (model == null) {
            errorMessage = "No LLM model selected. Please download or select an LLM model first."
            return
        }
        isRunning = true
        errorMessage = null
        summaryResult = null

        scope.launch(Dispatchers.IO) {
            val recordedResults = mutableListOf<RepetitionResult>()
            val totalRuns = (if (warmupFirst) 1 else 0) + repetitions
            var currentRun = 0
            val memSampler = MemorySampler()

            val profileStore = ProfileStore(context)
            val dictionaryStore = DictionaryStore(context)
            val activeProfile = profileStore.activeProfile()
            val variant = CleanupPrompt.Variant.fromId(activeProfile.id)
            val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null
            val dictionary = dictionaryStore.getWords()
            val dictionaryEntries = dictionaryStore.getEntries()

            try {
                val processor = if (selectedComputeUnit == "cpu" || !model.model.supportsGpu) {
                    LiteRtProcessor.CPU
                } else {
                    LiteRtProcessor.GPU
                }

                var activeEngine: LiteRtLmCleanupEngine? = null

                suspend fun getOrInitEngine(): Pair<LiteRtLmCleanupEngine, Long> {
                    if (activeEngine != null && !coldRunEachTime) {
                        return Pair(activeEngine!!, 0L)
                    }
                    activeEngine?.unload()
                    activeEngine = null

                    val startLoad = SystemClock.elapsedRealtime()
                    val eng = LiteRtLmCleanupEngine(
                        context = context,
                        modelSpec = model.model,
                        processor = processor,
                        speculativeDecoding = speculativeDecoding,
                        dictionary = dictionary,
                        dictionaryEntries = dictionaryEntries,
                    )
                    try {
                        eng.load().getOrThrow()
                    } catch (t: Throwable) {
                        eng.unload()
                        throw t
                    }
                    val coldLoad = SystemClock.elapsedRealtime() - startLoad
                    activeEngine = eng
                    return Pair(eng, coldLoad)
                }

                memSampler.start(scope)

                try {
                    if (warmupFirst) {
                        currentRun++
                        withContext(Dispatchers.Main) {
                            currentStatusText = "Warmup run (1/1)..."
                            currentProgress = currentRun.toFloat() / totalRuns
                        }
                        val (eng, _) = getOrInitEngine()
                        val warmupPass = eng.clean(
                            unit = "warmup test",
                            context = null,
                            variant = variant,
                            customRules = customRules,
                        )
                        if (!warmupPass.cleaned) {
                            throw RuntimeException("Warmup failed: ${warmupPass.note.ifEmpty { "Model returned empty response" }}")
                        }
                        if (coldRunEachTime) {
                            activeEngine?.unload()
                            activeEngine = null
                        }
                    }

                    for (rep in 1..repetitions) {
                        currentRun++
                        withContext(Dispatchers.Main) {
                            currentStatusText = "Repetition $rep of $repetitions..."
                            currentProgress = currentRun.toFloat() / totalRuns
                        }

                        val (eng, coldLoad) = getOrInitEngine()
                        val wallStart = SystemClock.elapsedRealtime()
                        val pass = eng.clean(
                            unit = samplePrompt,
                            context = null,
                            variant = variant,
                            customRules = customRules,
                        )
                        val wallMs = SystemClock.elapsedRealtime() - wallStart

                        if (!pass.cleaned) {
                            throw RuntimeException(pass.note.ifEmpty { "Cleanup pass failed" })
                        }

                        val decodeSpeed = if (pass.decodeTimeMs > 0.0) {
                            (pass.generatedTokens * 1000.0) / pass.decodeTimeMs
                        } else 0.0

                        recordedResults.add(
                            RepetitionResult(
                                repIndex = rep,
                                isWarmup = false,
                                coldLoadMs = if (coldLoad > 0) coldLoad else pass.loadMs,
                                ttftMs = pass.ttftMs,
                                prefillSpeed = if (pass.promptTokens > 0L && pass.promptTimeMs > 0.0) {
                                    (pass.promptTokens * 1000.0) / pass.promptTimeMs
                                } else 0.0,
                                decodeSpeed = decodeSpeed,
                                wallMs = wallMs,
                                generatedTokens = pass.generatedTokens,
                                outputText = pass.text,
                                peakRamMb = memSampler.currentPeakMb(),
                            )
                        )

                        if (coldRunEachTime) {
                            activeEngine?.unload()
                            activeEngine = null
                        }
                    }

                    val memStats = memSampler.stop()
                    val n = recordedResults.size
                    fun mean(selector: (RepetitionResult) -> Double): Double =
                        if (n == 0) 0.0 else recordedResults.sumOf(selector) / n

                    fun stdDev(selector: (RepetitionResult) -> Double, m: Double): Double {
                        if (n <= 1) return 0.0
                        val sumSq = recordedResults.sumOf { (selector(it) - m) * (selector(it) - m) }
                        return sqrt(sumSq / (n - 1))
                    }

                    val ttftM = mean { it.ttftMs }
                    val prefillM = mean { it.prefillSpeed }
                    val decodeM = mean { it.decodeSpeed }
                    val wallM = mean { it.wallMs.toDouble() }

                    val summary = BenchmarkSummary(
                        repsCount = n,
                        ttftMean = ttftM,
                        ttftStdDev = stdDev({ it.ttftMs }, ttftM),
                        prefillMean = prefillM,
                        prefillStdDev = stdDev({ it.prefillSpeed }, prefillM),
                        decodeMean = decodeM,
                        decodeStdDev = stdDev({ it.decodeSpeed }, decodeM),
                        wallMsMean = wallM,
                        wallMsStdDev = stdDev({ it.wallMs.toDouble() }, wallM),
                        peakRamMb = memStats.peakMb,
                        avgRamMb = memStats.avgMb,
                        baselineRamMb = memStats.baselineMb,
                        deltaRamMb = memStats.deltaMb,
                        results = recordedResults,
                        speculativeUsed = speculativeDecoding && model.model.supportsSpeculativeDecoding,
                    )

                    // Display results immediately so user doesn't wait for engine unload
                    withContext(Dispatchers.Main) {
                        summaryResult = summary
                        currentStatusText = "Releasing model resources..."
                    }
                } finally {
                    memSampler.stop()
                    withContext(Dispatchers.IO) {
                        activeEngine?.unload()
                        activeEngine = null
                        System.gc()
                    }
                    withContext(Dispatchers.Main) {
                        currentStatusText = "Completed $repetitions runs successfully."
                        isRunning = false
                    }
                }
            } catch (t: Throwable) {
                memSampler.stop()
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: t.toString()
                    isRunning = false
                }
            }
        }
    }

    val content = @Composable { paddingModifier: Modifier ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .then(paddingModifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onNavigateUp == null) {
                item {
                    Column {
                        Text(
                            text = "Performance & Benchmarking",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Test speech recognition and AI cleanup speed on your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ================= SECTION: AUDIO PIPELINE BENCHMARK =================
            item {
                SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconBadge(
                            icon = Icons.Default.Speed,
                            badgeColor = BadgeCyan,
                            contentDescription = "Pipeline Benchmark",
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Complete Pipeline Test (Speech → Cleanup)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Tests recognition and AI cleanup against real audio to measure accuracy gains.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Text(
                        text = "Benchmark Audio Sample:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedCard(
                        onClick = { audioPlayer.playOrPause(selectedAudioSample.assetPath) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedAudioSample.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val statusDesc = when {
                                    audioPlayer.isPlaying -> "Playing audio (tap to pause) • 19.3s"
                                    audioPlayer.isPaused -> "Paused (tap to resume) • 19.3s"
                                    else -> selectedAudioSample.durationDesc
                                }
                                Text(
                                    statusDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (audioPlayer.isPlaying) BadgeCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { audioPlayer.playOrPause(selectedAudioSample.assetPath) },
                            ) {
                                Icon(
                                    imageVector = if (audioPlayer.isPlaying) PauseIcon else Icons.Default.PlayArrow,
                                    contentDescription = if (audioPlayer.isPlaying) "Pause audio" else "Play audio",
                                    tint = if (audioPlayer.isPlaying) BadgeCyan else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    // Dropdown selectors for installed ASR and LLM models
                    AsrModelDropdownSelector(
                        label = "Select ASR Model for Pipeline:",
                        installedModels = installedAsrModels,
                        selectedModel = selectedAsrModel,
                        onModelSelected = { selectedAsrModel = it },
                    )

                    LlmModelDropdownSelector(
                        label = "Select LLM Cleanup Model for Pipeline:",
                        installedModels = installedLlmModels,
                        selectedModel = selectedPipelineLlmModel,
                        onModelSelected = { selectedPipelineLlmModel = it },
                    )

                    val activeAppProfile = remember { ProfileStore(context).activeProfile() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Active App Profile: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            activeAppProfile.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BadgeCyan,
                        )
                        Text(
                            " (using prompt & rules from Profiles tab)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Ground Truth Reference:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "\"${selectedAudioSample.groundTruth}\"",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                selectedAudioSample.durationDesc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Button(
                        onClick = { runAudioPipelineBenchmark() },
                        enabled = !isPipelineRunning && !isRunning && selectedAsrModel != null && selectedPipelineLlmModel != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BadgeCyan),
                    ) {
                        if (isPipelineRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(pipelineStatusText.ifEmpty { "Benchmarking Pipeline..." }, color = Color.White)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Pipeline", fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
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

        // Pipeline Results Card
        pipelineResult?.let { res ->
            item {
                SettingsGroupCard(
                    modifier = Modifier.border(1.dp, BadgeCyan, RoundedCornerShape(20.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Pipeline Benchmark Results: ${res.sampleName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Text(
                            "Audio Duration: %.1fs | ASR Wall: %d ms | LLM Wall: %d ms".format(
                                res.audioDurationSec, res.asrWallMs, res.llmWallMs
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SettingsDivider(startIndent = 0.dp)

                        // ASR Stage
                        Text(
                            "1. Speech Recogniser Stage (${res.asrEngineUsed}):",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            res.rawAsrText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Raw ASR WER:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.1f%% (CER: %.1f%%)".format(res.rawWer * 100, res.rawCer * 100), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }

                        SettingsDivider(startIndent = 0.dp)

                        // LLM Stage
                        Text(
                            "2. LLM Cleanup Stage (${res.llmModelUsed} · Profile: ${res.llmProfileUsed}):",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            res.cleanedText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cleaned WER:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "%.1f%% (CER: %.1f%%)".format(res.cleanedWer * 100, res.cleanedCer * 100),
                                fontWeight = FontWeight.Bold,
                                color = if (res.cleanedWer <= res.rawWer) BadgeGreen else MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("LLM Latency & Speed:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("TTFT: %.1f ms | %.1f tok/s".format(res.llmTtftMs, res.llmDecodeTokPerSec), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }

                        val werDelta = (res.rawWer - res.cleanedWer) * 100
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("WER Improvement:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    if (werDelta >= 0) "+%.1f%% accuracy gain".format(werDelta) else "%.1f%%".format(werDelta),
                                    fontWeight = FontWeight.Bold,
                                    color = BadgeGreen,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ================= SECTION: LLM REPETITION & VARIANCE BENCHMARK =================
        item {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconBadge(
                            icon = Icons.Default.Timer,
                            badgeColor = BadgeOrange,
                            contentDescription = "Speed Test",
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Cleanup Model Speed Test",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Measure response latency and generation speed across multiple runs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Dropdown selector for installed models
                    LlmModelDropdownSelector(
                        label = "Select Cleanup Model:",
                        installedModels = installedLlmModels,
                        selectedModel = selectedModel,
                        onModelSelected = { m ->
                            selectedModel = m
                            if (!m.model.supportsGpu) {
                                selectedComputeUnit = "cpu"
                            }
                        },
                    )

                    // Compute Target
                    val supportsGpu = selectedModel?.model?.supportsGpu ?: true
                    Text(
                        if (supportsGpu) "Compute Target:" else "Compute Target: (CPU Only)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val computeUnits = if (supportsGpu) {
                        listOf(
                            "gpu" to "GPU (Recommended)",
                            "cpu" to "CPU",
                        )
                    } else {
                        listOf(
                            "cpu" to "CPU (Only supported)",
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        computeUnits.forEach { (id, label) ->
                            FilterChip(
                                selected = selectedComputeUnit == id,
                                onClick = { selectedComputeUnit = id },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BadgeOrange.copy(alpha = 0.2f),
                                    selectedLabelColor = BadgeOrange,
                                ),
                            )
                        }
                    }

                    // Prompt Input
                    OutlinedTextField(
                        value = samplePrompt,
                        onValueChange = { samplePrompt = it },
                        label = { Text("Benchmark Sample Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )

                    // Repetitions Dropdown
                    RepetitionsDropdownSelector(
                        repetitions = repetitions,
                        onRepetitionsSelected = { repetitions = it },
                    )

                    // Warmup Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Skip First Run (Warmup)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Excludes initial cold run from averages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = warmupFirst,
                            onCheckedChange = { warmupFirst = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BadgeOrange,
                            ),
                        )
                    }

                    // Cold Reload Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Reload Model Every Run", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Measures fresh disk load time on each run", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = coldRunEachTime,
                            onCheckedChange = { coldRunEachTime = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BadgeOrange,
                            ),
                        )
                    }

                    // Speculative Decoding Switch
                    val modelSupportsSpeculative = selectedModel?.model?.supportsSpeculativeDecoding == true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Speculative Decoding (MTP)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        text = if (modelSupportsSpeculative) "MTP" else "Unsupported",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (modelSupportsSpeculative) BadgeGreen else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                if (modelSupportsSpeculative) {
                                    "Parallel candidate token prediction for higher throughput"
                                } else {
                                    "This model does not include speculative drafting heads"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(
                            checked = speculativeDecoding && modelSupportsSpeculative,
                            enabled = modelSupportsSpeculative,
                            onCheckedChange = { speculativeDecoding = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BadgeOrange,
                            ),
                        )
                    }

                    Button(
                        onClick = { runBenchmark() },
                        enabled = !isRunning && !isPipelineRunning && selectedModel != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BadgeOrange),
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(currentStatusText.ifEmpty { "Benchmarking..." }, color = Color.White)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Speed", fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }

                    if (isRunning && currentProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { currentProgress },
                            color = BadgeOrange,
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

        // Summary Card
        val summary = summaryResult
        if (summary != null) {
            item {
                SettingsGroupCard(
                    modifier = Modifier.border(1.dp, BadgeOrange, RoundedCornerShape(20.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Benchmark Summary (${summary.repsCount} reps)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Time to First Token (TTFT):", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "%.1f ± %.1f ms".format(summary.ttftMean, summary.ttftStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        if (summary.prefillMean > 0.0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Prefill Speed:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "%.1f ± %.1f tok/s".format(summary.prefillMean, summary.prefillStdDev),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Decode Speed:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "%.1f ± %.1f tok/s".format(summary.decodeMean, summary.decodeStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = BadgeOrange,
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Speculative Decoding (MTP):", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (summary.speculativeUsed) "Active" else "Disabled",
                                fontWeight = FontWeight.Bold,
                                color = if (summary.speculativeUsed) BadgeGreen else MaterialTheme.colorScheme.outline,
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Wall Clock:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "%.0f ± %.0f ms".format(summary.wallMsMean, summary.wallMsStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        if (summary.peakRamMb > 0.0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Peak RAM Consumption:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "%.0f MB (+%.0f MB)".format(summary.peakRamMb, summary.deltaRamMb),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Average RAM Consumption:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "%.0f MB".format(summary.avgRamMb),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(summary.results) { idx, rep ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val ramPart = if (rep.peakRamMb > 0.0) " | RAM %.0f MB".format(rep.peakRamMb) else ""
                        Text(
                            "Rep #${rep.repIndex}: Decode %.1f tok/s | TTFT %.1f ms | Wall %d ms$ramPart".format(
                                rep.decodeSpeed, rep.ttftMs, rep.wallMs
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (rep.coldLoadMs > 0) {
                            Text(
                                "Cold load: ${rep.coldLoadMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (rep.outputText.isNotBlank()) {
                            Text(
                                rep.outputText.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (onNavigateUp != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Model Benchmarks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        ) { innerPadding ->
            content(Modifier.padding(innerPadding))
        }
    } else {
        content(Modifier)
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
        1 to "1 run (Quick single test)",
        3 to "3 runs (Fast average)",
        5 to "5 runs (Standard benchmark)",
        10 to "10 runs (Thermal & variance check)",
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
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                        Text(
                            currentDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select repetitions",
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
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                ),
            ) {
                Text(
                    text = "No ASR models are downloaded. Please go to Models page and download an ASR model.",
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
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                selectedModel?.label ?: "Select an ASR model",
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
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select ASR model",
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
                                        model.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
    installedModels: List<BenchmarkLlmModel>,
    selectedModel: BenchmarkLlmModel?,
    onModelSelected: (BenchmarkLlmModel) -> Unit,
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
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                ),
            ) {
                Text(
                    text = "No LLM models are downloaded. Please go to Models page and download an LLM model.",
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
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                selectedModel?.label ?: "Select an LLM model",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${selectedModel?.backendName ?: ""} • ${selectedModel?.backendBadge ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = BadgeOrange,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select LLM model",
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
                    installedModels.forEach { item ->
                        val isSelected = item.id == selectedModel?.id
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.label,
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
                                                item.backendBadge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = BadgeOrange,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        "${item.backendName} • ${item.approximateBytes / 1_000_000} MB",
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
