package io.github.alexeikramm.sovereign.text

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
     * The text as it should be typed into the field: trimmed, with a separator. Normally that is a
     * single trailing space — the pill has no memory of where the cursor sits between sessions, so
     * the space is the cheap guarantee that the next dictation does not run into this one ("Done. ").
     * But when the capture **ends on a Markdown list item**, a space would glue the next capture onto
     * that bullet's line, where Obsidian absorbs it (lazy continuation). In that case the separator
     * is a blank line, which ends the list. Blank input stays blank.
     */
    fun forInsertion(text: CharSequence?): String {
        val trimmed = text?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (MarkdownLists.endsWithListItem(trimmed)) "$trimmed\n\n" else "$trimmed "
    }
}
