package fi.ak.smartvoice.floating

/**
 * A word the user long-pressed in the pill's transcript, together with its character range in that
 * text. The range is what lets a future action ("Replace word", say) rewrite exactly this span
 * without hunting for it again; today's dictionary action only needs [word].
 */
data class WordSelection(val word: String, val start: Int, val end: Int)

/**
 * A fix-it action offered for a long-pressed word.
 *
 * Adding another action is deliberately small: a case here (which also supplies the menu label and
 * the form's field label, so the menu UI needs no change), plus a branch in the service's
 * form-submit handler. The long-press detection, the highlighted word, the menu bar and the input
 * form are all shared. "Replace word" — editing this one word in the transcript — would be one more
 * case and one more handler branch.
 */
enum class WordAction(
    /** Stable identifier, used for logging and as the seam between the service and the view. */
    val id: String,
    /** The menu button's text. */
    val label: String,
    /** The input form's field label. */
    val fieldLabel: String,
) {
    ADD_TO_DICTIONARY(
        id = "add_to_dictionary",
        label = "Add to dictionary",
        fieldLabel = "Should say instead",
    ),
    ;

    /** The form's heading, naming the word being fixed. */
    fun formTitle(word: String): String = when (this) {
        ADD_TO_DICTIONARY -> "Fix \u201C$word\u201D"
    }
}

/**
 * The input form a word action opens: which action, and the word it is fixing. Every action is
 * form-based today; an action that needed no input would carry no form instead.
 */
data class WordFormState(val action: WordAction, val word: String)

/**
 * A dictionary correction: [incorrect] is what the recogniser heard, [correct] is what it should
 * have been. The field order mirrors `DictionaryStore.addCorrection(correct, incorrect)` by name
 * rather than position, because the two are easy to swap.
 */
data class DictionaryFix(val correct: String, val incorrect: String)

/**
 * The fiddly parts of "long-press a word and fix it", kept free of Compose and Android so they can
 * be pinned by plain JVM unit tests instead of eyeballed on a phone:
 *
 *  - turning a long-press offset into a word ([wordAt]),
 *  - turning a pressed word plus the user's edit into a dictionary correction ([correction]).
 */
object WordFix {

    /**
     * Characters that live *inside* a word. Everything else — spaces, most punctuation — separates
     * words, so "world," selects as `world` and "well-known" selects whole.
     */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-'

    /**
     * The word under [offset] in [text], or null when there is none. A long-press that lands just
     * off a word — on its trailing space or full stop — selects the word before it, which is what a
     * cursor sitting at a word boundary should do; a long-press on leading whitespace does nothing.
     */
    fun wordAt(text: String, offset: Int): WordSelection? {
        if (text.isEmpty()) return null
        var index = offset.coerceIn(0, text.length - 1)
        if (!isWordChar(text[index])) {
            val before = index - 1
            if (before < 0 || !isWordChar(text[before])) return null
            index = before
        }
        var start = index
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = index + 1
        while (end < text.length && isWordChar(text[end])) end++
        return WordSelection(word = text.substring(start, end), start = start, end = end)
    }

    /**
     * The correction to store for a pressed [heard] word and the user's [replacement]: the pressed
     * word becomes a mishearing of the replacement.
     *
     * Null when there is nothing to store — a blank replacement, or one identical to what was heard.
     * A **case-only** fix (hearing `gmail`, typing `Gmail`) is deliberately *not* null: the store
     * turns that into an "always spell it this way" term.
     */
    fun correction(heard: String, replacement: String): DictionaryFix? {
        val wrong = heard.trim()
        val correct = replacement.trim()
        if (wrong.isEmpty() || correct.isEmpty() || correct == wrong) return null
        return DictionaryFix(correct = correct, incorrect = wrong)
    }
}
