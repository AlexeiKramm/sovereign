package io.github.alexeikramm.sovereign.ui.cleanuplab

import android.content.Context
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.audio.WavReader
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPassTelemetry
import io.github.alexeikramm.sovereign.cleanup.CleanupPipeline
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmCleanupEngine
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModelSpec
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.DictionaryStore
import io.github.alexeikramm.sovereign.data.LiteRtProcessor
import io.github.alexeikramm.sovereign.dictation.DictationPipeline
import io.github.alexeikramm.sovereign.engine.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Live status for the lab's UI. [fraction] is 0..1 through the current phase. */
data class LabProgress(val phase: String, val fraction: Float, val detail: String)

/**
 * Runs the Cleanup Lab on the device, reusing the **real** dictation path so segmentation, seam healing
 * and conversation policy cannot drift from the pill:
 *
 * - **Phase A** ([buildUnitSet]): audio → ordered units, via [DictationPipeline] with cleanup off.
 * - **Phase B** ([runCleanup]): a cached unit set → cleaned, via [CleanupPipeline]. No ASR.
 * - **Full replay** ([runFullReplay]): audio → cleaned, via [DictationPipeline] with cleanup on.
 *
 * Frames are fed with **no wall-clock pacing**: endpointing is audio-time based, so the unit boundaries
 * match live dictation while the run takes a fraction of real time.
 */
class CleanupLabEngine(private val context: Context) {

    /** Phase A: transcribe the fixture and cache its ordered units. */
    suspend fun buildUnitSet(
        fixture: LabFixture,
        asrModel: AsrModelSpec,
        pauseSeconds: Float,
        onProgress: (LabProgress) -> Unit = {},
    ): LabUnitSet {
        val settings = AppSettings(context)
        val modelDir = asrModel.directory(context.filesDir)
        check(asrModel.isInstalled(modelDir)) { "${asrModel.label} is not installed" }

        val asr = LocalAsrEngine(
            modelDirectory = modelDir,
            modelSpec = asrModel,
            pauseSilenceSeconds = pauseSeconds,
            assetManager = context.assets,
        )
        val probes = Probes()
        val pipeline = newPipeline(
            asr = asr,
            cleanup = NoopCleanupEngine,
            settings = settings,
            rules = { "" },
            healing = 0,
            maxTurns = 0,
            probes = probes,
        )

        val wav = withContext(Dispatchers.IO) { WavReader.read(fixture.open(context)) }
        val samples = wav.samples
        val totalSec = samples.size.toFloat() / AsrModels.SAMPLE_RATE

        try {
            onProgress(LabProgress("Preparing", 0f, "Loading ${asrModel.label}…"))
            asr.load()
            pipeline.begin(fresh = true, cleanupEnabled = false)
            pipeline.beginSession()
            pipeline.open(asrModel.defaultLanguage)
            feed(samples, pipeline, probes) { elapsed ->
                onProgress(
                    LabProgress(
                        "Transcribing",
                        if (totalSec <= 0f) 0f else (elapsed / totalSec).coerceIn(0f, 1f),
                        "${"%.0f".format(elapsed)} / ${"%.0f".format(totalSec)} s · ${probes.units.size} units",
                    ),
                )
            }
            onProgress(LabProgress("Transcribing", 1f, "Settling the last unit…"))
            pipeline.finish()
            withContext(probes.mainDispatcher) { /* flush queued callbacks */ }
        } finally {
            pipeline.abort()
            withContext(Dispatchers.IO) { runCatching { asr.unload() } }
            probes.close()
        }

        val units = probes.units.mapIndexed { index, cut ->
            LabUnit(index = index + 1, text = cut.text, startSeconds = cut.startSeconds, endSeconds = cut.endSeconds)
        }
        return LabUnitSet(
            fixtureId = fixture.id,
            asrModelId = asrModel.id,
            pauseSeconds = pauseSeconds,
            units = units,
        )
    }

    /** Phase B: replay a cached unit set through one cleanup configuration. */
    suspend fun runCleanup(
        unitSet: LabUnitSet,
        config: LabConfig,
        onProgress: (LabProgress) -> Unit = {},
    ): LabRunResult {
        val settings = AppSettings(context)
        val spec = LiteRtLmModels.byId(config.llmModelId)
        val llm = newLlm(settings, spec)
        val probes = Probes()
        val cleanupDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + probes.mainDispatcher)
        val telemetry = mutableListOf<CleanupPassTelemetry>()

        val pipeline = CleanupPipeline(
            engine = llm,
            scope = scope,
            dispatcher = cleanupDispatcher,
            rules = { config.rules },
            healingSentencesCount = { config.healingSentences },
            maxTurns = { config.maxTurns },
            onCommitted = {},
            onPass = { telemetry.add(it) },
        )

        val startedAt = MonotonicClock.nowMs()
        try {
            onProgress(LabProgress("Cleaning", 0f, "Loading ${spec.label}…"))
            llm.load().getOrThrow()
            pipeline.begin()
            unitSet.units.forEachIndexed { i, unit ->
                pipeline.submit(unit.text)
                if (i % 5 == 0) {
                    onProgress(
                        LabProgress(
                            "Cleaning",
                            i.toFloat() / unitSet.units.size.coerceAtLeast(1),
                            "${i + 1} / ${unitSet.units.size} units",
                        ),
                    )
                }
            }
            pipeline.drain()
        } finally {
            probes.close()
            scope.cancel()
            cleanupDispatcher.close()
            withContext(Dispatchers.IO) { runCatching { llm.unload() } }
        }
        val wallMs = MonotonicClock.nowMs() - startedAt

        val units = alignUnits(unitSet, telemetry)
        val rawText = unitSet.rawText
        // The assembled committed text, not a join of per-pass outputs: with seam healing the model's
        // reply repeats the overlap, and it is the pipeline's `overlapToDelete` bookkeeping (inside
        // `UtteranceCleanupState`) that removes the duplicate. Joining raw outputs double-counts it.
        val cleanedText = pipeline.state.carriedTail.ifBlank { units.joinToString(" ") { it.cleanedUnit } }
        return LabRunResult(
            config = config,
            units = units,
            rawText = rawText,
            cleanedText = cleanedText,
            wallMs = wallMs,
            asrActiveMs = 0L,
            llmActiveMs = units.sumOf { it.activeMs },
            metrics = CleanupLabMetrics.compute(rawText, cleanedText, units),
            positions = CleanupLabMetrics.positions(units),
        )
    }

    /** Full replay: audio → cleaned, with the real ASR/cleanup overlap. Measures latency too. */
    suspend fun runFullReplay(
        fixture: LabFixture,
        asrModel: AsrModelSpec,
        pauseSeconds: Float,
        config: LabConfig,
        onProgress: (LabProgress) -> Unit = {},
    ): LabRunResult {
        val settings = AppSettings(context)
        val modelDir = asrModel.directory(context.filesDir)
        check(asrModel.isInstalled(modelDir)) { "${asrModel.label} is not installed" }
        val spec = LiteRtLmModels.byId(config.llmModelId)

        val asr = LocalAsrEngine(
            modelDirectory = modelDir,
            modelSpec = asrModel,
            pauseSilenceSeconds = pauseSeconds,
            assetManager = context.assets,
        )
        val llm = newLlm(settings, spec)
        val probes = Probes()
        val telemetry = mutableListOf<CleanupPassTelemetry>()
        val pipeline = newPipeline(
            asr = asr,
            cleanup = llm,
            settings = settings,
            rules = { config.rules },
            healing = config.healingSentences,
            maxTurns = config.maxTurns,
            probes = probes,
            onPass = { telemetry.add(it) },
        )

        val wav = withContext(Dispatchers.IO) { WavReader.read(fixture.open(context)) }
        val samples = wav.samples
        val totalSec = samples.size.toFloat() / AsrModels.SAMPLE_RATE

        val startedAt = MonotonicClock.nowMs()
        try {
            onProgress(LabProgress("Preparing", 0f, "Loading ${asrModel.label} + ${spec.label}…"))
            asr.load()
            llm.load().getOrThrow()
            pipeline.begin(fresh = true, cleanupEnabled = true)
            pipeline.beginSession()
            pipeline.open(asrModel.defaultLanguage)
            feed(samples, pipeline, probes) { elapsed ->
                onProgress(
                    LabProgress(
                        "Replaying",
                        if (totalSec <= 0f) 0f else (elapsed / totalSec).coerceIn(0f, 1f),
                        "${"%.0f".format(elapsed)} / ${"%.0f".format(totalSec)} s · ${probes.units.size} units",
                    ),
                )
            }
            onProgress(LabProgress("Replaying", 1f, "Draining the last cleanup pass…"))
            pipeline.finish()
            withContext(probes.mainDispatcher) { /* flush queued callbacks */ }
        } finally {
            pipeline.abort()
            withContext(Dispatchers.IO) {
                runCatching { asr.unload() }
                runCatching { llm.unload() }
            }
            probes.close()
        }
        val wallMs = MonotonicClock.nowMs() - startedAt

        val units = probes.units.mapIndexedNotNull { i, cut ->
            val pass = telemetry.getOrNull(i) ?: return@mapIndexedNotNull null
            LabUnitResult(
                index = i + 1,
                rawUnit = cut.text,
                modelInput = pass.unit,
                cleanedUnit = pass.cleanedText,
                cleanedOk = pass.cleaned,
                tokensBefore = pass.tokensBefore,
                tokensAfter = pass.tokensAfter,
                turnsInConversation = pass.turnsInConversation,
                rebuiltAfter = pass.rebuiltAfter,
                rebuildReason = pass.rebuildReason?.name,
                audioStartSeconds = cut.startSeconds,
                audioEndSeconds = cut.endSeconds,
                activeMs = pass.activeMs,
                ttftMs = pass.ttftMs,
                decodeTimeMs = pass.decodeTimeMs,
                promptTokens = pass.promptTokens,
                generatedTokens = pass.generatedTokens,
            )
        }
        val rawText = pipeline.rawText
        val cleanedText = pipeline.committedText
        return LabRunResult(
            config = config,
            units = units,
            rawText = rawText,
            cleanedText = cleanedText,
            wallMs = wallMs,
            asrActiveMs = pipeline.totalActiveAsrMs,
            llmActiveMs = pipeline.totalActiveLlmMs,
            metrics = CleanupLabMetrics.compute(rawText, cleanedText, units),
            positions = CleanupLabMetrics.positions(units),
        )
    }

    // ---- plumbing ----

    private fun newLlm(settings: AppSettings, spec: LiteRtLmModelSpec): LiteRtLmCleanupEngine =
        LiteRtLmCleanupEngine(
            context = context,
            modelSpec = spec,
            processor = if (spec.supportsGpu) settings.litertProcessor else LiteRtProcessor.CPU,
            speculativeDecoding = settings.litertSpeculativeDecoding && spec.supportsSpeculativeDecoding,
            vocabulary = DictionaryStore(context)::getEntries,
        )

    private fun newPipeline(
        asr: LocalAsrEngine,
        cleanup: CleanupEngine,
        settings: AppSettings,
        rules: () -> String,
        healing: Int,
        maxTurns: Int,
        probes: Probes,
        onPass: (CleanupPassTelemetry) -> Unit = {},
    ): DictationPipeline = DictationPipeline(
        asrEngine = asr,
        asrDispatcher = probes.asrDispatcher,
        cleanupEngine = cleanup,
        cleanupDispatcher = probes.cleanupDispatcher,
        scope = probes.scope,
        mainDispatcher = probes.mainDispatcher,
        timings = { settings.listenerTimings() },
        cleanupRules = rules,
        healingSentencesCount = { healing },
        maxTurns = { maxTurns },
        onText = { _, _, _ -> },
        onUnitCut = { text -> probes.onCut(text) },
        onCleanupPass = onPass,
    )

    /** Align per-pass telemetry with the cached raw units, in order. */
    private fun alignUnits(
        unitSet: LabUnitSet,
        telemetry: List<CleanupPassTelemetry>,
    ): List<LabUnitResult> = telemetry.mapIndexed { i, pass ->
        val raw = unitSet.units.getOrNull(i)?.text ?: pass.unit
        LabUnitResult(
            index = i + 1,
            rawUnit = raw,
            modelInput = pass.unit,
            cleanedUnit = pass.cleanedText,
            cleanedOk = pass.cleaned,
            tokensBefore = pass.tokensBefore,
            tokensAfter = pass.tokensAfter,
            turnsInConversation = pass.turnsInConversation,
            rebuiltAfter = pass.rebuiltAfter,
            rebuildReason = pass.rebuildReason?.name,
            audioStartSeconds = unitSet.units.getOrNull(i)?.startSeconds ?: 0f,
            audioEndSeconds = unitSet.units.getOrNull(i)?.endSeconds ?: 0f,
            activeMs = pass.activeMs,
            ttftMs = pass.ttftMs,
            decodeTimeMs = pass.decodeTimeMs,
            promptTokens = pass.promptTokens,
            generatedTokens = pass.generatedTokens,
        )
    }

    private suspend fun feed(
        samples: FloatArray,
        pipeline: DictationPipeline,
        probes: Probes,
        onProgress: (Float) -> Unit,
    ) {
        val frameSamples = AsrModels.SAMPLE_RATE / 10 // 100 ms
        var index = 0
        while (index < samples.size) {
            val end = minOf(index + frameSamples, samples.size)
            probes.audioSeconds = end.toFloat() / AsrModels.SAMPLE_RATE
            val pcm = floatToS16Le(samples, index, end)
            pipeline.onFrames(pcm, pcm.size)
            index = end
            onProgress(probes.audioSeconds)
        }
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

    /** A cut unit with the audio position it ended at. */
    data class CutUnit(val text: String, val startSeconds: Float, val endSeconds: Float)

    /** Per-run scratch: dispatchers, the scope, and the ordered cut units. */
    private class Probes {
        val asrDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val cleanupDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val mainDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

        val units = mutableListOf<CutUnit>()

        @Volatile
        var audioSeconds: Float = 0f

        private var lastEnd = 0f

        @Synchronized
        fun onCut(text: String) {
            if (text.isBlank()) return
            val start = lastEnd
            lastEnd = audioSeconds
            units.add(CutUnit(text = text, startSeconds = start, endSeconds = audioSeconds))
        }

        fun close() {
            scope.cancel()
            asrDispatcher.close()
            cleanupDispatcher.close()
            mainDispatcher.close()
        }
    }

    /** A cleanup engine that does nothing: Phase A runs the ASR path without loading an LLM. */
    private object NoopCleanupEngine : CleanupEngine {
        override val isLoaded: Boolean = true
        override val lastLoadMs: Long = 0L
        override suspend fun load(): Result<Unit> = Result.success(Unit)
        override suspend fun clean(unit: String, rules: String): CleanupPass =
            CleanupPass.failed(unit, "cleanup disabled")
        override fun unload() {}
        override val tokenCount: Int = 0
        override val contextTokens: Int = 0
    }
}
