package fi.ak.smartvoice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import fi.ak.smartvoice.MainActivity
import fi.ak.smartvoice.R
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.net.StackHttpException
import fi.ak.smartvoice.net.StackResponseParseException
import fi.ak.smartvoice.net.StackUnreachableException
import fi.ak.smartvoice.net.TranscribeClient
import fi.ak.smartvoice.net.TranscribeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1's deliverable: dictation that lands at the cursor in someone else's app.
 *
 * The panel (see [VoicePanelView]) works like a system keyboard's voice mode rather than being a
 * keyboard itself: switch to it, dictate, switch back to type. Push-to-talk, one utterance at a
 * time, one request with the currently selected `cleanup` and `language`, and the returned text
 * committed once — nothing is inserted raw and then rewritten, which is the v1 rule from D-021.
 *
 * Why the permission works here at all: an `InputMethodService` cannot request `RECORD_AUDIO` (no
 * Activity, no dialog) but the grant belongs to the package, so once [MainActivity] has it, this
 * service — same package, same process — can record.
 *
 * Every state writes to the status line, including the values actually requested: listening with
 * elapsed seconds, transcribing, committed with the stt / cleanup / total split, or a failure
 * carrying the contract's own error code. A key that does nothing silently is the worst outcome.
 */
class VoiceImeService : InputMethodService(), VoicePanelListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings
    private lateinit var recorder: WavRecorder

    private var panel: VoicePanelView? = null
    private var ticker: Job? = null
    private var inFlight: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        recorder = WavRecorder()
    }

    override fun onDestroy() {
        scope.cancel()
        recorder.cancel()
        super.onDestroy()
    }

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
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        abandonCapture()
        super.onFinishInputView(finishingInput)
    }

    // ------------------------------------------------------------------ panel controls

    override fun onToggleCleanup() {
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
            if (next == AppSettings.LANGUAGE_AUTO) {
                "language: auto — the server detects it per utterance"
            } else {
                "language: ${next.uppercase()} — pinned for the next utterance"
            },
        )
    }

    override fun onSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun syncPanelSettings() {
        panel?.setCleanup(settings.cleanupEnabled)
        panel?.setLanguage(settings.language)
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

    private fun startCapture() {
        if (!hasMicrophonePermission()) {
            panel?.setStatus(
                "microphone permission not granted — tap ⚙ and allow the mic in Smart Voice",
            )
            return
        }

        runCatching { recorder.start() }
            .onSuccess {
                panel?.setListening(true)
                panel?.setStatus(listeningStatus(0.0))
                ticker = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        panel?.setStatus(listeningStatus(recorder.capturedSeconds))
                    }
                }
            }
            .onFailure {
                panel?.setStatus("could not start recording: ${it.message ?: "AudioRecord failed"}")
            }
    }

    private fun listeningStatus(seconds: Double): String = "listening… %.1f s · %s · %s".format(
        seconds,
        "cleanup ${if (settings.cleanupEnabled) "full" else "off"}",
        "lang ${settings.language}",
    )

    private fun stopAndTranscribe() {
        ticker?.cancel()
        ticker = null
        panel?.setListening(false)

        val cleanup = settings.cleanupRequestValue
        val language = settings.language

        inFlight = scope.launch {
            // Stopping joins the capture thread, so it does not belong on the main thread.
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                panel?.setStatus("nothing was captured — no audio sent")
                return@launch
            }

            val seconds = (wav.size - 44).toDouble() / (WavRecorder.SAMPLE_RATE * 2)
            panel?.setStatus("transcribing %.1f s · cleanup $cleanup · lang $language…".format(seconds))

            try {
                val (result, roundTripMs) = TranscribeClient(settings.serverUrl)
                    .transcribe(wav, cleanup = cleanup, language = language)
                commit(result, roundTripMs)
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

    private fun commit(result: TranscribeResponse, roundTripMs: Long) {
        val connection = currentInputConnection
        if (connection == null) {
            panel?.setStatus("no text field is focused — nothing committed")
            return
        }
        if (result.text.isBlank()) {
            panel?.setStatus("server returned empty text — nothing committed")
            return
        }

        connection.commitText(result.text, 1)

        panel?.setStatus(
            buildString {
                append("committed · stt ${result.latencyMs?.sttMs() ?: "?"} ms")
                append(result.latencyMs?.cleanupMs()?.let { " · cleanup $it ms" } ?: " · cleanup not run")
                append(result.latencyMs?.let { " · total ${it.totalMs()} ms" } ?: "")
                append(" · round trip $roundTripMs ms")
                append(" · ${result.detectedLanguage ?: "?"}")
                if (result.corrections.isNotEmpty()) append(" · ${result.corrections.size} correction(s)")
                // Degraded success is a normal state per the contract, and the user must see it.
                if (result.cleanupUnavailable) append(" · cleanup was unavailable")
            },
        )
    }

    private fun abandonCapture() {
        ticker?.cancel()
        ticker = null
        if (recorder.isRecording) recorder.cancel()
        panel?.setListening(false)
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun idleStatus(): String = getString(R.string.ime_status_ready)

    companion object {
        private const val TICK_MS = 200L

        const val SERVICE_ID = "fi.ak.smartvoice/.ime.VoiceImeService"
    }
}
