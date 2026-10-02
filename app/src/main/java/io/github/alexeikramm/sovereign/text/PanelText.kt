package io.github.alexeikramm.sovereign.text

/**
 * The text-shaping rules shared by the surfaces that append dictation to a cursor.
 *
 * These decide how a new unit of speech joins the text already committed: whether it needs a leading
 * space, a newline before a bullet, or nothing at all. They were written for the keyboard's panel and
 * outlived it — the cleanup pipeline ([io.github.alexeikramm.sovereign.cleanup.UtteranceCleanupState]) is now the only
 * caller, and it is pure so the rules are tested on this machine rather than discovered at a cursor.
 */
object PanelText {

    /**
     * Whether a space should be prepended before new utterance text based on the
     * character immediately before the cursor. If the previous character is non-whitespace
     * (e.g. '.', '!', '?', or a letter), returns true so consecutive utterances don't fuse.
     */
    fun shouldPrependSpace(precedingChar: Char?): Boolean {
        if (precedingChar == null) return false
        return !precedingChar.isWhitespace()
    }

    private val LIST_ITEM_REGEX = Regex("""^([-*•]|\d+[\.\)])\s.*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Whether [text] begins with a markdown bullet point or numbered item marker.
     */
    fun isListItem(text: String): Boolean = LIST_ITEM_REGEX.matches(text.trim())

    /**
     * Determines the prefix separator ("\n", " ", or "") to prepend before [incoming] when appending to [preceding].
     *
     * - Returns "\n" if incoming explicitly starts with a newline, or is a list item while preceding text exists and does not already end with a newline.
     * - Returns "" if preceding text ends with whitespace or newline, or when there is no preceding text.
     * - Returns " " for normal sentence or word continuation.
     */
    fun unitPrefix(preceding: String?, incoming: String, isFirstUnit: Boolean = false): String {
        val trimmedIncoming = incoming.trim()
        if (trimmedIncoming.isEmpty()) return ""

        val rawIncomingStartsNewline = incoming.trimStart(' ', '\t').startsWith("\n")
        val precedingText = preceding.orEmpty()
        val precedingEndsNewline = precedingText.endsWith("\n")
        val precedingEndsColon = precedingText.trimEnd().endsWith(":")

        if (rawIncomingStartsNewline || isListItem(trimmedIncoming)) {
            return when {
                precedingEndsNewline -> ""
                precedingText.isNotEmpty() -> "\n"
                isFirstUnit -> ""
                else -> ""
            }
        }

        if (precedingEndsColon) {
            return "\n"
        }

        if (precedingText.isEmpty()) {
            return ""
        }

        val lastChar = precedingText.lastOrNull()
        return if (shouldPrependSpace(lastChar)) " " else ""
    }
}
