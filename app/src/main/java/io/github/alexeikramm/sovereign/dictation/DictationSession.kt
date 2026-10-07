package io.github.alexeikramm.sovereign.dictation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.audio.WavRecorder
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPassTelemetry
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmCleanupEngine
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.DictionaryStore
import io.github.alexeikramm.sovereign.data.ProfileStore
import io.github.alexeikramm.sovereign.engine.ModelResidency
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The headless engine/audio/residency layer, cut out of `FloatingOverlayService` so the floating
 * pill and the desktop server are two adapters over **one** set of engines (D-106, `REVIEW.md`
 * H1/H2/H3). This mirrors what D-080 §3 did when it extracted [DictationPipeline].
 *
 * What it owns:
 * - the [LocalAsrEngine], the cleanup engine (`LiteRtLmCleanupEngine` behind [CurrentCleanupEngine]),
 *   the single [WavRecorder], and the ASR/cleanup single-thread dispatchers;
 * - [DictationPipeline], and through it the utterance text and its callbacks;
 * - engine warm-up and the four residency triggers' unload path, plus the idle and keyboard-close
 *   timers and the [ModelResidency] guard.
 *
 * What it deliberately does **not** own: Compose/UI state, windows, animation, the silence monitor,
 * usage stats and history. The pill becomes an adapter that mirrors [onText] into its Compose state
 * and calls back in for capture control; the server becomes a second, thinner adapter.
 *
 * Threading mirrors the previous in-service code exactly: warm/unload switch to each engine's own
 * single-thread dispatcher; [finish] stops the recorder on IO then drains the pipeline.
 */
class DictationSession(
    context: Context,
    private val settings: AppSettings,
    private val profileStore: ProfileStore,
    private val dictionaryStore: DictionaryStore,
    private val scope: CoroutineScope,
    private val onText: (committed: String, raw: String, provisional: String) -> Unit,
    private val onCleanupUnavailable: () -> Unit = {},
    private val onPartialText: (String) -> Unit = {},
    private val onUnitCut: (String) -> Unit = {},
    private val onUnitCommitted: (String) -> Unit = {},
    private val onCleanupPass: (CleanupPassTelemetry) -> Unit = {},
    /** Raw mic RMS per frame, for the pill's level meter. [0, 1]. */
    private val onAudioLevel: (Float) -> Unit = {},
    /** Fires whenever either engine's loaded state changes, so the pill can re-render its warmth. */
    private val onModelResidencyChanged: (asrLoaded: Boolean, cleanupLoaded: Boolean) -> Unit = { _, _ -> },
) {

    private val appContext = context.applicationContext

    private val asrDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sovereign-floating-asr")
    }.asCoroutineDispatcher()

    private val cleanupDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sovereign-floating-cleanup")
    }.asCoroutineDispatcher()

    /** The one recorder: the pill and the desktop server both capture through this. */
    private val recorder = WavRecorder()

    private val localEngine = LocalAsrEngine(
        modelDirectory = settings.asrModel.directory(appContext.filesDir),
        modelSpec = settings.asrModel,
        assetManager = appContext.assets,
    )

    private var cleanupEngine: CleanupEngine? = null

    /** The process-wide capture arbiter; the pill and the server both claim this one (D-106). */
    val arbiter: SessionArbiter = SessionArbiter.shared

    // Residency: the engines are released by the triggers funnelled through [unloadEngines].
    private var asrModelLoaded = false
    private var cleanupModelLoaded = false
    /** When the last capture finished (recording and processing both done); 0 before the first. */
    private var lastCaptureEndedAtMs = 0L
    private var idleUnloadJob: Job? = null
    private var keyboardCloseUnloadJob: Job? = null
    /**
     * True while an utterance (or a whole-transcript reprocess) is being finalised — recorder stop,
     * cleanup drain, punctuation. Feeds [ModelResidency.canUnload] so a trigger cannot release the
     * engines inside that window. Read and written on the main dispatcher; volatile because the
     * server adapter (Chunk 1) may call [unloadEngines] from elsewhere.
     */
    @Volatile
    private var processing = false
    /**
     * D-105: while the desktop server is serving, the screen-off and keyboard-close triggers are
     * suppressed. Set by the server service (Chunk 1); false for the pill.
     */
    @Volatile
    private var serverMode = false

    private val pipeline = DictationPipeline(
        asrEngine = localEngine,
        asrDispatcher = asrDispatcher,
        cleanupEngine = CurrentCleanupEngine(),
        cleanupDispatcher = cleanupDispatcher,
        scope = scope,
        mainDispatcher = Dispatchers.Main.immediate,
        timings = { settings.listenerTimings() },
        cleanupRules = { profileStore.activeProfile().rules },
        healingSentencesCount = { settings.healingSentencesCount },
        maxTurns = { settings.maxTurns },
        onText = onText,
        onCleanupUnavailable = onCleanupUnavailable,
        onPartialText = onPartialText,
        onUnitCut = onUnitCut,
        onUnitCommitted = onUnitCommitted,
        onCleanupPass = onCleanupPass,
        cleanupSessionStartGraceMs = CLEANUP_WARMUP_GRACE_MS,
    )

    init {
        recorder.frameSink = { buffer, read ->
            pipeline.onFrames(buffer, read)
            // Raw RMS only: the visual level is derived on a perceptual (dB) scale by the caller, so
            // the mic gain is not baked in here.
            onAudioLevel(calculatePcmRms(buffer, read).coerceIn(0f, 1f))
        }
    }

    // --- State the adapters read ---------------------------------------------------------------

    val isRecording: Boolean get() = recorder.isRecording

    /** Elapsed audio so far, derived from the captured bytes; the silence monitor reads it. */
    val capturedSeconds: Double get() = recorder.capturedSeconds

    /** The audio length of the last capture, valid after [finish]/[abort] (the PCM buffer is freed). */
    val lastCapturedSeconds: Double get() = recorder.lastCapturedSeconds

    /** True while an utterance is finalising or the transcript is being reprocessed. */
    val isProcessing: Boolean get() = processing

    val asrLoaded: Boolean get() = asrModelLoaded
    val cleanupLoaded: Boolean get() = cleanupModelLoaded

    val rawText: String get() = pipeline.rawText
    val committedText: String get() = pipeline.committedText
    val provisionalText: String get() = pipeline.provisionalText

    /** The text to deliver: the settled text, or the raw words if nothing settled. */
    val deliveryText: String get() = pipeline.deliveryText

    val isCleanupEngaged: Boolean get() = pipeline.isCleanupEngaged
    val totalActiveAsrMs: Long get() = pipeline.totalActiveAsrMs
    val totalActiveLlmMs: Long get() = pipeline.totalActiveLlmMs
    val boundaryState get() = pipeline.boundaryState

    // --- Capture lifecycle ---------------------------------------------------------------------

    /**
     * Opens an utterance: cancels any pending unload, seeds the pipeline, applies the current model
     * and pause settings (both can unload a stale engine), and warms the engines. Panel/UI state
     * stays with the adapter.
     */
    fun begin(fresh: Boolean, cleanupEnabled: Boolean) {
        // A capture is about to need the engines: a pending idle/keyboard-close unload must not fire
        // under it (the guard in `unloadEngines` is the backstop, this keeps them from racing).
        stopIdleUnload()
        stopKeyboardCloseUnload()

        pipeline.begin(fresh = fresh, cleanupEnabled = cleanupEnabled)

        localEngine.applyModel(settings.asrModel, appContext.filesDir)
        localEngine.applyPauseSilence(settings.pauseSilenceSeconds)

        // Warm at capture start and nowhere else. After the two apply calls, because both can unload
        // a resident model that no longer matches the settings.
        warmEngines()
    }

    /** Starts the ASR worker. Separate from [begin] so the caller can warm the engines in between. */
    fun beginSession() = pipeline.beginSession()

    /** Opens the recogniser and records whether ASR came up. The returned state is for the caller. */
    suspend fun open(language: String): LocalAsrEngine.Availability {
        val availability = pipeline.open(language)
        setAsrLoaded(availability is LocalAsrEngine.Availability.Loaded)
        return availability
    }

    /** Feed a raw mic frame. The pill's recorder sink already routes these; the server may too. */
    fun onFrames(bytes: ByteArray, count: Int) = pipeline.onFrames(bytes, count)

    /** Starts the microphone. Throws when `AudioRecord` cannot start (the caller cancels). */
    fun startRecording() = recorder.start()

    /**
     * Ends the utterance: stop the recorder, then flush the ASR tail, drain cleanup and apply
     * terminal punctuation. Returns the settled text.
     *
     * [onRecorderStopped] fires on the caller's dispatcher once the microphone is shut and before
     * the drain — the pill releases `FLAG_KEEP_SCREEN_ON` there.
     */
    suspend fun finish(onRecorderStopped: () -> Unit = {}): String {
        processing = true
        try {
            withContext(Dispatchers.IO) { recorder.stop() }
            onRecorderStopped()
            return pipeline.finish()
        } finally {
            processing = false
        }
    }

    /** Drops the utterance without committing it, stopping the recorder if it is still running. */
    fun abort() {
        if (recorder.isRecording) runCatching { recorder.stop() }
        pipeline.abort()
    }

    /** [abort], but waits for the ASR and cleanup workers before returning (Cleanup Lab, tests). */
    suspend fun abortAndJoin() {
        if (recorder.isRecording) runCatching { recorder.stop() }
        pipeline.abortAndJoin()
    }

    fun reset() = pipeline.reset()

    /** Replaces the settled text wholesale (the pill's "reprocess full text" action). */
    fun replaceCommitted(text: String) = pipeline.replaceCommitted(text)

    /**
     * Re-runs the whole transcript through the cleanup LLM, on the cleanup dispatcher. Returns null
     * when the model cannot be loaded, so the caller keeps the text on screen and shows a note.
     * Bracketed as "processing" so a residency trigger cannot release the LLM mid-reprocess.
     */
    suspend fun reprocess(text: String, rules: String): CleanupPass? {
        processing = true
        try {
            return withContext(cleanupDispatcher) {
                val engine = engineForUtterance()
                if (!engine.isLoaded) {
                    val loadRes = engine.load()
                    setCleanupLoaded(engine.isLoaded)
                    if (loadRes.isFailure) return@withContext null
                } else {
                    setCleanupLoaded(true)
                }
                engine.clean(unit = text, rules = rules)
            }
        } finally {
            processing = false
        }
    }

    // --- Residency -----------------------------------------------------------------------------

    /** A capture's recording and processing are both done, so the engines start their idle window. */
    fun onCaptureFinished() {
        lastCaptureEndedAtMs = SystemClock.elapsedRealtime()
        restartIdleUnload()
    }

    /** D-105: the server service turns this on while it is serving; it suppresses two triggers. */
    fun setServerMode(active: Boolean) {
        serverMode = active
    }

    /** The screen-off trigger. Suppressed while the server serves — a docked screen is expected off. */
    fun onScreenOff() {
        if (!ModelResidency.triggerAllowed(ModelResidency.Trigger.SCREEN_OFF, serverMode)) {
            Log.i(TAG, "Screen off — ignored while the server is active")
            return
        }
        Log.i(TAG, "Screen off — releasing engines")
        unloadEngines("screen off")
    }

    /** The keyboard-close grace unload. A no-op while the server serves (D-105). */
    fun scheduleKeyboardCloseUnload() {
        stopKeyboardCloseUnload()
        if (!ModelResidency.triggerAllowed(ModelResidency.Trigger.KEYBOARD_CLOSE, serverMode)) return
        if (!settings.unloadOnKeyboardClose) return
        keyboardCloseUnloadJob = scope.launch {
            delay(KEYBOARD_CLOSE_GRACE_MS)
            if (settings.unloadOnKeyboardClose) unloadEngines("keyboard closed")
        }
    }

    fun stopKeyboardCloseUnload() {
        keyboardCloseUnloadJob?.cancel()
        keyboardCloseUnloadJob = null
    }

    /**
     * Releases both engines, unless a capture is in flight. Every trigger calls this and nothing else
     * unloads, so "never yank a model out from under a running utterance" has one home.
     *
     * Unload is idempotent: releasing an already-cold engine is a no-op, but the early return keeps
     * the log honest about what actually happened.
     */
    fun unloadEngines(reason: String) {
        stopIdleUnload()
        stopKeyboardCloseUnload()

        val serverUtterance = arbiter.holder == SessionArbiter.Owner.SERVER
        if (!ModelResidency.canUnload(
                recording = recorder.isRecording,
                processing = processing,
                serverUtterance = serverUtterance,
            )
        ) {
            Log.i(TAG, "Unload skipped ($reason): capture in flight")
            return
        }
        if (!asrModelLoaded && !cleanupModelLoaded) {
            Log.i(TAG, "Unload skipped ($reason): nothing is loaded")
            return
        }

        Log.i(TAG, "Unloading engines ($reason)")
        scope.launch {
            // Each engine is released on its own single-thread dispatcher, matching how it loads.
            withContext(asrDispatcher) { localEngine.unload() }
            setAsrLoaded(false)
            withContext(cleanupDispatcher) { cleanupEngine?.unload() }
            setCleanupLoaded(false)
            Log.i(TAG, "Engines unloaded ($reason)")
        }
    }

    /** Tears the session down for good: unload both engines and close the dispatchers. */
    fun shutdown() {
        stopIdleUnload()
        stopKeyboardCloseUnload()
        localEngine.unload()
        cleanupEngine?.unload()
        setAsrLoaded(false)
        setCleanupLoaded(false)
        asrDispatcher.close()
        cleanupDispatcher.close()
    }

    /** (Re)arms the idle-timeout unload from "now". `modelIdleTimeoutMinutes == 0` means never. */
    private fun restartIdleUnload() {
        stopIdleUnload()
        val minutes = settings.modelIdleTimeoutMinutes
        if (minutes <= 0) return
        idleUnloadJob = scope.launch {
            delay(minutes.toLong() * ModelResidency.MILLIS_PER_MINUTE)
            if (ModelResidency.idleTimeoutReached(
                    idleMillis = SystemClock.elapsedRealtime() - lastCaptureEndedAtMs,
                    timeoutMinutes = minutes,
                )
            ) {
                unloadEngines("idle ${minutes}m")
            }
        }
    }

    private fun stopIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
    }

    /**
     * Loads everything the current configuration needs, in the order the latency cares about:
     * **ASR first** (the first frames must be recognised as soon as possible), then the cleanup LLM
     * once ASR has reported loaded.
     *
     * Sequential rather than parallel on purpose: the engines run on separate single-thread
     * dispatchers, and racing two large allocations for CPU and memory bandwidth is exactly how a
     * low-end device spikes into OOM. The LLM is not needed until the first *unit* is emitted (after
     * a pause), so serialising costs almost nothing in perceived latency.
     */
    private fun warmEngines() {
        scope.launch {
            Log.i(TAG, "Warming engines at capture start")
            val asrAvailable = withContext(asrDispatcher) { localEngine.load() }
            setAsrLoaded(asrAvailable is LocalAsrEngine.Availability.Loaded)
            Log.i(TAG, "ASR warm: $asrAvailable in ${localEngine.lastLoadMillis}ms")
            // No ASR means no text, so there is nothing for cleanup to do; skip the allocation.
            if (!asrModelLoaded) return@launch
            if (!settings.cleanupEnabled) return@launch

            val engine = engineForUtterance()
            if (engine.isLoaded) {
                setCleanupLoaded(true)
                Log.i(TAG, "Cleanup model already warm")
                return@launch
            }
            val result = withContext(cleanupDispatcher) { engine.load() }
            setCleanupLoaded(engine.isLoaded)
            if (result.isFailure) {
                Log.w(TAG, "Cleanup model warm-up failed: ${result.exceptionOrNull()?.message}")
            } else {
                Log.i(TAG, "Cleanup model warm in ${engine.lastLoadMs}ms")
            }
        }
    }

    private fun setAsrLoaded(loaded: Boolean) {
        asrModelLoaded = loaded
        onModelResidencyChanged(asrModelLoaded, cleanupModelLoaded)
    }

    private fun setCleanupLoaded(loaded: Boolean) {
        cleanupModelLoaded = loaded
        onModelResidencyChanged(asrModelLoaded, cleanupModelLoaded)
    }

    private fun engineForUtterance(): CleanupEngine {
        cleanupEngine?.let { existing ->
            if (existing.isServing(settings)) return existing
            existing.unload()
            setCleanupLoaded(false)
        }
        return LiteRtLmCleanupEngine(
            context = appContext,
            modelSpec = settings.litertModel,
            processor = settings.litertProcessor,
            speculativeDecoding = settings.litertSpeculativeDecoding,
            vocabulary = dictionaryStore::getEntries,
        ).also { cleanupEngine = it }
    }

    private fun calculatePcmRms(bytes: ByteArray, length: Int): Float {
        val samplesCount = length / 2
        if (samplesCount == 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until samplesCount) {
            val offset = i * 2
            if (offset + 1 >= length) break
            val low = bytes[offset].toInt() and 0xFF
            val high = bytes[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val norm = sample / 32768.0
            sumSquares += norm * norm
        }
        return sqrt(sumSquares / samplesCount).toFloat()
    }

    private inner class CurrentCleanupEngine : CleanupEngine {
        override val isLoaded: Boolean get() = cleanupEngine?.isLoaded == true
        override val lastLoadMs: Long get() = cleanupEngine?.lastLoadMs ?: 0L
        override suspend fun load(): Result<Unit> = engineForUtterance().load()

        override suspend fun clean(
            unit: String,
            rules: String,
        ): CleanupPass = engineForUtterance().clean(unit, rules)

        override fun unload() {
            cleanupEngine?.unload()
            setCleanupLoaded(false)
        }

        override fun startSession(rules: String) {
            engineForUtterance().startSession(rules)
        }

        override fun closeSession() {
            cleanupEngine?.closeSession()
        }

        // Read straight from the real engine, never via `engineForUtterance()`: a getter must not
        // construct an engine as a side effect. These three were missing once, so the pipeline read
        // 0/0 and the rebuild trigger was unreachable (the KV-overflow bug).
        override val tokenCount: Int get() = cleanupEngine?.tokenCount ?: 0
        override val contextTokens: Int get() = cleanupEngine?.contextTokens ?: 0
        override fun onConversationRebuilt(usedTokens: Int, capTokens: Int) {
            cleanupEngine?.onConversationRebuilt(usedTokens, capTokens)
        }
    }

    companion object {
        private const val TAG = "DictationSession"

        /**
         * Grace after the keyboard closes before the engines are released. A tuned constant, not a
         * setting: long enough to survive moving between two fields, short enough to matter.
         */
        private const val KEYBOARD_CLOSE_GRACE_MS = 8_000L

        /**
         * Grace before the cleanup conversation prefills its system preface. The prefill is GPU work
         * (default processor) and the pill's card-open animation (~180–260 ms) wants the GPU first;
         * deferring the prefill keeps the motion smooth. It costs no perceived latency because the
         * first ASR unit cannot arrive before the ~1.5 s engine warm.
         */
        private const val CLEANUP_WARMUP_GRACE_MS = 350L
    }
}
