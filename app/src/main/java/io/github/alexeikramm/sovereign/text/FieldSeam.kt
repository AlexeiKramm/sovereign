package io.github.alexeikramm.sovereign.text

/**
 * The state of the text immediately before the insertion point, captured once at the start of a
 * cold-start utterance, plus how to make the dictated text continue it
 * (`docs/work/field-preamble-context/`).
 *
 * The cleanup model **never** sees the field text. That is deliberate: a small model asked to
 * "continue" a document tends to echo or rewrite it, so the continuation is a deterministic join —
 * a separating space plus the first letter's case — and the utterance is cleaned standalone. This
 * type is the join's pure half; it has no Android dependency and is tested on the host.
 */
data class FieldSeam(
    /** The last non-space character before the caret does not end a sentence: the text continues. */
    val endsMidSentence: Boolean,
    /** A non-space character sits immediately before the caret, so an insertion needs a leading space. */
    val needsSpace: Boolean,
) {

    /** The separator to prepend when inserting into the field ("" when the field already has one). */
    fun leadingSpace(): String = if (needsSpace) " " else ""

    /**
     * Adjusts the first letter of a cleaned utterance so it reads as a continuation of the field:
     * lowercase when the field is mid-sentence, uppercase when it ended a sentence. The pronoun `I`
     * and its contractions keep their capital (`i think` is never right). Blank text is unchanged.
     *
     * Limitation: a proper noun that opens a continuation is lowercased. The reads are opt-in, and a
     * user can protect a term through the custom vocabulary if this bites.
     */
    fun fitCase(text: String): String {
        if (text.isEmpty()) return text
        val first = text[0]
        if (endsMidSentence) {
            if (!first.isUpperCase()) return text
            if (startsWithPronounI(text)) return text
            return first.lowercaseChar() + text.substring(1)
        }
        if (!first.isLowerCase()) return text
        return first.uppercaseChar() + text.substring(1)
    }

    /** True for `I`, `I'm`, `I'll`, `I've`, `I'd` — the cases a naive lowercase would break. */
    private fun startsWithPronounI(text: String): Boolean {
        if (text[0] != 'I') return false
        if (text.length == 1) return true
        val next = text[1]
        return next.isWhitespace() || next == '\''
    }
}
