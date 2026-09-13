package fi.ak.smartvoice.ime

/**
 * Resolves automatic terminal punctuation at the end of an utterance.
 * Ensures completed dictations never hang with a trailing comma or missing period.
 */
object UtteranceFinalizer {

    enum class Action {
        NOOP,
        REPLACE_COMMA,
        APPEND_PERIOD,
    }

    private val TERMINAL_CHARS = setOf('.', '?', '!', '…', ':', ';', '\n', '\r')

    /**
     * Inspects [text] and decides whether terminal punctuation should be appended,
     * a trailing comma replaced, or left untouched.
     */
    fun resolve(text: CharSequence?): Action {
        val trimmed = text?.trimEnd { it == ' ' || it == '\t' } ?: return Action.NOOP
        if (trimmed.isEmpty()) return Action.NOOP
        val lastChar = trimmed.last()
        return when {
            lastChar in TERMINAL_CHARS -> Action.NOOP
            lastChar == ',' -> Action.REPLACE_COMMA
            else -> Action.APPEND_PERIOD
        }
    }
}
