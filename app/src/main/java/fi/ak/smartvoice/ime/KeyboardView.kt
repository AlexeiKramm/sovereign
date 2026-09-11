package fi.ak.smartvoice.ime

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** What a key does. Deliberately tiny for chunk 1 — the plumbing is the point, not the layout. */
enum class KeyboardAction { Mic, Space, Backspace, Enter, Settings }

/**
 * The keyboard surface, built programmatically rather than from XML or Compose.
 *
 * Two reasons. An IME's input view has no Activity to host a Compose lifecycle, which makes Compose
 * here more machinery than it is worth; and this is the one part of the app whose code is cheap to
 * replace with a designed layout, so it stays plain and disposable. the user restyles it in Android
 * Studio; the service only needs `root` and `setStatus`.
 *
 * Chunk 1 shows a mic key that commits a fixed string, plus the minimum to stay usable in a text
 * field (space, backspace, enter) and an entry point to the settings screen — an IME cannot request
 * `RECORD_AUDIO` itself, so that screen has to be reachable from here.
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

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))

        addView(statusView, rowParams())
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(key("🎤 dictate") { onAction(KeyboardAction.Mic) }, weightParams(2f))
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

    private fun weightParams(weight: Float) =
        LinearLayout.LayoutParams(0, dp(52), weight)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
