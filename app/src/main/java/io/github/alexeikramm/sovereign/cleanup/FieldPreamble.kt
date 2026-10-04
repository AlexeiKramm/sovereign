package io.github.alexeikramm.sovereign.cleanup

/**
 * Extracts the **field preamble**: the last N sentences of a text field that precede the cursor.
 *
 * This is the "pure context, never rewritten" input for a cold-start utterance
 * (`docs/work/field-preamble-context/`). It differs from [SeamHealer]'s overlap on purpose:
 *
 * - the overlap is **rewritten and replaced**, so its trailing punctuation is provisional and gets
 *   stripped; the preamble is **shown to the model as-is**, so a trailing `.` stays;
 * - the overlap is taken from the pill's own committed text; the preamble is read from the target
 *   field, so the caller supplies the text and the cursor.
 *
 * Sentence boundaries are deliberately the ones [SeamHealer.findSentenceStartIndices] already
 * defines (terminal punctuation followed by whitespace, newlines, with abbreviations and decimals
 * ignored), so the two notions of "sentence" cannot drift apart.
 */
object FieldPreamble {

    /**
     * Returns the last [sentences] sentence-starts of [text] at or before [cursor], or null when
     * there is nothing useful to send.
     *
     * A sentence starts at the first non-whitespace character, after a newline, or immediately after
     * terminal punctuation (`.` `!` `?` `…`) that is followed by whitespace; abbreviations ("Dr.",
     * "esim."), initials and decimals do not start one. That rule is exactly
     * [SeamHealer.findSentenceStartIndices], so "sentence" means the same thing here as at the seam.
     *
     * A trailing **partial** sentence is included: dictating into a field that ends mid-sentence is
     * exactly the case this exists for, and the fragment is context, not something to complete here.
     * Fewer sentences than requested simply returns everything before the cursor.
     *
     * @param text The whole editable field's text, or null if the field is not readable.
     * @param cursor The insertion index into [text] (UTF-16, as Android reports selection). Values
     *   outside the string are clamped; a cursor at 0 yields null.
     * @param sentences How many sentence-starts to include; non-positive yields null.
     * @return The tail to use as pure context, trailing whitespace trimmed, or null when blank.
     */
    fun extract(text: String?, cursor: Int, sentences: Int): String? {
        if (text.isNullOrEmpty() || sentences <= 0) return null
        val end = cursor.coerceIn(0, text.length)
        if (end == 0) return null

        val prefix = text.substring(0, end)
        val starts = SeamHealer.findSentenceStartIndices(prefix)
        if (starts.isEmpty()) return null

        val from = starts[maxOf(0, starts.size - sentences)]
        return prefix.substring(from).trimEnd().ifBlank { null }
    }
}
