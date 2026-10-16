package io.github.alexeikramm.sovereign.server

import android.util.Log
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.DictationHistoryStore
import io.github.alexeikramm.sovereign.data.FinalizedUtterance
import io.github.alexeikramm.sovereign.data.FinalizedUtteranceRecorder
import io.github.alexeikramm.sovereign.data.UsageStatsStore
import io.github.alexeikramm.sovereign.dictation.DictationListener
import io.github.alexeikramm.sovereign.dictation.DictationSession
import io.github.alexeikramm.sovereign.dictation.DictationSessionHolder
import io.github.alexeikramm.sovereign.dictation.SessionArbiter
import io.github.alexeikramm.sovereign.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The production [CaptureHost]: the desktop protocol's view of the **one** shared [DictationSession]
 * (D-106/D-112). It never constructs an engine, recorder or dispatcher of its own — it claims the
 * process-wide [SessionArbiter] as `SERVER`, drives the session's capture lifecycle, and translates
 * the session's main-thread text callbacks into the engine's [CaptureListener] contract.
 *
 * One instance is shared by every connection (`SovereignServerService`), because only one capture can
 * be in flight and the arbiter is the gate; a second connection's `begin` is refused with the current
 * holder's owner.
 *
 * Threading: every method and callback runs on the main dispatcher (the service's scope and the
 * transport both marshal there), matching how the pill drives the session. The session's own heavy
 * work is already offloaded to its ASR/cleanup dispatchers.
 */
class SessionCaptureHost(
    private val settings: AppSettings,
    private val session: DictationSession,
    private val scope: CoroutineScope,
    /** Where a finished desktop utterance is written, so it lands in the same history as the pill. */
    private val historyStore: DictationHistoryStore,
    /** And the same usage stats (CHUNK-5); before this a desktop dictation never reached the Stats screen. */
    private val usageStatsStore: UsageStatsStore,
    /** The dashboard's observable view of the running server (Chunk 2a). */
    private val runtime: ServerRuntime = ServerRuntime.shared,
) : CaptureHost {

    private var listener: CaptureListener? = null
    private var lease: SessionArbiter.Lease? = null

    private var lastCommitted = ""
    private var lastProvisional = ""
    private var lastAsrMs = 0L
    private var lastCleanupMs = 0L

    /** The exclusive capture phase; [cleaning] overlaps it (D-115). */
    private var phase: ServerPipeline = ServerPipeline.IDLE

    /** An LLM pass is in flight; may be true at the same time as `TRANSCRIBING`. */
    private var cleaning = false

    /** Last values published, so the stage log records transitions rather than every emission. */
    private var publishedPhase: ServerPipeline? = null
    private var publishedCleaning: Boolean? = null

    /**
     * True from the moment the utterance stops until the next one begins. During the final drain the
     * pipeline emits a last `onText` with a blank provisional; without this guard that would overwrite
     * the terminal `CLEANING` stage with `LISTENING`.
     */
    private var finalizing = false

    init {
        // The router delivers these only while `SERVER` holds the arbiter, so a pill capture can
        // never stream into a desktop session (D-112). onText carries committed + raw + provisional
        // in one main-thread update; provisional is derived from it rather than from onPartialText,
        // which fires on the ASR worker thread.
        DictationSessionHolder.setListener(
            SessionArbiter.Owner.SERVER,
            DictationListener(
                onText = { committed, _, provisional -> onSessionText(committed, provisional) },
                // The only signal for the LLM phase: the pipeline keeps the provisional tail visible
                // while cleanup runs, so "cleaning" is not otherwise distinguishable from
                // "transcribing" (Chunk 2a device bug).
                onUnitCut = { onUnitCut() },
            ),
        )
    }

    /**
     * Aborts any capture still in flight (so the microphone is not left running), releases the
     * arbiter and unregisters the server's callbacks. The shared session itself keeps running.
     */
    fun dispose() {
        if (lease != null) cancel()
        DictationSessionHolder.clearListener(SessionArbiter.Owner.SERVER)
    }

    override fun begin(listener: CaptureListener): StartOutcome {
        if (lease != null) {
            return StartOutcome.Busy(session.arbiter.holder ?: SessionArbiter.Owner.SERVER)
        }
        when (val result = session.arbiter.acquire(SessionArbiter.Owner.SERVER)) {
            is SessionArbiter.Result.Refused -> return StartOutcome.Busy(result.holder)
            is SessionArbiter.Result.Granted -> lease = result.lease
        }

        this.listener = listener
        lastCommitted = ""
        lastProvisional = ""
        finalizing = false
        runtime.onCaptureStarted()
        phase = ServerPipeline.WARMING
        cleaning = false
        publishStage()
        Log.i(
            TAG,
            "Server capture begin: model=${settings.asrModelId} cleanup=${settings.cleanupEnabled}",
        )

        return try {
            session.begin(fresh = true, cleanupEnabled = settings.cleanupEnabled)
            session.beginSession()
            session.startRecording()
            // Baseline the per-utterance compute totals *after* the session resets them:
            // beginSession clears totalActiveAsrMs and begin clears the cleanup total. Baselining
            // before the reset subtracted the previous utterance's totals, so the first COMMIT_TEXT
            // of every utterance after the first carried a negative delta (seen on the dashboard).
            lastAsrMs = session.totalActiveAsrMs
            lastCleanupMs = session.totalActiveLlmMs
            // Report readiness off the call stack: the engine must emit START_ACK before any
            // STATE_CHANGED (CaptureHost.begin contract). Dispatchers.Main always posts, so this
            // cannot run re-entrantly even though `begin` is called on main.
            scope.launch(Dispatchers.Main) {
                listener.onState(PipelineState.WARMING)
                when (val availability = session.open(settings.language)) {
                    is LocalAsrEngine.Availability.Loaded -> {
                        phase = ServerPipeline.LISTENING
                        publishStage()
                        listener.onState(PipelineState.LISTENING)
                    }
                    else -> {
                        val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason
                            ?: "recogniser unavailable: $availability"
                        Log.w(TAG, "Server capture could not open the recogniser: $reason")
                        phase = ServerPipeline.ERROR
                        publishStage()
                        listener.onState(PipelineState.ERROR)
                        session.abort()
                        session.onCaptureFinished()
                        release()
                    }
                }
            }
            StartOutcome.Started
        } catch (e: Exception) {
            Log.e(TAG, "Server capture could not start recording", e)
            release()
            StartOutcome.Failed(e.message ?: "microphone unavailable")
        }
    }

    override suspend fun finish(): CaptureResult {
        // `finish` stops the recorder, flushes the ASR tail and drains cleanup; it brackets the whole
        // window as "processing" so no residency trigger can release the engines mid-finalise. There
        // is no ASR left, so the row lights only `Cleaning` for the drain.
        finalizing = true
        phase = ServerPipeline.IDLE
        cleaning = settings.cleanupEnabled
        publishStage()
        try {
            session.finish()
            session.onCaptureFinished()
            val result = CaptureResult(session.deliveryText, session.rawText)
            recordUtterance(result)
            return result
        } finally {
            // Always release the arbiter lease: a throw from the session or the recorder must not
            // leave the microphone held and every later dictation answered SESSION_BUSY.
            release()
            cleaning = false
            publishStage()
        }
    }

    override fun cancel() {
        finalizing = true
        phase = ServerPipeline.IDLE
        cleaning = false
        publishStage()
        session.abort()
        session.onCaptureFinished()
        release()
    }

    /**
     * Writes the finished utterance to history **and** usage stats through the shared recorder, so a
     * desktop dictation is stored exactly like a pill or transcribe one (CHUNK-5; before this it
     * reached history but was silently absent from the Stats screen). Called only from [finish],
     * never [cancel] — a cancelled capture is not a dictation. The phone cannot observe whether the
     * desktop inserted the text, so `insertedSuccessfully` is reported as true.
     */
    private fun recordUtterance(result: CaptureResult) {
        val llmRan = session.totalActiveLlmMs > 0L || session.isCleanupEngaged
        val asrMs = session.totalActiveAsrMs
        val llmMs = session.totalActiveLlmMs
        FinalizedUtteranceRecorder.record(
            historyStore = historyStore,
            usageStatsStore = usageStatsStore,
            historyEnabled = settings.historyEnabled,
            capacity = settings.historyCapacity,
            utterance = FinalizedUtterance(
                rawText = result.rawText,
                cleanedText = result.deliveryText,
                durationSeconds = session.lastCapturedSeconds,
                engine = Engine.LOCAL.label,
                asrModel = settings.asrModel.label,
                llmModel = if (llmRan) settings.litertModel.label else null,
                cleanedByLlm = llmRan,
                insertedSuccessfully = true,
                asrMs = asrMs,
                llmMs = llmMs,
                wallClockMs = asrMs + llmMs,
            ),
        )
    }

    /** Releases the arbiter lease and stops forwarding text. Idempotent. */
    private fun release() {
        listener = null
        lease?.let { session.arbiter.release(it) }
        lease = null
    }

    /**
     * This unit's share of a cumulative compute total. A counter reset can only make the raw delta
     * negative, which is meaningless on the dashboard, so the result floors at zero.
     */
    private fun deltaMs(current: Long, baseline: Long): Long = (current - baseline).coerceAtLeast(0L)

    /**
     * The session's text changed: `committed` is a full snapshot, so a change is one `COMMIT_TEXT`
     * (never an append — seam healing can rewrite it), and a changed `provisional` is the live tail.
     */
    private fun onSessionText(committed: String, provisional: String) {
        val target = listener ?: return
        if (committed != lastCommitted) {
            val asrMs = session.totalActiveAsrMs
            val cleanupMs = session.totalActiveLlmMs
            val asrDelta = deltaMs(asrMs, lastAsrMs)
            val cleanupDelta = deltaMs(cleanupMs, lastCleanupMs)
            runtime.onTelemetry(asrDelta, cleanupDelta, committed)
            target.onCommit(committed, CommitPerf(asrDelta, cleanupDelta))
            lastAsrMs = asrMs
            lastCleanupMs = cleanupMs
            lastCommitted = committed
            // The commit ends this unit's LLM pass. During the final drain it must stay lit until
            // `finish` clears it, so the terminal Cleaning is not cut short here.
            if (!finalizing && cleaning) {
                cleaning = session.hasPendingClean
                publishStage()
            }
        }
        if (provisional != lastProvisional) {
            lastProvisional = provisional
            // While finalising, the drain's last blank provisional must not clobber the Cleaning row.
            if (!finalizing) {
                phase = if (provisional.isBlank()) ServerPipeline.LISTENING else ServerPipeline.TRANSCRIBING
                publishStage()
            }
            target.onProvisional(provisional)
        }
    }

    /**
     * A unit was cut on the ASR worker, so (with cleanup engaged) an LLM pass is about to run. The
     * pipeline keeps the provisional tail visible during that pass, so this is the only signal that
     * cleaning is happening — and it runs *alongside* transcribing, hence a flag rather than a phase.
     */
    private fun onUnitCut() {
        if (settings.cleanupEnabled && settings.cleanupMode == io.github.alexeikramm.sovereign.data.CleanupMode.PER_SEGMENT && !finalizing) {
            cleaning = true
            publishStage()
        }
    }

    /**
     * Publishes the current stage to the dashboard and logs each transition — the dashboard's own
     * measurement, in place of the unusable device video. Synchronised because [onUnitCut] fires on
     * the ASR worker while the rest fire on main.
     */
    @Synchronized
    private fun publishStage() {
        if (phase == publishedPhase && cleaning == publishedCleaning) return
        publishedPhase = phase
        publishedCleaning = cleaning
        Log.i(TAG, "Dashboard stage: phase=$phase cleaning=$cleaning")
        runtime.setPhase(phase)
        runtime.setCleaning(cleaning)
    }

    private companion object {
        const val TAG = "SovereignServer"
    }
}
