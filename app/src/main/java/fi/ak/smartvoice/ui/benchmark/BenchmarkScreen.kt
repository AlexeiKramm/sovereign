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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ProfilingData
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.audio.WavReader
import fi.ak.smartvoice.cleanup.CleanupModel
import fi.ak.smartvoice.cleanup.CleanupModelStore
import fi.ak.smartvoice.cleanup.CleanupModels
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.cleanup.GenieXCleanupEngine
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.ProfileStore
import fi.ak.smartvoice.data.DictionaryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

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
    val results: List<RepetitionResult>,
)

@Composable
fun BenchmarkScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettings(context) }

    var selectedModel by remember { mutableStateOf<CleanupModel>(appSettings.cleanupModel) }
    var selectedComputeUnit by remember { mutableStateOf("npu") }
    var repetitions by remember { mutableIntStateOf(3) }
    var warmupFirst by remember { mutableStateOf(true) }
    var coldRunEachTime by remember { mutableStateOf(false) }

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
    var installedCleanupModels by remember { mutableStateOf<List<CleanupModel>>(emptyList()) }
    var isCheckingInstalledModels by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) {
            CleanupModels.ALL.filter { model ->
                CleanupModelStore.paths(context, model) != null
            }
        }
        installedCleanupModels = installed
        isCheckingInstalledModels = false
        if (installed.isNotEmpty() && installed.none { it.repository == selectedModel.repository }) {
            selectedModel = installed.first()
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
                    pipelineStatusText = "Running ASR speech recognition..."
                }

                val asrEngine = LocalAsrEngine(AsrModels.directory(context.filesDir))
                val asrStart = SystemClock.elapsedRealtime()
                var rawAsr = ""
                var asrEngineLabel = "Local Sherpa-ONNX (Streaming)"

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
                    asrEngine.unload()
                } else {
                    asrEngineLabel = "Simulated Raw ASR (Model not on device)"
                    rawAsr = sample.fallbackRawAsr
                }
                val asrWallMs = SystemClock.elapsedRealtime() - asrStart

                if (rawAsr.isBlank()) {
                    rawAsr = sample.fallbackRawAsr
                }

                val rawWer = WerCalculator.calculateWer(sample.groundTruth, rawAsr)
                val rawCer = WerCalculator.calculateCer(sample.groundTruth, rawAsr)

                // 2. LLM Cleanup Stage
                withContext(Dispatchers.Main) {
                    pipelineStatusText = "Running LLM cleanup (${selectedModel.label})..."
                }

                val model = selectedModel
                val paths = CleanupModelStore.paths(context, model)
                if (paths == null) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Model (${model.label}) is not downloaded on device. Please select an installed model or download it in the Models tab."
                        isPipelineRunning = false
                        pipelineStatusText = ""
                    }
                    return@launch
                }

                val profileStore = ProfileStore(context)
                val dictionaryStore = DictionaryStore(context)
                val activeProfile = profileStore.activeProfile()
                val variant = when (activeProfile.id) {
                    "conservative" -> CleanupPrompt.Variant.CONSERVATIVE
                    "repairing" -> CleanupPrompt.Variant.REPAIRING
                    else -> CleanupPrompt.Variant.STANDARD
                }
                val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null
                val dictionary = dictionaryStore.getWords()
                val dictionaryEntries = dictionaryStore.getEntries()

                val messages = CleanupPrompt.messages(
                    unit = rawAsr,
                    context = null,
                    dictionary = dictionary,
                    dictionaryEntries = dictionaryEntries,
                    variant = variant,
                    customRules = customRules,
                )

                val config = ModelConfig(nCtx = 1024, nGpuLayers = -1)
                val input = LlmCreateInput(
                    model_path = paths.model_path,
                    tokenizer_path = paths.tokenizer_path,
                    config = config,
                    runtime_id = paths.runtime_id.ifEmpty { "llama_cpp" },
                    compute_unit = selectedComputeUnit,
                )
                val wrapper = LlmWrapper.builder()
                    .llmCreateInput(input)
                    .build()
                    .getOrThrow()

                runCatching { wrapper.reset() }
                val templated = wrapper.applyChatTemplate(messages, null, false).getOrThrow()

                val llmStart = SystemClock.elapsedRealtime()
                var genText = ""
                var profile: ProfilingData? = null

                wrapper.generateStreamFlow(templated.formattedText, GenerationConfig(maxTokens = CleanupPrompt.MAX_TOKENS))
                    .collect { res ->
                        when (res) {
                            is LlmStreamResult.Token -> genText += res.text
                            is LlmStreamResult.Completed -> profile = res.profile
                            is LlmStreamResult.Error -> throw res.throwable
                        }
                    }
                val llmWallMs = SystemClock.elapsedRealtime() - llmStart
                val llmTtftMs = profile?.ttftMs ?: 0.0
                val decMs = profile?.decodeTimeMs ?: 0.0
                val genTokens = profile?.generatedTokens ?: 0L
                val llmDecodeSpeed = if (decMs > 0.0) (genTokens * 1000.0) / decMs else 0.0
                val cleanedText = CleanupPrompt.parse(genText)
                wrapper.close()

                val cleanedWer = WerCalculator.calculateWer(sample.groundTruth, cleanedText)
                val cleanedCer = WerCalculator.calculateCer(sample.groundTruth, cleanedText)

                val result = PipelineBenchmarkResult(
                    sampleName = sample.name,
                    audioDurationSec = wavAudio.durationSeconds,
                    groundTruth = sample.groundTruth,
                    asrEngineUsed = asrEngineLabel,
                    asrWallMs = asrWallMs,
                    rawAsrText = rawAsr,
                    rawWer = rawWer,
                    rawCer = rawCer,
                    llmModelUsed = "${model.label} (${selectedComputeUnit.uppercase()})",
                    llmProfileUsed = activeProfile.name,
                    llmTtftMs = llmTtftMs,
                    llmDecodeTokPerSec = llmDecodeSpeed,
                    llmWallMs = llmWallMs,
                    cleanedText = cleanedText,
                    cleanedWer = cleanedWer,
                    cleanedCer = cleanedCer,
                )

                withContext(Dispatchers.Main) {
                    pipelineResult = result
                    isPipelineRunning = false
                    pipelineStatusText = ""
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
        isRunning = true
        errorMessage = null
        summaryResult = null

        scope.launch(Dispatchers.IO) {
            val model = selectedModel
            val paths = CleanupModelStore.paths(context, model)
            if (paths == null) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Model (${model.label}) is not downloaded on device. Please download it in the Models tab first."
                    isRunning = false
                }
                return@launch
            }

            val recordedResults = mutableListOf<RepetitionResult>()
            val totalRuns = (if (warmupFirst) 1 else 0) + repetitions
            var currentRun = 0

            var activeWrapper: LlmWrapper? = null

            suspend fun getOrInitWrapper(): Pair<LlmWrapper, Long> {
                if (activeWrapper != null && !coldRunEachTime) {
                    return Pair(activeWrapper!!, 0L)
                }
                activeWrapper?.close()
                activeWrapper = null

                val config = ModelConfig(nCtx = 1024, nGpuLayers = -1)
                val startLoad = System.currentTimeMillis()
                val input = LlmCreateInput(
                    model_path = paths.model_path,
                    tokenizer_path = paths.tokenizer_path,
                    config = config,
                    runtime_id = paths.runtime_id.ifEmpty { "llama_cpp" },
                    compute_unit = selectedComputeUnit,
                )
                val wrapper = LlmWrapper.builder()
                    .llmCreateInput(input)
                    .build()
                    .getOrThrow()
                val coldLoad = System.currentTimeMillis() - startLoad
                activeWrapper = wrapper
                return Pair(wrapper, coldLoad)
            }

            try {
                if (warmupFirst) {
                    currentRun++
                    withContext(Dispatchers.Main) {
                        currentStatusText = "Warmup run (1/1)..."
                        currentProgress = currentRun.toFloat() / totalRuns
                    }
                    val (wrapper, coldLoad) = getOrInitWrapper()
                    runCatching { wrapper.reset() }
                    val messages = CleanupPrompt.messages("warmup test", null)
                    val templated = wrapper.applyChatTemplate(messages, null, false).getOrThrow()
                    wrapper.generateStreamFlow(templated.formattedText, GenerationConfig(maxTokens = 32)).collect {}
                    if (coldRunEachTime) {
                        activeWrapper?.close()
                        activeWrapper = null
                    }
                }

                for (rep in 1..repetitions) {
                    currentRun++
                    withContext(Dispatchers.Main) {
                        currentStatusText = "Repetition $rep of $repetitions..."
                        currentProgress = currentRun.toFloat() / totalRuns
                    }

                    val (wrapper, coldLoad) = getOrInitWrapper()
                    runCatching { wrapper.reset() }
                    val profileStore = ProfileStore(context)
                    val dictionaryStore = DictionaryStore(context)
                    val activeProfile = profileStore.activeProfile()
                    val variant = when (activeProfile.id) {
                        "conservative" -> CleanupPrompt.Variant.CONSERVATIVE
                        "repairing" -> CleanupPrompt.Variant.REPAIRING
                        else -> CleanupPrompt.Variant.STANDARD
                    }
                    val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null
                    val messages = CleanupPrompt.messages(
                        unit = samplePrompt,
                        context = null,
                        dictionary = dictionaryStore.getWords(),
                        dictionaryEntries = dictionaryStore.getEntries(),
                        variant = variant,
                        customRules = customRules,
                    )
                    val templated = wrapper.applyChatTemplate(messages, null, false).getOrThrow()

                    val wallStart = System.currentTimeMillis()
                    var output = ""
                    var profile: ProfilingData? = null

                    wrapper.generateStreamFlow(templated.formattedText, GenerationConfig(maxTokens = CleanupPrompt.MAX_TOKENS))
                        .collect { res ->
                            when (res) {
                                is LlmStreamResult.Token -> output += res.text
                                is LlmStreamResult.Completed -> profile = res.profile
                                is LlmStreamResult.Error -> throw res.throwable
                            }
                        }
                    val wallMs = System.currentTimeMillis() - wallStart

                    val parsed = CleanupPrompt.parse(output)
                    recordedResults.add(
                        RepetitionResult(
                            repIndex = rep,
                            isWarmup = false,
                            coldLoadMs = coldLoad,
                            ttftMs = profile?.ttftMs ?: 0.0,
                            prefillSpeed = if ((profile?.promptTimeMs ?: 0.0) > 0.0) ((profile?.promptTokens ?: 0L) * 1000.0) / profile!!.promptTimeMs else 0.0,
                            decodeSpeed = if ((profile?.decodeTimeMs ?: 0.0) > 0.0) ((profile?.generatedTokens ?: 0L) * 1000.0) / profile!!.decodeTimeMs else 0.0,
                            wallMs = wallMs,
                            generatedTokens = profile?.generatedTokens ?: 0L,
                            outputText = parsed,
                        )
                    )

                    if (coldRunEachTime) {
                        activeWrapper?.close()
                        activeWrapper = null
                    }
                }

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
                    results = recordedResults,
                )

                withContext(Dispatchers.Main) {
                    summaryResult = summary
                    currentStatusText = "Completed $repetitions runs successfully."
                    isRunning = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: t.toString()
                    isRunning = false
                }
            } finally {
                activeWrapper?.close()
                activeWrapper = null
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("Performance & Benchmarking", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Benchmark on-device ASR recognition and NPU LLM cleanup speed, accuracy, and repetition stability.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ================= SECTION: AUDIO PIPELINE BENCHMARK =================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Audio Pipeline Benchmark (Audio → ASR → LLM)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Feeds real preloaded 16kHz speech WAV files through the speech recogniser and LLM cleanup pipeline, comparing raw vs cleaned Word Error Rate (WER) against ground truth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text("Benchmark Audio Sample:", style = MaterialTheme.typography.labelMedium)
                    OutlinedCard(
                        onClick = { audioPlayer.playOrPause(selectedAudioSample.assetPath) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
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
                                )
                                val statusDesc = when {
                                    audioPlayer.isPlaying -> "Playing audio (tap to pause) • 19.3s"
                                    audioPlayer.isPaused -> "Paused (tap to resume) • 19.3s"
                                    else -> selectedAudioSample.durationDesc
                                }
                                Text(
                                    statusDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (audioPlayer.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { audioPlayer.playOrPause(selectedAudioSample.assetPath) },
                            ) {
                                Icon(
                                    imageVector = if (audioPlayer.isPlaying) PauseIcon else Icons.Default.PlayArrow,
                                    contentDescription = if (audioPlayer.isPlaying) "Pause audio" else "Play audio",
                                    tint = if (audioPlayer.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Dropdown selector for installed models
                    CleanupModelDropdownSelector(
                        label = "Select Cleanup Model for Pipeline:",
                        installedModels = installedCleanupModels,
                        selectedModel = selectedModel,
                        onModelSelected = { selectedModel = it },
                    )

                    val activeAppProfile = remember { ProfileStore(context).activeProfile() }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            " (using prompt & rules from Profiles tab)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Ground Truth Reference:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "\"${selectedAudioSample.groundTruth}\"",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
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
                        enabled = !isPipelineRunning && !isRunning && installedCleanupModels.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isPipelineRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(pipelineStatusText.ifEmpty { "Benchmarking Pipeline..." })
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run Pipeline Benchmark")
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Pipeline Benchmark Results: ${res.sampleName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        Text(
                            "Audio Duration: %.1fs | ASR Wall: %d ms | LLM Wall: %d ms".format(
                                res.audioDurationSec, res.asrWallMs, res.llmWallMs
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        HorizontalDivider()

                        // ASR Stage
                        Text("1. Speech Recogniser Stage (${res.asrEngineUsed}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text(
                            res.rawAsrText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Raw ASR WER:", style = MaterialTheme.typography.bodySmall)
                            Text("%.1f%% (CER: %.1f%%)".format(res.rawWer * 100, res.rawCer * 100), fontWeight = FontWeight.Bold)
                        }

                        HorizontalDivider()

                        // LLM Stage
                        Text("2. LLM Cleanup Stage (${res.llmModelUsed} · Profile: ${res.llmProfileUsed}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text(
                            res.cleanedText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cleaned WER:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "%.1f%% (CER: %.1f%%)".format(res.cleanedWer * 100, res.cleanedCer * 100),
                                fontWeight = FontWeight.Bold,
                                color = if (res.cleanedWer <= res.rawWer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("LLM Latency & Speed:", style = MaterialTheme.typography.bodySmall)
                            Text("TTFT: %.1f ms | %.1f tok/s".format(res.llmTtftMs, res.llmDecodeTokPerSec), fontWeight = FontWeight.Bold)
                        }

                        val werDelta = (res.rawWer - res.cleanedWer) * 100
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("WER Improvement:", fontWeight = FontWeight.Bold)
                                Text(
                                    if (werDelta >= 0) "+%.1f%% accuracy gain".format(werDelta) else "%.1f%%".format(werDelta),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ================= SECTION: LLM REPETITION & VARIANCE BENCHMARK =================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("LLM Repetition & Variance Benchmark", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Measure TTFT, prefill, and decode speed variance across multiple runs to verify thermal and memory stability.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Dropdown selector for installed models
                    CleanupModelDropdownSelector(
                        label = "Select Cleanup Model:",
                        installedModels = installedCleanupModels,
                        selectedModel = selectedModel,
                        onModelSelected = { selectedModel = it },
                    )

                    // Compute Unit
                    Text("Compute Unit:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("npu" to "Hexagon NPU", "cpu" to "CPU", "gpu" to "GPU").forEach { (id, label) ->
                            FilterChip(
                                selected = selectedComputeUnit == id,
                                onClick = { selectedComputeUnit = id },
                                label = { Text(label) },
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
                            Text("Discard Initial Warmup Run", style = MaterialTheme.typography.bodyMedium)
                            Text("Excludes first cold JIT/KV cache run from stats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = warmupFirst, onCheckedChange = { warmupFirst = it })
                    }

                    // Cold Reload Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Force Cold Reload Each Run", style = MaterialTheme.typography.bodyMedium)
                            Text("Measures cold model load from disk every repetition", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = coldRunEachTime, onCheckedChange = { coldRunEachTime = it })
                    }

                    Button(
                        onClick = { runBenchmark() },
                        enabled = !isRunning && !isPipelineRunning && installedCleanupModels.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(currentStatusText.ifEmpty { "Benchmarking..." })
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run Repetition Benchmark")
                        }
                    }

                    if (isRunning && currentProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { currentProgress },
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
        summaryResult?.let { summary ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Benchmark Summary (${summary.repsCount} reps)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Time to First Token (TTFT):", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%.1f ± %.1f ms".format(summary.ttftMean, summary.ttftStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Prefill Speed:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%.1f ± %.1f tok/s".format(summary.prefillMean, summary.prefillStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Decode Speed:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%.1f ± %.1f tok/s".format(summary.decodeMean, summary.decodeStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Wall Clock:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%.0f ± %.0f ms".format(summary.wallMsMean, summary.wallMsStdDev),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            itemsIndexed(summary.results) { idx, rep ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Rep #${rep.repIndex}: Decode %.1f tok/s | TTFT %.1f ms | Wall %d ms".format(
                                rep.decodeSpeed, rep.ttftMs, rep.wallMs
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
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
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupModelDropdownSelector(
    label: String,
    installedModels: List<CleanupModel>,
    selectedModel: CleanupModel,
    onModelSelected: (CleanupModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        if (installedModels.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                ),
            ) {
                Text(
                    text = "No models downloaded (download in Models tab)",
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
                                selectedModel.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Precision: ${selectedModel.precision} • ${CleanupModels.sizeLabel(selectedModel.approximateBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select model",
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
                        val isSelected = model.repository == selectedModel.repository
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
                                        "${model.precision} • ${CleanupModels.sizeLabel(model.approximateBytes)}",
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
        Text("Benchmark Repetitions:", style = MaterialTheme.typography.labelMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedCard(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        cardWidthDp = with(density) { coordinates.size.width.toDp() }
                    },
                shape = RoundedCornerShape(12.dp),
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
