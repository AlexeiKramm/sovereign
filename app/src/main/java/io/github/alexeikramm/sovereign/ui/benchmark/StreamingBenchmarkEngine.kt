package io.github.alexeikramm.sovereign.ui.benchmark

import android.content.Context
import android.util.Log
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.audio.WavReader
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPrompt
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmCleanupEngine
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.DictionaryStore
import io.github.alexeikramm.sovereign.data.LiteRtProcessor
import io.github.alexeikramm.sovereign.data.ProfileStore
import io.github.alexeikramm.sovereign.dictation.DictationPipeline
import io.github.alexeikramm.sovereign.engine.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runs one [StreamingCell] through the real [DictationPipeline] on the device, streaming the fixture
 * as 100 ms s16le frames paced at wall-clock **1×** (the whole point: perceived latency, not a
 * throughput sweep). Collects the timestamps the pipeline emits and folds them into a
 * [StreamingCellResult].
 *
 * Cold/warm mirrors the pill: a **warm** cell has both models resident *before* the utterance starts
 * (steady state, cleanup session open on a loaded engine), while a **cold** cell warms them *while*
 * the audio streams — ASR on the ASR worker, cleanup lazy-loading on its first unit on the cleanup
 * worker, overlapping the next ASR segment. Nothing is preloaded sequentially.
 */
class StreamingBenchmarkEngine(private val context: Context) {

    suspend fun runCell(
        cell: StreamingCell,
        fixture: DictationFixture,
        onProgress: (String) -> Unit = {},
    ): StreamingCellResult {
        val settings = AppSettings(context)
        val dictionaryStore = DictionaryStore(context)
        val profileStore = ProfileStore(context)
        val activeProfile = profileStore.activeProfile()
        val variant = CleanupPrompt.Variant.fromId(activeProfile.id)
        val customRules = if (!activeProfile.isBuiltin) activeProfile.rules else null

        val modelDir = cell.asrModel.directory(context.filesDir)
        check(cell.asrModel.isInstalled(modelDir)) { "${cell.asrModel.label} is not installed" }

        val asr = LocalAsrEngine(
            modelDirectory = modelDir,
            modelSpec = cell.asrModel,
            pauseSilenceSeconds = cell.pauseSeconds,
            assetManager = context.assets,
        )

        val llmDelegate = LiteRtLmCleanupEngine(
            context = context,
            modelSpec = settings.litertModel,
            processor = if (settings.litertModel.supportsGpu) settings.litertProcessor else LiteRtProcessor.CPU,
            speculativeDecoding = settings.litertSpeculativeDecoding && settings.litertModel.supportsSpeculativeDecoding,
            vocabulary = dictionaryStore::getEntries,
        )
        val cleanupEngine: CleanupEngine = RulesCleanupEngine(llmDelegate, { customRules })

        val asrDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val cleanupDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

        val cutTimes = mutableListOf<Long>()
        val commitTimes = mutableListOf<Long>()
        var firstPartialMs = -1L
        var t0 = 0L
        fun rel(): Long = MonotonicClock.nowMs() - t0

        val pipeline = DictationPipeline(
            asrEngine = asr,
            asrDispatcher = asrDispatcher,
            cleanupEngine = cleanupEngine,
            cleanupDispatcher = cleanupDispatcher,
            scope = scope,
            mainDispatcher = mainDispatcher,
            timings = { settings.listenerTimings() },
            cleanupVariant = { variant },
            healingSentencesCount = { cell.healingSentences },
            onText = { _, _ -> },
            onPartialText = { if (firstPartialMs < 0L) firstPartialMs = rel() },
            onUnitCut = { cutTimes.add(rel()) },
            onUnitCommitted = { commitTimes.add(rel()) },
        )

        try {
            onProgress("Loading audio sample ${fixture.id}")
            val wav = withContext(Dispatchers.IO) { WavReader.read(context.assets.open(fixture.assetWavPath)) }
            val samples = wav.samples
            val frameSamples = AsrModels.SAMPLE_RATE / 10 // 100 ms

            val language = cell.asrModel.defaultLanguage

            // Warm = steady state: both models resident *before* the utterance begins, so
            // `cleanup.begin` opens a KV-cache session on a loaded engine — exactly like a warm pill.
            // Cold deliberately does not preload (see below), so `startSession` no-ops and cleanup
            // lazy-loads on its first unit.
            if (!cell.cold) {
                onProgress("Warming models")
                if (cell.cleanup) llmDelegate.load().getOrThrow()
            }

            pipeline.begin(fresh = true, cleanupEnabled = cell.cleanup)
            pipeline.beginSession()

            suspend fun feed() {
                var index = 0
                while (index < samples.size) {
                    val end = minOf(index + frameSamples, samples.size)
                    val pcm = floatToS16Le(samples, index, end)
                    pipeline.onFrames(pcm, pcm.size)
                    index = end
                    val target = t0 + (index.toLong() * 1000L) / AsrModels.SAMPLE_RATE
                    val now = MonotonicClock.nowMs()
                    if (target > now) delay(target - now)
                }
            }

            if (cell.cold) {
                // Mirror the pill's warmEngines: with the stage prepared and nothing loaded, start
                // both model loads eagerly in the background while the audio streams. The first chunk
                // waits for the in-flight cleanup load, then reuses one conversation for the rest.
                onProgress("Streaming — models warm as audio arrives")
                t0 = MonotonicClock.nowMs()
                val warmJob = scope.launch {
                    withContext(asrDispatcher) { asr.load() }
                    if (cell.cleanup) withContext(cleanupDispatcher) { llmDelegate.load() }
                }
                val openJob = scope.launch { pipeline.open(language) }
                feed()
                openJob.join()
                warmJob.join()
            } else {
                onProgress("Streaming — models warm")
                pipeline.open(language)
                t0 = MonotonicClock.nowMs()
                feed()
            }

            val stopAt = MonotonicClock.nowMs()
            pipeline.finish()
            withContext(mainDispatcher) { /* flush queued pipeline callbacks */ }
            val wallMs = MonotonicClock.nowMs() - t0
            val tailMs = MonotonicClock.nowMs() - stopAt

            val firstCut = cutTimes.firstOrNull()
            val firstCommit = commitTimes.firstOrNull()

            return StreamingCellResult(
                cell = cell,
                fixtureId = fixture.id,
                audioDurationSec = samples.size.toFloat() / AsrModels.SAMPLE_RATE,
                unitCount = cutTimes.size,
                timeToFirstTextMs = firstCommit ?: -1L,
                deadAirMs = if (firstCommit != null && firstPartialMs >= 0L) firstCommit - firstPartialMs else -1L,
                endpointGatingMs = if (firstCut != null && firstPartialMs >= 0L) firstCut - firstPartialMs else -1L,
                cleanupLagMs = if (firstCut != null && firstCommit != null) firstCommit - firstCut else -1L,
                visibleCadenceMs = StreamingMetrics.gaps(commitTimes),
                commitCadenceMs = StreamingMetrics.gaps(commitTimes),
                tailLatencyMs = tailMs,
                asrActiveMs = pipeline.totalActiveAsrMs,
                llmActiveMs = pipeline.totalActiveLlmMs,
                wallMs = wallMs,
                committedText = pipeline.committedText,
                rawText = pipeline.rawText,
            )
        } finally {
            pipeline.abort()
            withContext(Dispatchers.IO) {
                runCatching { asr.unload() }
                runCatching { llmDelegate.unload() }
            }
            scope.cancel()
            asrDispatcher.close()
            cleanupDispatcher.close()
            mainDispatcher.close()
        }
    }

    /** Fills in the active profile's custom rules, which [DictationPipeline] does not carry itself. */
    private class RulesCleanupEngine(
        private val delegate: CleanupEngine,
        private val rules: () -> String?,
    ) : CleanupEngine by delegate {
        override suspend fun clean(
            unit: String,
            context: String?,
            variant: CleanupPrompt.Variant,
            customRules: String?,
        ): CleanupPass = delegate.clean(unit, context, variant, customRules ?: rules())

        override fun startSession(variant: CleanupPrompt.Variant, customRules: String?) =
            delegate.startSession(variant, customRules ?: rules())
    }

    private fun floatToS16Le(samples: FloatArray, from: Int, to: Int): ByteArray {
        val out = ByteArray((to - from) * 2)
        for (i in from until to) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[(i - from) * 2] = (v and 0xFF).toByte()
            out[(i - from) * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/** Appends one row per cell to `filesDir/streaming_benchmark.csv`; the run's durable record. */
object StreamingCsvLogger {
    private const val HEADER =
        "timestamp,asr_model,cleanup,pause_s,healing,cold,fixture,audio_dur_s,units,first_text_ms," +
            "dead_air_ms,endpoint_gating_ms,cleanup_lag_ms,tail_ms,asr_active_ms,llm_active_ms,wall_ms," +
            "visible_cadence_ms,committed_text,raw_text"

    fun log(context: Context, result: StreamingCellResult): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val line = listOf(
            stamp,
            escape(result.cell.asrModel.label),
            result.cell.cleanup.toString(),
            String.format(Locale.US, "%.2f", result.cell.pauseSeconds),
            result.cell.healingSentences.toString(),
            result.cell.cold.toString(),
            result.fixtureId,
            String.format(Locale.US, "%.2f", result.audioDurationSec),
            result.unitCount.toString(),
            result.timeToFirstTextMs.toString(),
            result.deadAirMs.toString(),
            result.endpointGatingMs.toString(),
            result.cleanupLagMs.toString(),
            result.tailLatencyMs.toString(),
            result.asrActiveMs.toString(),
            result.llmActiveMs.toString(),
            result.wallMs.toString(),
            result.visibleCadenceMs.joinToString(" ") { it.toString() },
            escape(result.committedText),
            escape(result.rawText),
        ).joinToString(",")

        try {
            val file = File(context.filesDir, "streaming_benchmark.csv")
            val isNew = !file.exists() || file.length() == 0L
            file.appendText(if (isNew) "$HEADER\n$line\n" else "$line\n")
        } catch (e: Exception) {
            Log.e("StreamingBenchmark", "Failed to write streaming_benchmark.csv: ${e.message}")
        }
        Log.i("StreamingBenchmark", "CSV: $line")
        return line
    }

    private fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
