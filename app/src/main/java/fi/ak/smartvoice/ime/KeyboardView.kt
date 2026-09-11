package fi.ak.smartvoice.ime

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** What a key does. */
enum class KeyboardAction { Mic, Space, Backspace, Enter, Settings }

/**
 * The keyboard surface, built programmatically rather than from XML or Compose.
 *
 * Two reasons. An IME's input view has no Activity to host a Compose lifecycle, which makes Compose
 * here more machinery than it is worth; and this is the one part of the app whose code is cheap to
 * replace with a designed layout, so it stays plain and disposable. the user restyles it in Android
 * Studio; the service only needs `root`, `setStatus` and `setMicLabel`.
 *
 * Deliberately not a QWERTY keyboard yet: the mic key, space, backspace, enter and a way into
 * settings are the minimum for dictation to be usable and testable in a real text field.
 */
class KeyboardView(
    private val context: Context,
    private val onAction: (KeyboardAction) -> Unit,
) {

    private val statusView = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(6))
    }

    private val micButton: Button = key("🎤 dictate") { onAction(KeyboardAction.Mic) }

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))

        addView(statusView, rowParams())
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(micButton, weightParams(2f))
                addView(key("⌫") { onAction(KeyboardAction.Backspace) }, weightParams(1f))
                addView(key("⏎") { onAction(KeyboardAction.Enter) }, weightParams(1f))
            },
            rowParams(),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(key("space") { onAction(KeyboardAction.Space) }, weightParams(2f))
                addView(key("⚙") { onAction(KeyboardAction.Settings) }, weightParams(1f))
            },
            rowParams(),
        )
    }

    fun setStatus(text: String) {
        statusView.text = text
    }

    /** The mic key has to say what it will do next: start listening, or stop and send. */
    fun setMicLabel(text: String) {
        micButton.text = text
    }

    private fun key(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightParams(weight: Float) = LinearLayout.LayoutParams(0, dp(52), weight)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
