package fi.ak.smartvoice.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
 * **Two status lines, not one (D-041).** [PanelState.headline] is what a person needs while dictating;
 * [PanelState.details] is the diagnostics that three device rounds earned the hard way — shown when the
 * status area is tapped, and not deleted, because the next wrong threshold will need it as much as the
 * last three did. One line carrying both is how a status line becomes a developer's instrument.
 *
 * Views rather than Compose: an IME's input view has no Activity to host a Compose lifecycle.
 * Styling stays plain on purpose — colour, size and type are the user's to design in Studio.
 */
class VoicePanelView(
    private val context: Context,
    private val listener: VoicePanelListener,
) {

    private var collapsed = true
    private var lastState = PanelState(headline = "")

    private val headlineView = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(10), dp(12), dp(2))
        isClickable = true
        contentDescription = "Dictation status; tap for the details"
        setOnClickListener { toggleDetails() }
    }

    /** The recogniser's words as they arrive — a preview only, never the cursor's text (D-021). */
    private val previewView = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(attrColor(android.R.attr.textColorSecondary))
        setPadding(dp(12), 0, dp(12), dp(4))
        visibility = View.GONE
    }

    private val detailsView = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.START
        maxLines = 8
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(attrColor(android.R.attr.textColorSecondary))
        setPadding(dp(12), 0, dp(12), dp(6))
        isClickable = true
        setOnClickListener { toggleDetails() }
        visibility = View.GONE
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

        addView(headlineView, matchWidth())
        addView(previewView, matchWidth())
        addView(detailsView, matchWidth())

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

    /**
     * Paints a whole panel state. The service composes it (see [PanelState] for the split); this class
     * owns only how it looks — including whether the details are open, which is the user's business and
     * therefore survives every re-render while they dictate.
     */
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
        cleanupChip.text = if (enabled) "Cleanup: full" else "Cleanup: off"
    }

    /**
     * The cleanup chip is a view of what the *current tier* will do, so it says only what it does: the
     * reason behind the state ("model still loading", "load failed") is one tap away in the details.
     */
    fun setCleanupLabel(label: String) {
        cleanupChip.text = label
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
