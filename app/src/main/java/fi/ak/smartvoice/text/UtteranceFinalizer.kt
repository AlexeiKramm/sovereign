package fi.ak.smartvoice.text

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

    /**
     * The text as it should be typed into the field: trimmed, with a single trailing space. The
     * next dictation must not run into this one, and the pill has no memory of where the cursor
     * sits between sessions, so the trailing space is the cheap guarantee of a separator. It comes
     * after any terminal punctuation too ("Done. "), which is how sentences read anyway. Blank
     * input stays blank: there is nothing to insert.
     */
    fun forInsertion(text: CharSequence?): String {
        val trimmed = text?.toString()?.trim().orEmpty()
        return if (trimmed.isEmpty()) "" else "$trimmed "
    }
}
