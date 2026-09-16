package fi.ak.smartvoice.floating

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import fi.ak.smartvoice.data.AppSettings

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

        // Ensure overlay service is running if enabled
        if (settings.floatingPillEnabled) {
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
     * Injects [text] into the currently focused editable field, leaving it on the clipboard too so
     * nothing is lost if the field refuses the paste.
     */
    fun injectText(text: String): Boolean {
        if (text.isBlank()) return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Smart Voice", text))

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
