package fi.ak.smartvoice.ui.benchmark

import android.content.Context
import android.os.SystemClock
import android.util.Log
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModelType
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.asr.OfflineVadAsrEngine
import fi.ak.smartvoice.audio.WavReader
import fi.ak.smartvoice.cleanup.CleanupPass
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.cleanup.LiteRtLmCleanupEngine
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.data.DictionaryStore
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.data.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

enum class BenchmarkMode(val label: String) {
    FULL_PIPELINE("Full Pipeline (Speech → Cleanup)"),
    ASR_ONLY("ASR Only (Speech → Text)"),
    LLM_ONLY("LLM Only (Text Cleanup Speed)"),
}

data class BenchmarkAudioSample(
    val id: String,
    val name: String,
    val assetWavPath: String,
    val assetTxtPath: String,
    val durationDesc: String,
    val durationSeconds: Float,
    val language: String,
) {
    companion object {
        val FIXTURES = listOf(
            BenchmarkAudioSample(
                id = "en_short",
                name = "Short (4.8s • 11 words)",
                assetWavPath = "en/short.wav",
                assetTxtPath = "en/short.txt",
                durationDesc = "4.83s audio • 16kHz mono WAV",
                durationSeconds = 4.83f,
                language = "en",
            ),
            BenchmarkAudioSample(
                id = "en_medium",
                name = "Medium (11.4s • 29 words)",
                assetWavPath = "en/medium.wav",
                assetTxtPath = "en/medium.txt",
                durationDesc = "11.42s audio • 16kHz mono WAV",
                durationSeconds = 11.42f,
                language = "en",
            ),
            BenchmarkAudioSample(
                id = "en_long",
                name = "Long (21.4s • 55 words)",
                assetWavPath = "en/long.wav",
                assetTxtPath = "en/long.txt",
                durationDesc = "21.35s audio • 16kHz mono WAV",
                durationSeconds = 21.35f,
                language = "en",
            ),
        )

        fun readGroundTruth(context: Context, sample: BenchmarkAudioSample): String {
            return try {
                context.assets.open(sample.assetTxtPath).bufferedReader().use { it.readText() }.trim()
            } catch (e: Exception) {
                Log.w("BenchmarkEngine", "Failed to read ground truth from ${sample.assetTxtPath}: ${e.message}")
                ""
            }
        }
    }
}

data class BenchmarkConfig(
    val mode: BenchmarkMode,
    val asrModel: AsrModelSpec? = null,
    val llmModel: LiteRtLmModelSpec? = null,
    val audioSample: BenchmarkAudioSample = BenchmarkAudioSample.FIXTURES[0],
    val textPrompt: String = "",
    val computeTarget: String = "gpu", // "gpu" or "cpu"
    val speculativeDecoding: Boolean = true,
    val repetitions: Int = 3,
    val coldReloadEachRep: Boolean = false,
)

data class RepetitionMeasurement(
    val repIndex: Int,
    val isCold: Boolean,
    val asrColdLoadMs: Long = 0L,
    val llmColdLoadMs: Long = 0L,
    val asrWallMs: Long = 0L,
    val asrRtf: Double = 0.0,
    val rawAsrText: String = "",
    val rawWer: Double = 0.0,
    val rawCer: Double = 0.0,
    val llmTtftMs: Double = 0.0,
    val llmPrefillTokPerSec: Double = 0.0,
    val llmDecodeTokPerSec: Double = 0.0,
    val llmWallMs: Long = 0L,
    val cleanedText: String = "",
    val cleanedWer: Double = 0.0,
    val cleanedCer: Double = 0.0,
    val e2eWallMs: Long = 0L,
)

data class MetricStat(val mean: Double, val stdDev: Double)

data class BenchmarkSummary(
    val mode: BenchmarkMode,
    val asrModelLabel: String,
    val llmModelLabel: String,
    val computeTarget: String,
    val speculativeUsed: Boolean,
    val sampleName: String,
    val audioDurationSec: Float,
    val repsCount: Int,
    val coldReloadEachRep: Boolean,
    val initialAsrColdLoadMs: Long,
    val initialLlmColdLoadMs: Long,
    val asrColdLoadStat: MetricStat,
    val llmColdLoadStat: MetricStat,
    val asrWallStat: MetricStat,
    val asrRtfStat: MetricStat,
    val llmTtftStat: MetricStat,
    val llmPrefillStat: MetricStat,
    val llmDecodeStat: MetricStat,
    val llmWallStat: MetricStat,
    val e2eWallStat: MetricStat,
    val groundTruth: String,
    val rawAsrText: String,
    val rawWer: Double,
    val rawCer: Double,
    val cleanedText: String,
    val cleanedWer: Double,
    val cleanedCer: Double,
    val peakRamMb: Double,
    val avgRamMb: Double,
    val repetitions: List<RepetitionMeasurement>,
)

object BenchmarkStatsHelper {
    fun calculateMeanAndStdDev(values: List<Double>): MetricStat {
        if (values.isEmpty()) return MetricStat(0.0, 0.0)
        val mean = values.average()
        val variance = if (values.size > 1) {
            values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        } else 0.0
        return MetricStat(mean, sqrt(variance))
    }

    fun calculateRtf(wallMs: Long, audioDurationSec: Float): Double {
        if (audioDurationSec <= 0f) return 0.0
        val procSec = wallMs / 1000.0
        return procSec / audioDurationSec.toDouble()
    }
}

object BenchmarkCsvLogger {
    private const val CSV_HEADER =
        "timestamp,mode,asr_model,llm_model,compute_target,mtp,sample_name,audio_dur_sec,reps,cold_reload," +
            "cold_asr_load_ms,cold_llm_load_ms,asr_wall_mean_ms,asr_wall_std_ms,asr_rtf_mean,asr_rtf_std," +
            "llm_ttft_mean_ms,llm_ttft_std_ms,llm_prefill_mean_tps,llm_decode_mean_tps,llm_decode_std_tps," +
            "llm_wall_mean_ms,llm_wall_std_ms,e2e_wall_mean_ms,e2e_wall_std_ms,raw_wer,raw_cer,cleaned_wer,cleaned_cer," +
            "peak_ram_mb,avg_ram_mb"

    fun log(context: Context, summary: BenchmarkSummary): String {
        val isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val csvLine = listOf(
            isoTimestamp,
            summary.mode.name,
            escapeCsv(summary.asrModelLabel),
            escapeCsv(summary.llmModelLabel),
            summary.computeTarget,
            summary.speculativeUsed.toString(),
            escapeCsv(summary.sampleName),
            String.format(Locale.US, "%.2f", summary.audioDurationSec),
            summary.repsCount.toString(),
            summary.coldReloadEachRep.toString(),
            summary.initialAsrColdLoadMs.toString(),
            summary.initialLlmColdLoadMs.toString(),
            String.format(Locale.US, "%.1f", summary.asrWallStat.mean),
            String.format(Locale.US, "%.1f", summary.asrWallStat.stdDev),
            String.format(Locale.US, "%.4f", summary.asrRtfStat.mean),
            String.format(Locale.US, "%.4f", summary.asrRtfStat.stdDev),
            String.format(Locale.US, "%.1f", summary.llmTtftStat.mean),
            String.format(Locale.US, "%.1f", summary.llmTtftStat.stdDev),
            String.format(Locale.US, "%.1f", summary.llmPrefillStat.mean),
            String.format(Locale.US, "%.1f", summary.llmDecodeStat.mean),
            String.format(Locale.US, "%.1f", summary.llmDecodeStat.stdDev),
            String.format(Locale.US, "%.1f", summary.llmWallStat.mean),
            String.format(Locale.US, "%.1f", summary.llmWallStat.stdDev),
            String.format(Locale.US, "%.1f", summary.e2eWallStat.mean),
            String.format(Locale.US, "%.1f", summary.e2eWallStat.stdDev),
            String.format(Locale.US, "%.4f", summary.rawWer),
            String.format(Locale.US, "%.4f", summary.rawCer),
            String.format(Locale.US, "%.4f", summary.cleanedWer),
            String.format(Locale.US, "%.4f", summary.cleanedCer),
            String.format(Locale.US, "%.1f", summary.peakRamMb),
            String.format(Locale.US, "%.1f", summary.avgRamMb),
        ).joinToString(",")

        Log.i("SmartVoiceBenchmark", "CSV: $csvLine")

        try {
            val file = File(context.filesDir, "benchmarks.csv")
            val isNew = !file.exists() || file.length() == 0L
            file.appendText(if (isNew) "$CSV_HEADER\n$csvLine\n" else "$csvLine\n")
        } catch (e: Exception) {
            Log.e("SmartVoiceBenchmark", "Failed to write benchmarks.csv: ${e.message}")
        }

        return csvLine
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

class BenchmarkEngine(private val context: Context) {

    suspend fun run(
        config: BenchmarkConfig,
        onProgress: (progress: Float, status: String) -> Unit,
    ): BenchmarkSummary = withContext(Dispatchers.IO) {
        val memSampler = MemorySampler()
        memSampler.start(this)

        try {
            when (config.mode) {
                BenchmarkMode.FULL_PIPELINE -> runFullPipeline(config, memSampler, onProgress)
                BenchmarkMode.ASR_ONLY -> runAsrOnly(config, memSampler, onProgress)
                BenchmarkMode.LLM_ONLY -> runLlmOnly(config, memSampler, onProgress)
            }
        } finally {
            memSampler.stop()
        }
    }

    private suspend fun runFullPipeline(
        config: BenchmarkConfig,
        memSampler: MemorySampler,
        onProgress: (progress: Float, status: String) -> Unit,
    ): BenchmarkSummary {
        val asrSpec = checkNotNull(config.asrModel) { "ASR model is required for Full Pipeline" }
        val llmSpec = checkNotNull(config.llmModel) { "LLM model is required for Full Pipeline" }

        onProgress(0.05f, "Loading audio fixture...")
        val wavAudio = WavReader.read(context.assets.open(config.audioSample.assetWavPath))
        val groundTruth = BenchmarkAudioSample.readGroundTruth(context, config.audioSample)

        val profileStore = ProfileStore(context)
        val dictionaryStore = DictionaryStore(context)
        val activeProfile = profileStore.activeProfile()
        val variant = CleanupPrompt.Variant.fromId(activeProfile.id)
        val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null
        val processor = if (config.computeTarget == "cpu" || !llmSpec.supportsGpu) {
            LiteRtProcessor.CPU
        } else {
            LiteRtProcessor.GPU
        }

        var persistentStreamingAsr: LocalAsrEngine? = null
        var persistentOfflineAsr: OfflineVadAsrEngine? = null
        var persistentLlm: LiteRtLmCleanupEngine? = null

        var initialAsrColdLoadMs = 0L
        var initialLlmColdLoadMs = 0L

        suspend fun loadAsr(): Long {
            val start = SystemClock.elapsedRealtime()
            val asrDir = asrSpec.directory(context.filesDir)
            if (asrSpec.type == AsrModelType.STREAMING_TRANSDUCER) {
                val engine = LocalAsrEngine(asrDir, assetManager = context.assets)
                engine.load()
                persistentStreamingAsr = engine
            } else {
                val engine = OfflineVadAsrEngine(asrDir, asrSpec, assetManager = context.assets)
                engine.load()
                persistentOfflineAsr = engine
            }
            return SystemClock.elapsedRealtime() - start
        }

        suspend fun unloadAsr() {
            persistentStreamingAsr?.unload()
            persistentStreamingAsr = null
            persistentOfflineAsr?.unload()
            persistentOfflineAsr = null
        }

        suspend fun loadLlm(): Long {
            val start = SystemClock.elapsedRealtime()
            val engine = LiteRtLmCleanupEngine(
                context = context,
                modelSpec = llmSpec,
                processor = processor,
                speculativeDecoding = config.speculativeDecoding && llmSpec.supportsSpeculativeDecoding,
                vocabulary = dictionaryStore::getEntries,
            )
            engine.load().getOrThrow()
            persistentLlm = engine
            return SystemClock.elapsedRealtime() - start
        }

        suspend fun unloadLlm() {
            persistentLlm?.unload()
            persistentLlm = null
            System.gc()
        }

        try {
            // Cold Setup
            onProgress(0.1f, "Cold loading models...")
            initialAsrColdLoadMs = loadAsr()
            initialLlmColdLoadMs = loadLlm()

            // Warmup pass if running warm steady-state
            if (!config.coldReloadEachRep) {
                onProgress(0.15f, "Warming up pipeline...")
                runSingleAsr(persistentStreamingAsr, persistentOfflineAsr, wavAudio.samples, config.audioSample.language)
                persistentLlm?.clean(unit = "warmup pass", context = null, variant = variant, customRules = customRules)
            } else {
                // If coldReloadEachRep, unload now so rep 1 does a cold reload
                unloadAsr()
                unloadLlm()
            }

            val repetitions = mutableListOf<RepetitionMeasurement>()
            val totalReps = config.repetitions

            for (rep in 1..totalReps) {
                val repFraction = (rep.toFloat() / totalReps) * 0.8f + 0.15f
                var asrLoad = 0L
                var llmLoad = 0L

                if (config.coldReloadEachRep) {
                    onProgress(repFraction, "Rep $rep/$totalReps: Cold loading models...")
                    asrLoad = loadAsr()
                    llmLoad = loadLlm()
                }

                onProgress(repFraction, "Rep $rep/$totalReps: Running ASR...")
                val e2eStart = SystemClock.elapsedRealtime()
                val asrStart = SystemClock.elapsedRealtime()
                val rawAsr = runSingleAsr(
                    persistentStreamingAsr,
                    persistentOfflineAsr,
                    wavAudio.samples,
                    config.audioSample.language,
                )
                val asrWallMs = SystemClock.elapsedRealtime() - asrStart
                val asrRtf = BenchmarkStatsHelper.calculateRtf(asrWallMs, wavAudio.durationSeconds)

                val rawWer = if (groundTruth.isNotBlank()) WerCalculator.calculateWer(groundTruth, rawAsr) else 0.0
                val rawCer = if (groundTruth.isNotBlank()) WerCalculator.calculateCer(groundTruth, rawAsr) else 0.0

                onProgress(repFraction, "Rep $rep/$totalReps: Running LLM Cleanup...")
                val llmStart = SystemClock.elapsedRealtime()
                val pass = persistentLlm!!.clean(
                    unit = rawAsr,
                    context = null,
                    variant = variant,
                    customRules = customRules,
                )
                val llmWallMs = SystemClock.elapsedRealtime() - llmStart
                val e2eWallMs = SystemClock.elapsedRealtime() - e2eStart

                val cleanedText = if (pass.cleaned && pass.text.isNotBlank()) pass.text else rawAsr
                val cleanedWer = if (groundTruth.isNotBlank()) WerCalculator.calculateWer(groundTruth, cleanedText) else 0.0
                val cleanedCer = if (groundTruth.isNotBlank()) WerCalculator.calculateCer(groundTruth, cleanedText) else 0.0

                val decodeSpeed = if (pass.decodeTimeMs > 0.0) (pass.generatedTokens * 1000.0) / pass.decodeTimeMs else 0.0
                val prefillSpeed = if (pass.promptTimeMs > 0.0 && pass.promptTokens > 0) (pass.promptTokens * 1000.0) / pass.promptTimeMs else 0.0

                repetitions.add(
                    RepetitionMeasurement(
                        repIndex = rep,
                        isCold = config.coldReloadEachRep,
                        asrColdLoadMs = if (config.coldReloadEachRep) asrLoad else initialAsrColdLoadMs,
                        llmColdLoadMs = if (config.coldReloadEachRep) llmLoad else initialLlmColdLoadMs,
                        asrWallMs = asrWallMs,
                        asrRtf = asrRtf,
                        rawAsrText = rawAsr,
                        rawWer = rawWer,
                        rawCer = rawCer,
                        llmTtftMs = pass.ttftMs,
                        llmPrefillTokPerSec = prefillSpeed,
                        llmDecodeTokPerSec = decodeSpeed,
                        llmWallMs = llmWallMs,
                        cleanedText = cleanedText,
                        cleanedWer = cleanedWer,
                        cleanedCer = cleanedCer,
                        e2eWallMs = e2eWallMs,
                    )
                )

                if (config.coldReloadEachRep) {
                    unloadAsr()
                    unloadLlm()
                }
            }

            val memStats = memSampler.stop()
            val summary = buildSummary(
                config = config,
                groundTruth = groundTruth,
                audioDurationSec = wavAudio.durationSeconds,
                initialAsrColdLoadMs = initialAsrColdLoadMs,
                initialLlmColdLoadMs = initialLlmColdLoadMs,
                repetitions = repetitions,
                memStats = memStats,
            )

            BenchmarkCsvLogger.log(context, summary)
            onProgress(1f, "Complete")
            return summary
        } finally {
            unloadAsr()
            unloadLlm()
        }
    }

    private suspend fun runAsrOnly(
        config: BenchmarkConfig,
        memSampler: MemorySampler,
        onProgress: (progress: Float, status: String) -> Unit,
    ): BenchmarkSummary {
        val asrSpec = checkNotNull(config.asrModel) { "ASR model is required for ASR Only mode" }

        onProgress(0.05f, "Loading audio fixture...")
        val wavAudio = WavReader.read(context.assets.open(config.audioSample.assetWavPath))
        val groundTruth = BenchmarkAudioSample.readGroundTruth(context, config.audioSample)

        var persistentStreamingAsr: LocalAsrEngine? = null
        var persistentOfflineAsr: OfflineVadAsrEngine? = null
        var initialAsrColdLoadMs = 0L

        suspend fun loadAsr(): Long {
            val start = SystemClock.elapsedRealtime()
            val asrDir = asrSpec.directory(context.filesDir)
            if (asrSpec.type == AsrModelType.STREAMING_TRANSDUCER) {
                val engine = LocalAsrEngine(asrDir, assetManager = context.assets)
                engine.load()
                persistentStreamingAsr = engine
            } else {
                val engine = OfflineVadAsrEngine(asrDir, asrSpec, assetManager = context.assets)
                engine.load()
                persistentOfflineAsr = engine
            }
            return SystemClock.elapsedRealtime() - start
        }

        suspend fun unloadAsr() {
            persistentStreamingAsr?.unload()
            persistentStreamingAsr = null
            persistentOfflineAsr?.unload()
            persistentOfflineAsr = null
            System.gc()
        }

        try {
            onProgress(0.1f, "Cold loading ASR model...")
            initialAsrColdLoadMs = loadAsr()

            if (!config.coldReloadEachRep) {
                onProgress(0.15f, "Warming up ASR...")
                runSingleAsr(persistentStreamingAsr, persistentOfflineAsr, wavAudio.samples, config.audioSample.language)
            } else {
                unloadAsr()
            }

            val repetitions = mutableListOf<RepetitionMeasurement>()
            val totalReps = config.repetitions

            for (rep in 1..totalReps) {
                val repFraction = (rep.toFloat() / totalReps) * 0.8f + 0.15f
                var asrLoad = 0L

                if (config.coldReloadEachRep) {
                    onProgress(repFraction, "Rep $rep/$totalReps: Cold loading ASR...")
                    asrLoad = loadAsr()
                }

                onProgress(repFraction, "Rep $rep/$totalReps: Running ASR recognition...")
                val asrStart = SystemClock.elapsedRealtime()
                val rawAsr = runSingleAsr(
                    persistentStreamingAsr,
                    persistentOfflineAsr,
                    wavAudio.samples,
                    config.audioSample.language,
                )
                val asrWallMs = SystemClock.elapsedRealtime() - asrStart
                val asrRtf = BenchmarkStatsHelper.calculateRtf(asrWallMs, wavAudio.durationSeconds)

                val rawWer = if (groundTruth.isNotBlank()) WerCalculator.calculateWer(groundTruth, rawAsr) else 0.0
                val rawCer = if (groundTruth.isNotBlank()) WerCalculator.calculateCer(groundTruth, rawAsr) else 0.0

                repetitions.add(
                    RepetitionMeasurement(
                        repIndex = rep,
                        isCold = config.coldReloadEachRep,
                        asrColdLoadMs = if (config.coldReloadEachRep) asrLoad else initialAsrColdLoadMs,
                        asrWallMs = asrWallMs,
                        asrRtf = asrRtf,
                        rawAsrText = rawAsr,
                        rawWer = rawWer,
                        rawCer = rawCer,
                        e2eWallMs = asrWallMs,
                    )
                )

                if (config.coldReloadEachRep) {
                    unloadAsr()
                }
            }

            val memStats = memSampler.stop()
            val summary = buildSummary(
                config = config,
                groundTruth = groundTruth,
                audioDurationSec = wavAudio.durationSeconds,
                initialAsrColdLoadMs = initialAsrColdLoadMs,
                initialLlmColdLoadMs = 0L,
                repetitions = repetitions,
                memStats = memStats,
            )

            BenchmarkCsvLogger.log(context, summary)
            onProgress(1f, "Complete")
            return summary
        } finally {
            unloadAsr()
        }
    }

    private suspend fun runLlmOnly(
        config: BenchmarkConfig,
        memSampler: MemorySampler,
        onProgress: (progress: Float, status: String) -> Unit,
    ): BenchmarkSummary {
        val llmSpec = checkNotNull(config.llmModel) { "LLM model is required for LLM Only mode" }

        val profileStore = ProfileStore(context)
        val dictionaryStore = DictionaryStore(context)
        val activeProfile = profileStore.activeProfile()
        val variant = CleanupPrompt.Variant.fromId(activeProfile.id)
        val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null
        val processor = if (config.computeTarget == "cpu" || !llmSpec.supportsGpu) {
            LiteRtProcessor.CPU
        } else {
            LiteRtProcessor.GPU
        }

        var persistentLlm: LiteRtLmCleanupEngine? = null
        var initialLlmColdLoadMs = 0L

        suspend fun loadLlm(): Long {
            val start = SystemClock.elapsedRealtime()
            val engine = LiteRtLmCleanupEngine(
                context = context,
                modelSpec = llmSpec,
                processor = processor,
                speculativeDecoding = config.speculativeDecoding && llmSpec.supportsSpeculativeDecoding,
                vocabulary = dictionaryStore::getEntries,
            )
            engine.load().getOrThrow()
            persistentLlm = engine
            return SystemClock.elapsedRealtime() - start
        }

        suspend fun unloadLlm() {
            persistentLlm?.unload()
            persistentLlm = null
            System.gc()
        }

        try {
            onProgress(0.1f, "Cold loading LLM model...")
            initialLlmColdLoadMs = loadLlm()

            if (!config.coldReloadEachRep) {
                onProgress(0.15f, "Warming up LLM...")
                persistentLlm?.clean(unit = "warmup run", context = null, variant = variant, customRules = customRules)
            } else {
                unloadLlm()
            }

            val repetitions = mutableListOf<RepetitionMeasurement>()
            val totalReps = config.repetitions
            val prompt = config.textPrompt.ifBlank {
                "Basically I was just thinking that we could maybe like test the model rewrite capability and see how it performs."
            }

            for (rep in 1..totalReps) {
                val repFraction = (rep.toFloat() / totalReps) * 0.8f + 0.15f
                var llmLoad = 0L

                if (config.coldReloadEachRep) {
                    onProgress(repFraction, "Rep $rep/$totalReps: Cold loading LLM...")
                    llmLoad = loadLlm()
                }

                onProgress(repFraction, "Rep $rep/$totalReps: Generating cleanup...")
                val llmStart = SystemClock.elapsedRealtime()
                val pass = persistentLlm!!.clean(
                    unit = prompt,
                    context = null,
                    variant = variant,
                    customRules = customRules,
                )
                val llmWallMs = SystemClock.elapsedRealtime() - llmStart

                val decodeSpeed = if (pass.decodeTimeMs > 0.0) (pass.generatedTokens * 1000.0) / pass.decodeTimeMs else 0.0
                val prefillSpeed = if (pass.promptTimeMs > 0.0 && pass.promptTokens > 0) (pass.promptTokens * 1000.0) / pass.promptTimeMs else 0.0

                repetitions.add(
                    RepetitionMeasurement(
                        repIndex = rep,
                        isCold = config.coldReloadEachRep,
                        llmColdLoadMs = if (config.coldReloadEachRep) llmLoad else initialLlmColdLoadMs,
                        llmTtftMs = pass.ttftMs,
                        llmPrefillTokPerSec = prefillSpeed,
                        llmDecodeTokPerSec = decodeSpeed,
                        llmWallMs = llmWallMs,
                        cleanedText = if (pass.cleaned) pass.text else prompt,
                        e2eWallMs = llmWallMs,
                    )
                )

                if (config.coldReloadEachRep) {
                    unloadLlm()
                }
            }

            val memStats = memSampler.stop()
            val summary = buildSummary(
                config = config,
                groundTruth = "",
                audioDurationSec = 0f,
                initialAsrColdLoadMs = 0L,
                initialLlmColdLoadMs = initialLlmColdLoadMs,
                repetitions = repetitions,
                memStats = memStats,
            )

            BenchmarkCsvLogger.log(context, summary)
            onProgress(1f, "Complete")
            return summary
        } finally {
            unloadLlm()
        }
    }

    private fun runSingleAsr(
        streaming: LocalAsrEngine?,
        offline: OfflineVadAsrEngine?,
        samples: FloatArray,
        language: String,
    ): String {
        val chunkSize = 1600 // 100ms at 16kHz
        return if (streaming != null) {
            streaming.startSession(language = language)
            for (i in samples.indices step chunkSize) {
                val end = minOf(i + chunkSize, samples.size)
                streaming.accept(samples.copyOfRange(i, end))
            }
            streaming.finish().trim()
        } else if (offline != null) {
            offline.startSession(language = language)
            for (i in samples.indices step chunkSize) {
                val end = minOf(i + chunkSize, samples.size)
                offline.accept(samples.copyOfRange(i, end))
            }
            offline.finish().trim()
        } else {
            ""
        }
    }

    private fun buildSummary(
        config: BenchmarkConfig,
        groundTruth: String,
        audioDurationSec: Float,
        initialAsrColdLoadMs: Long,
        initialLlmColdLoadMs: Long,
        repetitions: List<RepetitionMeasurement>,
        memStats: MemoryStats,
    ): BenchmarkSummary {
        val asrColdLoads = repetitions.map { it.asrColdLoadMs.toDouble() }
        val llmColdLoads = repetitions.map { it.llmColdLoadMs.toDouble() }
        val asrWalls = repetitions.map { it.asrWallMs.toDouble() }
        val asrRtfs = repetitions.map { it.asrRtf }
        val llmTtfts = repetitions.map { it.llmTtftMs }
        val llmPrefills = repetitions.map { it.llmPrefillTokPerSec }
        val llmDecodes = repetitions.map { it.llmDecodeTokPerSec }
        val llmWalls = repetitions.map { it.llmWallMs.toDouble() }
        val e2eWalls = repetitions.map { it.e2eWallMs.toDouble() }

        val lastRep = repetitions.lastOrNull()
        val rawAsrText = lastRep?.rawAsrText ?: ""
        val cleanedText = lastRep?.cleanedText ?: ""
        val rawWer = lastRep?.rawWer ?: 0.0
        val rawCer = lastRep?.rawCer ?: 0.0
        val cleanedWer = lastRep?.cleanedWer ?: 0.0
        val cleanedCer = lastRep?.cleanedCer ?: 0.0

        return BenchmarkSummary(
            mode = config.mode,
            asrModelLabel = config.asrModel?.label ?: "N/A",
            llmModelLabel = config.llmModel?.label ?: "N/A",
            computeTarget = if (config.llmModel != null) config.computeTarget else "N/A",
            speculativeUsed = config.speculativeDecoding && (config.llmModel?.supportsSpeculativeDecoding == true),
            sampleName = if (config.mode != BenchmarkMode.LLM_ONLY) config.audioSample.name else "Custom Text",
            audioDurationSec = audioDurationSec,
            repsCount = repetitions.size,
            coldReloadEachRep = config.coldReloadEachRep,
            initialAsrColdLoadMs = initialAsrColdLoadMs,
            initialLlmColdLoadMs = initialLlmColdLoadMs,
            asrColdLoadStat = BenchmarkStatsHelper.calculateMeanAndStdDev(asrColdLoads),
            llmColdLoadStat = BenchmarkStatsHelper.calculateMeanAndStdDev(llmColdLoads),
            asrWallStat = BenchmarkStatsHelper.calculateMeanAndStdDev(asrWalls),
            asrRtfStat = BenchmarkStatsHelper.calculateMeanAndStdDev(asrRtfs),
            llmTtftStat = BenchmarkStatsHelper.calculateMeanAndStdDev(llmTtfts),
            llmPrefillStat = BenchmarkStatsHelper.calculateMeanAndStdDev(llmPrefills),
            llmDecodeStat = BenchmarkStatsHelper.calculateMeanAndStdDev(llmDecodes),
            llmWallStat = BenchmarkStatsHelper.calculateMeanAndStdDev(llmWalls),
            e2eWallStat = BenchmarkStatsHelper.calculateMeanAndStdDev(e2eWalls),
            groundTruth = groundTruth,
            rawAsrText = rawAsrText,
            rawWer = rawWer,
            rawCer = rawCer,
            cleanedText = cleanedText,
            cleanedWer = cleanedWer,
            cleanedCer = cleanedCer,
            peakRamMb = memStats.peakMb,
            avgRamMb = memStats.avgMb,
            repetitions = repetitions,
        )
    }
}
