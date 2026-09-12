package fi.ak.smartvoice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import fi.ak.smartvoice.MainActivity
import fi.ak.smartvoice.R
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.asr.LocalAsrSession
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.engine.DictationOutcome
import fi.ak.smartvoice.engine.Engine
import fi.ak.smartvoice.engine.EngineChoice
import fi.ak.smartvoice.engine.TierPolicy
import fi.ak.smartvoice.net.StackHttpException
import fi.ak.smartvoice.net.StackResponseParseException
import fi.ak.smartvoice.net.StackUnreachableException
import fi.ak.smartvoice.net.TranscribeClient
import fi.ak.smartvoice.net.TranscribeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Dictation that lands at the cursor in someone else's app — now from either tier.
 *
 * The panel (see [VoicePanelView]) works like a system keyboard's voice mode rather than being a
 * keyboard itself: switch to it, dictate, switch back to type. Push-to-talk, one utterance at a time.
 *
 * **Text is committed per pause-delimited unit, and never revised** (D-021 + D-032). The local tier's
 * ASR closes a segment at each pause, and that segment's text is committed at the cursor as soon as it
 * is final, so a long dictation no longer waits for the user to stop. Nothing is inserted raw and then
 * rewritten, and the recogniser's stream is reset at each boundary precisely so that text already on
 * screen cannot change under the user.
 *
 * **Tier policy (D-027): local by default, server as an explicit choice in settings.** The choice
 * is made per utterance by [TierPolicy], so switching the preference takes effect on the next tap
 * without restarting the keyboard, and the reason for the choice is put in the status line rather
 * than left for the user to guess.
 *
 * The two tiers genuinely differ, and the UI says so rather than pretending otherwise:
 * - **Local** feeds audio into a streaming session as it is captured (one worker thread, frames in
 *   order) and gets its text back at stop. There is no cleanup stage on-device yet — that is
 *   D-024 — so the cleanup chip reports "n/a (local)" instead of pretending a stage ran.
 * - **Server** buffers the utterance and POSTs it, as v1 did, and reports the server's own stage
 *   split.
 *
 * Why the mic permission works here at all: an `InputMethodService` cannot request `RECORD_AUDIO`
 * (no Activity, no dialog) but the grant belongs to the package, so once [MainActivity] has it,
 * this service — same package, same process — can record.
 */
class VoiceImeService : InputMethodService(), VoicePanelListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The local engine's thread. A streaming session is serial by definition (PLAN §3 threading
     * rules), so one worker is not a simplification — it is the contract.
     */
    private val asrDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smartvoice-asr")
    }.asCoroutineDispatcher()

    private lateinit var settings: AppSettings
    private lateinit var recorder: WavRecorder
    private lateinit var localEngine: LocalAsrEngine
    private lateinit var localSession: LocalAsrSession

    private var panel: VoicePanelView? = null
    private var ticker: Job? = null
    private var inFlight: Job? = null

    @Volatile
    private var partialWords = 0

    /** Units committed at the cursor this utterance, and the last reason a commit could not happen. */
    @Volatile
    private var unitsCommitted = 0

    @Volatile
    private var commitNote: String? = null

    /** Cold-load time of the current utterance, when it paid for one (D-010 rule 2). */
    @Volatile
    private var coldLoadMs = 0L

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        recorder = WavRecorder()
        // Model directory comes from the app's private files dir; nothing here loads the model.
        // The recogniser is created on the first local utterance (D-010) — a keyboard appearing
        // must not cost 681 MB of reads.
        localEngine = LocalAsrEngine(AsrModels.directory(filesDir))
        localSession = LocalAsrSession(
            engine = localEngine,
            dispatcher = asrDispatcher,
            scope = scope,
            // Called on the ASR thread; the status ticker reads it on the main thread.
            onPartialWords = { partialWords = it },
            // Also called on the ASR thread, in order, once per pause-delimited unit (D-032).
            onUnit = ::onLocalUnit,
        )
    }

    override fun onDestroy() {
        abandonCapture()
        scope.cancel()
        localEngine.unload()
        asrDispatcher.close()
        super.onDestroy()
    }

    /**
     * There is deliberately no `onTrimMemory` unload here.
     *
     * D-010 rule 4 planned to release models on the `TRIM_MEMORY_RUNNING_LOW` /
     * `RUNNING_CRITICAL` / `BACKGROUND` ladder. That ladder is gone: those levels were deprecated
     * in API 35 and the platform has not notified apps of them since API 34, so the only level
     * still delivered is `TRIM_MEMORY_UI_HIDDEN` — which for an IME means "the keyboard was just
     * closed", i.e. exactly when the user is most likely to re-open it a second later. Unloading
     * there would trade a few seconds of reload latency for memory the system is going to reclaim
     * by killing the process anyway.
     *
     * So the policy is the honest one: the recogniser stays resident while the process lives, and
     * the explicit release paths are the settings screen's "delete model" and process death.
     * Recorded in DECISIONS D-028.
     */
    override fun onCreateInputView(): View {
        val view = VoicePanelView(this, this)
        panel = view
        syncPanelSettings()
        view.setStatus(idleStatus())
        return view.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A new field means a clean slate; an utterance started in the previous field is abandoned.
        abandonCapture()
        syncPanelSettings()
        panel?.setStatus(idleStatus())
        prefetchLocalModel()
    }

    /**
     * D-010 rule 2, as measured: the cold load is **1912 ms** on the target phone (2026-09-12) —
     * over the ~1.5 s the rule was written against — so the load is paid while the keyboard is
     * visible instead of after the first mic tap. Nothing is loaded at process start, and nothing
     * is loaded when the user's setting is the server.
     */
    private fun prefetchLocalModel() {
        if (recorder.isRecording) return
        if (!TierPolicy.shouldPrefetch(settings.enginePreference, localEngine.availability())) return

        scope.launch {
            withContext(asrDispatcher) { localEngine.load() }
            // Only report if the user has not started dictating in the meantime: the ticker owns the
            // status line from that point.
            if (!recorder.isRecording && inFlight?.isActive != true) panel?.setStatus(idleStatus())
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        abandonCapture()
        super.onFinishInputView(finishingInput)
    }

    // ------------------------------------------------------------------ panel controls

    override fun onToggleCleanup() {
        if (currentChoice().engine == Engine.LOCAL) {
            // Honest state, not a disabled control: there is no cleanup stage on the device yet.
            panel?.setStatus(
                "cleanup runs on the server tier only — the local tier commits the model's own text " +
                    "(D-024). Pick the server in the app's settings if you want it cleaned.",
            )
            return
        }
        settings.cleanupEnabled = !settings.cleanupEnabled
        syncPanelSettings()
        panel?.setStatus(
            if (settings.cleanupEnabled) {
                "cleanup on — the server will clean and punctuate the transcript"
            } else {
                "cleanup off — you will get the raw transcript (faster, no LLM)"
            },
        )
    }

    override fun onCycleLanguage() {
        val cycle = AppSettings.LANGUAGE_CYCLE
        val next = cycle[(cycle.indexOf(settings.language).takeIf { it >= 0 } ?: 0).let { (it + 1) % cycle.size }]
        settings.language = next
        syncPanelSettings()
        panel?.setStatus(
            when {
                next == AppSettings.LANGUAGE_AUTO && currentChoice().engine == Engine.LOCAL ->
                    "language: auto — the local model detects it per utterance"
                next == AppSettings.LANGUAGE_AUTO ->
                    "language: auto — the server detects it per utterance"
                currentChoice().engine == Engine.LOCAL ->
                    // The local model uses this as a hard prompt: pinned to a language it is not
                    // hearing, it returns nothing rather than guessing (measured 2026-09-12).
                    "language: ${next.uppercase()} — the local model will only transcribe " +
                        "${next.uppercase()}; use auto for mixed or unsure speech"
                else -> "language: ${next.uppercase()} — pinned for the next utterance"
            },
        )
    }

    override fun onSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun syncPanelSettings() {
        val choice = currentChoice()
        panel?.setCleanupLabel(cleanupLabel(choice))
        panel?.setLanguage(settings.language)
    }

    private fun cleanupLabel(choice: EngineChoice): String = when (choice.engine) {
        Engine.LOCAL -> "Cleanup: n/a (local)"
        Engine.REMOTE -> if (settings.cleanupEnabled) "Cleanup: full" else "Cleanup: off"
    }

    // ------------------------------------------------------------------ dictation

    override fun onMic() {
        when {
            recorder.isRecording -> stopAndTranscribe()
            inFlight?.isActive == true -> panel?.setStatus("still working on the last utterance…")
            else -> startCapture()
        }
    }

    override fun onCancel() {
        abandonCapture()
        panel?.setStatus("cancelled — nothing was sent")
    }

    private fun currentChoice(): EngineChoice = TierPolicy.choose(settings.enginePreference, localEngine.availability())

    private fun startCapture() {
        if (!hasMicrophonePermission()) {
            panel?.setStatus(
                "microphone permission not granted — tap ⚙ and allow the mic in Smart Voice",
            )
            return
        }

        val choice = currentChoice()
        partialWords = 0
        unitsCommitted = 0
        commitNote = null

        if (choice.engine == Engine.LOCAL) startLocalCapture(choice) else startRemoteCapture(choice)
    }

    // ------------------------------------------------------------------ local tier

    /**
     * The streaming path. The frame queue exists before the model does, so audio captured while
     * the encoder is still being loaded (seconds on the first utterance) is kept, not dropped.
     */
    private fun startLocalCapture(choice: EngineChoice) {
        val language = settings.language
        // Whether this utterance pays the cold load decides if its number is worth reporting:
        // D-010 rule 2 asks for a measured load time, not for one on every utterance's status line.
        val paysColdLoad = localEngine.availability() != LocalAsrEngine.Availability.Loaded
        coldLoadMs = 0L

        localSession.begin()
        startRecording(choice, sink = localSession::onFrames)

        scope.launch {
            val availability = localSession.open(language)
            if (availability !is LocalAsrEngine.Availability.Loaded) {
                val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason
                    ?: "model not installed"
                abandonCapture()
                panel?.setStatus("local engine unavailable — $reason; download the model or pick the server")
            } else if (paysColdLoad) {
                coldLoadMs = localEngine.lastLoadMillis
            }
        }
    }

    private fun stopLocalCapture(choice: EngineChoice) {
        val audioSeconds = recorder.capturedSeconds

        inFlight = scope.launch {
            // Stopping joins the capture thread, so no frames arrive after this point.
            withContext(Dispatchers.IO) { recorder.stop() }

            val stoppedAt = SystemClock.elapsedRealtime()
            // finish() flushes and emits whatever the last segment held through onUnit, so by the time
            // this returns the commits have been posted — in order, because the main looper runs posted
            // tasks in order and the worker posted them in the order it produced them.
            val whole = localSession.finish()
            val tailMs = SystemClock.elapsedRealtime() - stoppedAt

            reportLocal(choice, audioSeconds, tailMs, whole)
        }
    }

    /**
     * One completed unit arriving from the ASR worker (D-032). The commit happens on the main thread —
     * an `InputConnection` belongs to the view hierarchy — and the leading space is deliberate: units are
     * separate recognitions, so the first word of the next one arrives without one. The cleanup stage
     * replaces that spacing with real punctuation when it lands (slice 5c-2); until then one space is the
     * honest join, and the join rule itself is pinned by `UtteranceTextTest`.
     */
    private fun onLocalUnit(unit: String) {
        scope.launch(Dispatchers.Main.immediate) {
            val connection = currentInputConnection
            if (connection == null) {
                // Focus moved. Saying so beats text that silently goes nowhere.
                commitNote = "no text field is focused — nothing committed"
                return@launch
            }
            connection.commitText(if (unitsCommitted > 0) " $unit" else unit, 1)
            unitsCommitted++
        }
    }

    /**
     * The local tier's end-of-utterance report. Deliberately does **not** commit: the text went to the
     * cursor unit by unit as each pause closed a segment, which is also why nothing on screen is ever
     * revised (D-021). What is left to report is the tail — the flush plus the last unit — and the
     * numbers that make the run honest.
     */
    private fun reportLocal(choice: EngineChoice, audioSeconds: Double, tailMs: Long, whole: String) {
        if (whole.isBlank()) {
            val pinned = settings.language
            panel?.setStatus(
                if (pinned != AppSettings.LANGUAGE_AUTO) {
                    "no text — the local model was pinned to ${pinned.uppercase()} and only " +
                        "transcribes speech in that language; set the language chip to auto and retry"
                } else {
                    "local returned empty text — nothing committed"
                },
            )
            return
        }

        val note = buildString {
            append(choice.note)
            append(" · ${localSession.unitCount} pause-delimited unit(s)")
            if (coldLoadMs > 0L) append(" · cold model load $coldLoadMs ms")
            if (localSession.droppedFrames > 0) {
                append(" · ${localSession.droppedFrames} audio frame(s) dropped (engine behind)")
            }
            commitNote?.let { append(" · $it") }
        }
        panel?.setStatus("committed · local · %.1f s audio · tail %d ms · %s".format(audioSeconds, tailMs, note))
    }

    // ------------------------------------------------------------------ server tier

    private fun startRemoteCapture(choice: EngineChoice) {
        startRecording(choice, sink = null)
    }

    private fun stopRemoteCapture(choice: EngineChoice) {
        val cleanup = settings.cleanupRequestValue
        val language = settings.language

        inFlight = scope.launch {
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                panel?.setStatus("nothing was captured — no audio sent")
                return@launch
            }

            val seconds = (wav.size - 44).toDouble() / (WavRecorder.SAMPLE_RATE * 2)
            panel?.setStatus("transcribing %.1f s · cleanup $cleanup · lang $language…".format(seconds))

            try {
                val startedAt = SystemClock.elapsedRealtime()
                val (result, roundTripMs) = TranscribeClient(settings.serverUrl)
                    .transcribe(wav, cleanup = cleanup, language = language)
                commit(
                    DictationOutcome(
                        text = result.text,
                        engine = Engine.REMOTE,
                        note = choice.note,
                        audioSeconds = seconds,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        response = result,
                    ),
                    roundTripMs = roundTripMs,
                )
            } catch (e: StackHttpException) {
                panel?.setStatus("server said ${e.code} (HTTP ${e.status}) — ${e.message}")
            } catch (e: StackUnreachableException) {
                panel?.setStatus("cannot reach ${settings.serverUrl} — check the URL in ⚙ settings")
            } catch (e: StackResponseParseException) {
                panel?.setStatus("server response could not be read — ${e.message}")
            } catch (t: Throwable) {
                panel?.setStatus("failed: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ shared

    /**
     * Starts capture and the once-per-200 ms status ticker. Failure is reported, never silent:
     * an `AudioRecord` that cannot initialize is the one thing that makes the mic key look dead.
     */
    private fun startRecording(choice: EngineChoice, sink: ((ByteArray, Int) -> Unit)?) {
        recorder.frameSink = sink
        runCatching { recorder.start() }
            .onSuccess {
                panel?.setListening(true)
                panel?.setStatus(listeningStatus(choice, 0.0))
                ticker = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        panel?.setStatus(listeningStatus(choice, recorder.capturedSeconds))
                    }
                }
            }
            .onFailure {
                recorder.frameSink = null
                localSession.abort()
                val reason = it.message ?: "AudioRecord failed"
                panel?.setStatus("could not start recording: $reason")
            }
    }

    private fun stopAndTranscribe() {
        ticker?.cancel()
        ticker = null
        panel?.setListening(false)

        val choice = currentChoice()
        when (choice.engine) {
            Engine.LOCAL -> stopLocalCapture(choice)
            Engine.REMOTE -> stopRemoteCapture(choice)
        }
    }

    private fun listeningStatus(choice: EngineChoice, seconds: Double): String = buildString {
        append("listening… %.1f s".format(seconds))
        append(" · ${choice.engine.label}")
        if (choice.engine == Engine.LOCAL) {
            // The partial count is not the transcript — the transcript lands at the cursor when each
            // unit becomes final, and nothing is rewritten (D-021). Both numbers are here because they
            // say different things: the engine is keeping up, and text is actually arriving.
            if (partialWords > 0) append(" · ~$partialWords word(s) so far")
            if (unitsCommitted > 0) append(" · $unitsCommitted unit(s) at the cursor")
            commitNote?.let { append(" · $it") }
        } else {
            append(" · cleanup ${if (settings.cleanupEnabled) "full" else "off"}")
        }
        append(" · lang ${settings.language}")
    }

    /**
     * The server tier's commit path: one response, one commit. The local tier no longer comes through
     * here — it commits per unit in [onLocalUnit] — so this keeps the remote path's shape.
     */
    private fun commit(outcome: DictationOutcome, roundTripMs: Long? = null) {
        val connection = currentInputConnection
        if (connection == null) {
            panel?.setStatus("no text field is focused — nothing committed")
            return
        }
        if (outcome.text.isBlank()) {
            // Measured 2026-09-12 (STATUS): the multilingual artifact treats `language` as a hard
            // prompt — English audio with `fi` pinned comes back empty, not as a guess. Saying so
            // is the difference between "the keyboard is broken" and "change one chip".
            val pinned = settings.language
            panel?.setStatus(
                if (outcome.engine == Engine.LOCAL && pinned != AppSettings.LANGUAGE_AUTO) {
                    "no text — the local model was pinned to ${pinned.uppercase()} and only " +
                        "transcribes speech in that language; set the language chip to auto and retry"
                } else {
                    "${outcome.engine.label} returned empty text — nothing committed"
                },
            )
            return
        }

        connection.commitText(outcome.text, 1)

        panel?.setStatus(
            when (outcome.engine) {
                Engine.LOCAL -> buildString {
                    append("committed · local · %.1f s audio".format(outcome.audioSeconds))
                    append(" · tail ${outcome.elapsedMs} ms")
                    append(" · ${outcome.note}")
                }

                Engine.REMOTE -> {
                    val result = outcome.response
                    buildString {
                        append("committed · server")
                        append(" · stt ${result?.latencyMs?.sttMs() ?: "?"} ms")
                        append(result?.latencyMs?.cleanupMs()?.let { " · cleanup $it ms" } ?: " · cleanup not run")
                        append(result?.latencyMs?.let { " · total ${it.totalMs()} ms" } ?: "")
                        append(roundTripMs?.let { " · round trip $it ms" } ?: "")
                        append(" · ${result?.detectedLanguage ?: "?"}")
                        if ((result?.corrections?.size ?: 0) > 0) append(" · ${result!!.corrections.size} correction(s)")
                        // Degraded success is a normal state per the contract, and the user must see it.
                        if (result?.cleanupUnavailable == true) append(" · cleanup was unavailable")
                    }
                }
            },
        )
    }

    private fun abandonCapture() {
        ticker?.cancel()
        ticker = null
        recorder.frameSink = null
        if (recorder.isRecording) recorder.cancel()
        // Dropping the stream is all a cancel needs: the recogniser stays resident (D-010) and
        // nothing was committed, which is what "cancel" has to mean.
        localSession.abort()
        partialWords = 0
        unitsCommitted = 0
        commitNote = null
        panel?.setListening(false)
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun idleStatus(): String {
        val choice = currentChoice()
        return "${getString(R.string.ime_status_ready)} · engine: ${choice.engine.label} (${choice.note})"
    }

    companion object {
        private const val TICK_MS = 200L

        const val SERVICE_ID = "fi.ak.smartvoice/.ime.VoiceImeService"
    }
}
