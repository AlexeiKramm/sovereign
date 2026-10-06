package io.github.alexeikramm.sovereign.server

import android.util.Log
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.dictation.DictationListener
import io.github.alexeikramm.sovereign.dictation.DictationSession
import io.github.alexeikramm.sovereign.dictation.DictationSessionHolder
import io.github.alexeikramm.sovereign.dictation.SessionArbiter
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
) : CaptureHost {

    private var listener: CaptureListener? = null
    private var lease: SessionArbiter.Lease? = null

    private var lastCommitted = ""
    private var lastProvisional = ""
    private var lastAsrMs = 0L
    private var lastCleanupMs = 0L

    init {
        // The router delivers these only while `SERVER` holds the arbiter, so a pill capture can
        // never stream into a desktop session (D-112). onText carries committed + raw + provisional
        // in one main-thread update; provisional is derived from it rather than from onPartialText,
        // which fires on the ASR worker thread.
        DictationSessionHolder.setListener(
            SessionArbiter.Owner.SERVER,
            DictationListener(onText = { committed, _, provisional -> onSessionText(committed, provisional) }),
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
        // Baseline the per-utterance compute totals, so each COMMIT_TEXT carries this unit's share.
        lastAsrMs = session.totalActiveAsrMs
        lastCleanupMs = session.totalActiveLlmMs
        Log.i(
            TAG,
            "Server capture begin: model=${settings.asrModelId} cleanup=${settings.cleanupEnabled}",
        )

        return try {
            session.begin(fresh = true, cleanupEnabled = settings.cleanupEnabled)
            session.beginSession()
            session.startRecording()
            // Report readiness off the call stack: the engine must emit START_ACK before any
            // STATE_CHANGED (CaptureHost.begin contract). Dispatchers.Main always posts, so this
            // cannot run re-entrantly even though `begin` is called on main.
            scope.launch(Dispatchers.Main) {
                listener.onState(PipelineState.WARMING)
                when (val availability = session.open(settings.language)) {
                    is LocalAsrEngine.Availability.Loaded -> listener.onState(PipelineState.LISTENING)
                    else -> {
                        val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason
                            ?: "recogniser unavailable: $availability"
                        Log.w(TAG, "Server capture could not open the recogniser: $reason")
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
        // window as "processing" so no residency trigger can release the engines mid-finalise.
        session.finish()
        session.onCaptureFinished()
        val result = CaptureResult(session.deliveryText, session.rawText)
        release()
        return result
    }

    override fun cancel() {
        session.abort()
        session.onCaptureFinished()
        release()
    }

    /** Releases the arbiter lease and stops forwarding text. Idempotent. */
    private fun release() {
        listener = null
        lease?.let { session.arbiter.release(it) }
        lease = null
    }

    /**
     * The session's text changed: `committed` is a full snapshot, so a change is one `COMMIT_TEXT`
     * (never an append — seam healing can rewrite it), and a changed `provisional` is the live tail.
     */
    private fun onSessionText(committed: String, provisional: String) {
        val target = listener ?: return
        if (committed != lastCommitted) {
            val asrMs = session.totalActiveAsrMs
            val cleanupMs = session.totalActiveLlmMs
            target.onCommit(committed, CommitPerf(asrMs - lastAsrMs, cleanupMs - lastCleanupMs))
            lastAsrMs = asrMs
            lastCleanupMs = cleanupMs
            lastCommitted = committed
        }
        if (provisional != lastProvisional) {
            lastProvisional = provisional
            target.onProvisional(provisional)
        }
    }

    private companion object {
        const val TAG = "SovereignServer"
    }
}
