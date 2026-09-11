package fi.ak.smartvoice.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import fi.ak.smartvoice.MainActivity

/**
 * Chunk 1: the IME plumbing, proved with no audio and no network.
 *
 * The keyboard is enabled in system settings and then lives in this app's own process, which is the
 * whole trick for permissions: an `InputMethodService` cannot request `RECORD_AUDIO` — it has no
 * Activity to show a dialog from — but the grant belongs to the package, so once `MainActivity`
 * has obtained it the mic key here inherits it. That is why chunk 0's screen exists before this.
 *
 * Chunk 1 commits a **fixed** string at the cursor. Real transcription arrives in chunk 2, where
 * the mic key drives the recorder and the contract client that chunk 0 already verified.
 */
class VoiceImeService : InputMethodService() {

    private var keyboard: KeyboardView? = null

    override fun onCreateInputView(): View {
        val view = KeyboardView(this) { action -> onKey(action) }
        keyboard = view
        view.setStatus(getString(fi.ak.smartvoice.R.string.ime_status_ready))
        return view.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.setStatus(getString(fi.ak.smartvoice.R.string.ime_status_ready))
    }

    private fun onKey(action: KeyboardAction) {
        when (action) {
            KeyboardAction.Mic -> currentInputConnection?.commitText(CHUNK_1_FIXED_TEXT, 1)
            KeyboardAction.Space -> currentInputConnection?.commitText(" ", 1)
            KeyboardAction.Backspace -> currentInputConnection?.deleteSurroundingText(1, 0)
            KeyboardAction.Enter -> sendEnter()
            KeyboardAction.Settings -> openSettings()
        }
    }

    private fun sendEnter() {
        val connection = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        val actionId = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        if (actionId != 0 && actionId != EditorInfo.IME_ACTION_NONE) {
            connection.performEditorAction(actionId)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        /**
         * Chunk 1's acceptance test is that this appears at the cursor in a third-party app, so it
         * is deliberately unmistakable rather than realistic.
         */
        const val CHUNK_1_FIXED_TEXT = "[smart-voice-ime OK] "

        const val SERVICE_CLASS = "fi.ak.smartvoice.ime.VoiceImeService"
        const val SERVICE_ID = "fi.ak.smartvoice/.ime.VoiceImeService"
    }
}
