package io.github.alexeikramm.sovereign.floating

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import io.github.alexeikramm.sovereign.cleanup.FieldPreamble
import io.github.alexeikramm.sovereign.data.AppSettings

/**
 * Monitors active input fields and software keyboard (IME) visibility,
 * and performs text injection on behalf of the floating dictation pill.
 */
class VoiceAccessibilityService : AccessibilityService() {

    private lateinit var settings: AppSettings
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        instance = this
        Log.i(TAG, "VoiceAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "VoiceAccessibilityService connected")

        // Ensure overlay service is running if enabled and mic permission is held
        if (settings.floatingPillEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            FloatingOverlayService.start(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    lastFocusedNode = source
                }
                checkKeyboardVisibility()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkKeyboardVisibility()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        lastFocusedNode = null
        Log.i(TAG, "VoiceAccessibilityService destroyed")
    }

    /**
     * Checks whether a software keyboard (IME window) is currently visible on screen.
     */
    private fun checkKeyboardVisibility() {
        if (!settings.floatingPillEnabled) return

        val currentWindows = try {
            windows
        } catch (_: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }

        val isKeyboardOpen = currentWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val activeInputNode = findFocusedInputNode()
        val hasEditableFocus = activeInputNode != null

        FloatingOverlayService.updateVisibility(
            context = this,
            isKeyboardOpen = isKeyboardOpen,
            hasEditableFocus = hasEditableFocus,
        )
    }

    /**
     * Finds the editable node that currently holds input focus.
     *
     * Deliberately searches **every window**, not just the active one: the active window is not
     * always the one holding the editor (a WebView editor like Obsidian's can live in a different
     * window than the one the system considers active), and the IME window frequently has focus.
     * Falling back to a cached node keeps the last known editor working when focus briefly moves.
     */
    fun findFocusedInputNode(): AccessibilityNodeInfo? {
        val currentWindows = try {
            windows
        } catch (_: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }
        for (window in currentWindows) {
            val focused = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                lastFocusedNode = focused
                return focused
            }
        }

        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            lastFocusedNode = focused
            return focused
        }
        return lastFocusedNode?.takeIf { it.refresh() && it.isEditable }
    }

    /**
     * Reads the tail of the currently focused editable field — the last [sentences] sentences before
     * the caret — to hand the cleanup model as **pure context** for a cold-start utterance
     * (`docs/work/field-preamble-context/`), so the new text fits what is already in the field.
     *
     * Returns null when no editable field is focused, the field exposes no text, or there is nothing
     * before the caret. Editors that expose neither text nor selection (some Compose/custom fields)
     * thus behave exactly as before. When the node reports no selection (`-1`), the end of the text is
     * used, which matches the common "append at the end" case.
     *
     * `node.text` reads the whole field (O(document)); the extractor strips it down to [sentences].
     * The caller invokes this once, at the start of an utterance, only when the user has opted in.
     */
    fun readFocusedFieldTail(sentences: Int): String? {
        val node = findFocusedInputNode() ?: run {
            Log.i(TAG, "Field preamble: no editable field focused")
            return null
        }
        val text = node.text?.toString() ?: run {
            Log.i(TAG, "Field preamble: focused field exposes no text")
            return null
        }
        val cursor = node.textSelectionStart
        val at = if (cursor >= 0) cursor else text.length
        val tail = FieldPreamble.extract(text, at, sentences)
        // Length only: the tail itself is the user's field content and never belongs in logcat.
        Log.i(TAG, "Field preamble: ${tail?.length ?: 0}ch from ${text.length}ch at cursor $at")
        return tail
    }

    /**
     * Injects [text] into the currently focused editable field, leaving it on the clipboard too so
     * nothing is lost if the field refuses the paste.
     */
    fun injectText(text: String): Boolean {
        if (text.isBlank()) return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Sovereign", text))

        // Two attempts: the first lookup can land on a stale node (a WebView editor that rebuilt
        // itself, or a window that lost focus as the pill expanded), and a fresh resolve usually
        // succeeds. Keep the text on the clipboard either way.
        repeat(2) { attempt ->
            val node = findFocusedInputNode()
            if (node == null) {
                Log.w(TAG, "No editable node found to inject into (attempt ${attempt + 1})")
                return@repeat
            }

            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Log.i(TAG, "Injected text via ACTION_PASTE at cursor")
                return true
            }

            // Safe fallback ONLY for a genuinely empty field — never clobber existing content.
            val existing = node.text
            if (existing.isNullOrEmpty()) {
                val setArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
                    Log.i(TAG, "Injected text via ACTION_SET_TEXT into empty field")
                    return true
                }
            }
            Log.w(TAG, "Paste refused (attempt ${attempt + 1}); retrying with a fresh node")
        }

        Log.w(TAG, "Could not paste directly; text remains safe on the clipboard")
        return false
    }

    companion object {
        private const val TAG = "VoiceAccessibility"

        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
