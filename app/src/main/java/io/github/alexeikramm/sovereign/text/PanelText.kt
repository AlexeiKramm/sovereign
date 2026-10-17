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
     * Characters that attach to the word before them and must never be pushed away by a space.
     *
     * The streaming recogniser continues one transcript across a pause, so a pause's punctuation
     * arrives at the *start* of the next unit: after "…I think" the next unit can be ", and more".
     * Inserting a boundary space there produced "think , and more" in the live and committed text.
     */
    private val NO_SPACE_BEFORE = setOf(
        ',', '.', '!', '?', ';', ':', ')', ']', '}', '…', '”', '’', '»', '%',
    )

    /**
     * Whether a space should be prepended before new utterance text based on the
     * character immediately before the cursor. If the previous character is non-whitespace
     * (e.g. '.', '!', '?', or a letter), returns true so consecutive utterances don't fuse.
     */
    fun shouldPrependSpace(precedingChar: Char?): Boolean {
        if (precedingChar == null) return false
        return !precedingChar.isWhitespace()
    }

    /**
     * As [shouldPrependSpace], but also honours [incoming]'s own leading character: a unit that
     * begins with whitespace already supplies its boundary, and one that begins with punctuation
     * (see [NO_SPACE_BEFORE]) must attach to the preceding word. This is the rule the pipeline uses
     * when it joins segments — the recogniser's own spacing is authoritative and the pipeline only
     * fills a gap that is genuinely missing.
     */
    fun shouldPrependSpace(precedingChar: Char?, incoming: String): Boolean {
        if (!shouldPrependSpace(precedingChar)) return false
        val first = incoming.firstOrNull() ?: return false
        if (first.isWhitespace()) return false
        return first !in NO_SPACE_BEFORE
    }

    /**
     * The separator (" " or "") between two pieces of dictation text that are being concatenated —
     * the lighter sibling of [unitPrefix], with no list/newline shaping. Used for the live provisional
     * text and the raw (cleanup-off) commit path.
     */
    fun joinSeparator(preceding: String?, incoming: String): String =
        if (shouldPrependSpace(preceding?.lastOrNull(), incoming)) " " else ""

    /** [units] concatenated by [joinSeparator], skipping empties — the pipeline's provisional join. */
    fun joinUnits(units: Iterable<String>): String {
        val out = StringBuilder()
        for (unit in units) {
            if (unit.isEmpty()) continue
            if (shouldPrependSpace(out.lastOrNull(), unit)) out.append(' ')
            out.append(unit)
        }
        return out.toString()
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
        return if (shouldPrependSpace(lastChar, trimmedIncoming)) " " else ""
    }
}
