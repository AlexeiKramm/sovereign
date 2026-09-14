package fi.ak.smartvoice.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** What the panel asks the service to do. */
interface VoicePanelListener {
    fun onMic()
    fun onCancel()
    fun onToggleCleanup()
    fun onCycleLanguage()
    fun onSettings()
    fun onBackspace()
    fun onEnter()
    fun onSwitchKeyboard()
}

/**
 * The dictation panel — modern, sleek dark theme with:
 * - Animated RecordButtonView featuring a pulsing glow ring when listening
 * - Subtle, sleek utility keys: Switch Keyboard, Backspace, and Enter
 * - Profile and language switcher chips
 * - Elevated preview bubble for live streaming transcription
 */
class VoicePanelView(
    private val context: Context,
    private val listener: VoicePanelListener,
) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var collapsed = true
    private var lastState = PanelState(headline = "")

    // Top grab handle
    private val handleBar = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(Color.parseColor("#334155"))
        }
    }

    private val headlineView = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.parseColor("#94A3B8"))
        setPadding(dp(12), dp(4), dp(12), dp(2))
        isClickable = true
        contentDescription = "Dictation status; tap for details"
        setOnClickListener { toggleDetails() }
    }

    /** The recogniser's words as they arrive — styled as an elevated floating bubble. */
    private val previewView = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.parseColor("#38BDF8")) // Soft cyan/sky blue
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#1E293B"))
            setStroke(dp(1), Color.parseColor("#334155"))
        }
        visibility = View.GONE
    }

    private val detailsView = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.START
        maxLines = 8
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.parseColor("#64748B"))
        setPadding(dp(16), 0, dp(16), dp(6))
        isClickable = true
        setOnClickListener { toggleDetails() }
        visibility = View.GONE
    }

    // Center record button with animated pulsing glow
    private val recordButton = RecordButtonView(context).apply {
        setOnClickListener { listener.onMic() }
        contentDescription = "Start or stop dictating"
    }

    // Utility keys: subtle dark background with slate icons
    private val switchKey = actionKey("⌨", "Switch keyboard") { listener.onSwitchKeyboard() }
    private val backspaceKey = actionKey("⌫", "Backspace") { listener.onBackspace() }
    private val enterKey = actionKey("↵", "New line") { listener.onEnter() }

    private val cleanupChip = chip("Cleanup: Standard") { listener.onToggleCleanup() }
    private val languageChip = chip("Lang: AUTO") { listener.onCycleLanguage() }
    private val cancelChip = chip("✕ Cancel", isDestructive = true) { listener.onCancel() }
    private val settingsChip = chip("⚙") { listener.onSettings() }

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Sleek dark sheet with rounded top corners
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                dp(24).toFloat(), dp(24).toFloat(), // top-left
                dp(24).toFloat(), dp(24).toFloat(), // top-right
                0f, 0f, 0f, 0f,
            )
            setColor(Color.parseColor("#0F172A")) // Deep dark slate
            setStroke(dp(1), Color.parseColor("#1E293B"))
        }
        setPadding(dp(12), dp(8), dp(12), dp(10))

        // 1. Handle bar at the top
        val handleParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(2), 0, dp(6))
        }
        addView(handleBar, handleParams)

        // 2. Status & preview
        addView(headlineView, matchWidth())
        val previewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(dp(16), dp(4), dp(16), dp(4))
        }
        addView(previewView, previewParams)
        addView(detailsView, matchWidth())

        // 3. Main interactive row: Switch Key | Record Button | Backspace & Enter
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))

            addView(switchKey, keyParams())

            val leftSpacer = View(context)
            addView(leftSpacer, LinearLayout.LayoutParams(dp(28), 1))

            addView(recordButton, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                gravity = Gravity.CENTER
            })

            val rightSpacer = View(context)
            addView(rightSpacer, LinearLayout.LayoutParams(dp(20), 1))

            val rightKeys = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(backspaceKey, keyParams())
                val keyGap = View(context)
                addView(keyGap, LinearLayout.LayoutParams(dp(8), 1))
                addView(enterKey, keyParams())
            }
            addView(rightKeys)
        }
        addView(controlsRow, matchWidth())

        // 4. Bottom chips bar
        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
            addView(cancelChip, chipParams())
            addView(cleanupChip, chipParams())
            addView(languageChip, chipParams())
            addView(settingsChip, chipParams())
        }
        addView(chipsRow, matchWidth())
    }

    init {
        setListening(false)
    }

    // ------------------------------------------------------------------ state

    fun render(state: PanelState) {
        lastState = state
        paint()
    }

    private fun paint() {
        val state = lastState
        val hasDetails = state.details.isNotBlank()
        headlineView.text = PanelText.headlineWithMarker(state, collapsed)
        previewView.text = PanelText.previewLine(state.preview)
        previewView.visibility = if (state.hasPreview) View.VISIBLE else View.GONE
        detailsView.text = state.details
        detailsView.visibility = if (hasDetails && !collapsed) View.VISIBLE else View.GONE
    }

    fun toggleDetails() {
        collapsed = !collapsed
        paint()
    }

    fun setCleanup(enabled: Boolean) {
        cleanupChip.text = if (enabled) "Cleanup: Standard" else "Cleanup: Off"
    }

    fun setCleanupLabel(label: String) {
        cleanupChip.text = label
    }

    fun setLanguage(language: String) {
        languageChip.text = "Lang: ${if (language == "auto") "AUTO" else language.uppercase()}"
    }

    fun setListening(listening: Boolean) {
        recordButton.setListening(listening)
        cancelChip.visibility = if (listening) View.VISIBLE else View.GONE
        root.keepScreenOn = listening
    }

    // ------------------------------------------------------------------ construction

    private fun actionKey(symbol: String, desc: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = symbol
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            contentDescription = desc
            isClickable = true
            isFocusable = true
            background = keyBackground()
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    private fun keyBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(Color.parseColor("#1E293B"))
        setStroke(dp(1), Color.parseColor("#334155"))
    }

    private fun keyParams() = LinearLayout.LayoutParams(dp(50), dp(46)).apply {
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun chip(label: String, isDestructive: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val textColor = if (isDestructive) Color.parseColor("#F87171") else Color.parseColor("#CBD5E1")
            setTextColor(textColor)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E293B"))
                val strokeColor = if (isDestructive) Color.parseColor("#7F1D1D") else Color.parseColor("#334155")
                setStroke(dp(1), strokeColor)
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    private fun chipParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        setMargins(dp(3), 0, dp(3), 0)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
