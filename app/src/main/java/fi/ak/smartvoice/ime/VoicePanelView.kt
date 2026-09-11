package fi.ak.smartvoice.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
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
}

/**
 * The dictation panel — the shape of the IME.
 *
 * Deliberately **not** a QWERTY keyboard. It works like the voice mode of a system keyboard: the
 * user switches to it, dictates, and switches back to their normal keyboard to type. Building a
 * second keyboard (layouts, autocorrect, numbers page) would have been a large amount of work in
 * the least interesting part of the product; a panel with one big record key is the whole surface
 * the feature needs.
 *
 * The two chips are the contract's own request fields, not app inventions:
 * `cleanup` (`off|full`) and `language` (`auto|<ISO-639-1>`). Tapping them changes what the next
 * utterance sends, and the requested value is echoed in the status line so the user can always see
 * what was actually asked for.
 *
 * Views rather than Compose: an IME's input view has no Activity to host a Compose lifecycle.
 * Styling stays plain on purpose — colour, size and type are the user's to design in Studio.
 */
class VoicePanelView(
    private val context: Context,
    private val listener: VoicePanelListener,
) {

    private val statusView = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 3
        setPadding(dp(12), dp(10), dp(12), dp(4))
    }

    private val recordButton = TextView(context).apply {
        text = "🎤"
        textSize = 40f
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = "Start dictating"
        background = circle(accentColor())
        setOnClickListener { listener.onMic() }
    }

    private val cleanupChip = chip("Cleanup: full") { listener.onToggleCleanup() }
    private val languageChip = chip("Lang: auto") { listener.onCycleLanguage() }
    private val cancelChip = chip("✕ cancel") { listener.onCancel() }
    private val settingsChip = chip("⚙") { listener.onSettings() }

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(attrColor(android.R.attr.colorBackground))
        setPadding(dp(8), dp(6), dp(8), dp(10))

        addView(statusView, matchWidth())

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(recordButton, circleParams())
            },
            matchWidth(),
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
                addView(cancelChip, chipParams())
                addView(cleanupChip, chipParams())
                addView(languageChip, chipParams())
                addView(settingsChip, chipParams())
            },
            matchWidth(),
        )
    }

    init {
        setListening(false)
    }

    // ------------------------------------------------------------------ state

    fun setStatus(text: String) {
        statusView.text = text
    }

    fun setCleanup(enabled: Boolean) {
        cleanupChip.text = if (enabled) "Cleanup: full" else "Cleanup: off"
    }

    fun setLanguage(language: String) {
        languageChip.text = "Lang: ${if (language == "auto") "auto" else language.uppercase()}"
    }

    /**
     * While listening the record key becomes the stop key and cancel appears; a cancel must leave
     * nothing behind, so it is always reachable in the same place.
     */
    fun setListening(listening: Boolean) {
        recordButton.text = if (listening) "■" else "🎤"
        recordButton.contentDescription = if (listening) "Send the dictation" else "Start dictating"
        cancelChip.visibility = if (listening) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ construction

    private fun circleParams() = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun chip(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pill(outlineColor())
            setOnClickListener { onClick() }
        }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), outlineColor())
    }

    private fun pill(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(attrColor(android.R.attr.colorBackground))
        setStroke(dp(1), strokeColor)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun chipParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dp(6) }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private fun attrColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /** `colorAccent` exists on every Android theme, which keeps this independent of the theme family. */
    private fun accentColor(): Int = attrColor(android.R.attr.colorAccent)

    private fun outlineColor(): Int = attrColor(android.R.attr.colorControlHighlight)
}
