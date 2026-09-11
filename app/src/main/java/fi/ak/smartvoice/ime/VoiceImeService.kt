package fi.ak.smartvoice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import fi.ak.smartvoice.MainActivity
import fi.ak.smartvoice.R
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.net.Correction
import fi.ak.smartvoice.net.StackHttpException
import fi.ak.smartvoice.net.StackResponseParseException
import fi.ak.smartvoice.net.StackUnreachableException
import fi.ak.smartvoice.net.TranscribeClient
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
 * Push-to-talk, one utterance at a time: tap the mic, speak, tap again. The whole utterance goes to
 * the server in one request with `cleanup: "full"`, and the returned (already cleaned) text is
 * committed once — nothing is inserted raw and then rewritten, which is the v1 rule from D-021.
 *
 * Why the permission works here at all: an `InputMethodService` cannot request `RECORD_AUDIO` (no
 * Activity, no dialog) but the grant belongs to the package, so once [MainActivity] has it, this
 * service — same package, same process — can record.
 *
 * States are all on the status line, and none of them is silent: listening with elapsed seconds,
 * transcribing, committed, or a failure with the contract's own error code. A mic key that does
 * nothing is the worst possible outcome, so every path writes something the user can read.
 */
class VoiceImeService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings
    private lateinit var recorder: WavRecorder

    private var keyboard: KeyboardView? = null
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
        val view = KeyboardView(this) { action -> onKey(action) }
        keyboard = view
        view.setStatus(idleStatus())
        return view.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A new field means a clean slate; an utterance started in the previous field is abandoned.
        abandonCapture()
        keyboard?.setStatus(idleStatus())
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        abandonCapture()
        super.onFinishInputView(finishingInput)
    }

    private fun onKey(action: KeyboardAction) {
        when (action) {
            KeyboardAction.Mic -> onMicKey()
            KeyboardAction.Space -> currentInputConnection?.commitText(" ", 1)
            KeyboardAction.Backspace -> currentInputConnection?.deleteSurroundingText(1, 0)
            KeyboardAction.Enter -> sendEnter()
            KeyboardAction.Settings -> openSettings()
        }
    }

    private fun onMicKey() {
        when {
            recorder.isRecording -> stopAndTranscribe()
            inFlight?.isActive == true -> keyboard?.setStatus("still working on the last utterance…")
            else -> startCapture()
        }
    }

    private fun startCapture() {
        if (!hasMicrophonePermission()) {
            keyboard?.setStatus(
                "microphone permission not granted — tap ⚙ to open Smart Voice, allow the mic, " +
                    "then switch back here",
            )
            return
        }

        runCatching { recorder.start() }
            .onSuccess {
                keyboard?.setMicLabel("■ stop & send")
                keyboard?.setStatus("listening… 0.0 s — tap ■ to stop")
                ticker = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        keyboard?.setStatus("listening… %.1f s — tap ■ to stop".format(recorder.capturedSeconds))
                    }
                }
            }
            .onFailure {
                keyboard?.setStatus("could not start recording: ${it.message ?: "AudioRecord failed"}")
            }
    }

    private fun stopAndTranscribe() {
        ticker?.cancel()
        ticker = null
        keyboard?.setMicLabel("🎤 dictate")

        inFlight = scope.launch {
            // Stopping the recorder joins its capture thread, so it does not belong on the main thread.
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                keyboard?.setStatus("nothing was captured — no audio sent")
                return@launch
            }

            val seconds = (wav.size - 44).toDouble() / (WavRecorder.SAMPLE_RATE * 2)
            keyboard?.setStatus("transcribing %.1f s of audio…".format(seconds))

            try {
                val (result, roundTripMs) = TranscribeClient(settings.serverUrl).transcribe(wav)
                commit(result.text, result.latencyMs?.sttMs(), result.latencyMs?.cleanupMs(), result.latencyMs?.totalMs(), roundTripMs, result.cleanupUnavailable, result.corrections)
            } catch (e: StackHttpException) {
                keyboard?.setStatus("server said ${e.code} (HTTP ${e.status}) — ${e.message}")
            } catch (e: StackUnreachableException) {
                keyboard?.setStatus("cannot reach the server at ${settings.serverUrl} — check the URL in Smart Voice")
            } catch (e: StackResponseParseException) {
                keyboard?.setStatus("server response could not be read — ${e.message}")
            } catch (t: Throwable) {
                keyboard?.setStatus("failed: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun commit(
        text: String,
        sttMs: Long?,
        cleanupMs: Long?,
        totalMs: Long?,
        roundTripMs: Long,
        cleanupUnavailable: Boolean,
        corrections: List<Correction>,
    ) {
        val connection = currentInputConnection
        if (connection == null) {
            keyboard?.setStatus("no text field is focused — nothing committed")
            return
        }
        if (text.isBlank()) {
            keyboard?.setStatus("server returned empty text — nothing committed")
            return
        }

        connection.commitText(text, 1)
        keyboard?.setStatus(
            buildString {
                append("committed · stt ${sttMs ?: "?"} ms")
                append(cleanupMs?.let { " · cleanup $it ms" } ?: " · cleanup not run")
                append(totalMs?.let { " · total $it ms" } ?: "")
                append(" · round trip $roundTripMs ms")
                if (corrections.isNotEmpty()) append(" · ${corrections.size} correction(s)")
                // Degraded success is a normal state per the contract, and the user must see it.
                if (cleanupUnavailable) append(" · cleanup was unavailable")
            },
        )
    }

    private fun abandonCapture() {
        ticker?.cancel()
        ticker = null
        if (recorder.isRecording) recorder.cancel()
        keyboard?.setMicLabel("🎤 dictate")
    }

    private fun sendEnter() {
        val connection = currentInputConnection ?: return
        val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        if (actionId != 0 && actionId != EditorInfo.IME_ACTION_NONE) {
            connection.performEditorAction(actionId)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
