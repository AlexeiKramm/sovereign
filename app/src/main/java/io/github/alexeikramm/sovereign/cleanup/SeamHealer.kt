package io.github.alexeikramm.sovereign.cleanup

/**
 * Result of splitting committed text for seam healing.
 *
 * There is deliberately **no** field for "text before the overlap". When an overlap is healed, the
 * rewritten sentences are in the unit and the session's KV cache holds everything earlier, so the app
 * sends no extra context. Re-stating the carried tail would be redundant, and after a conversation
 * rebuild it would be unbounded — overflowing the 4096-token cache on a long utterance (D-089).
 *
 * @property overlapText The exact substring committed at the tail of the editor to delete if the caret hasn't moved.
 * @property strippedOverlap The overlap text with provisional terminal punctuation and trailing whitespace stripped.
 */
data class SeamSplit(
    val overlapText: String,
    val strippedOverlap: String,
)

/**
 * Calculates the overlap of sentences from already-committed text to heal
 * transcription seams across pause-delimited boundaries.
 */
object SeamHealer {

    private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '…')
    private val TERMINAL_CHARS = setOf('.', '?', '!', '…', ':', ';', ',', '\n', '\r', ' ', '\t')

    /**
     * Titles and honorifics that are commonly followed by a capitalized name
     * in the middle of a sentence (e.g. "Dr. Smith", "Mr. Brown").
     * These should never be treated as sentence boundaries.
     */
    val TITLES = setOf(
        "dr", "mr", "mrs", "ms", "jr", "sr", "prof", "rev", "hon", "gen", "col", "maj", "capt", "sgt", "lt",
    )

    /**
     * General abbreviations across English, Finnish, and Russian.
     * When followed by a lowercase word, they are in the middle of a sentence and ignored.
     * When followed by a capitalized word, they mark the end of the sentence.
     */
    val GENERAL_ABBREVIATIONS = setOf(
        // English
        "etc", "eg", "e.g", "ie", "i.e", "vs", "v", "inc", "ltd", "co", "corp", "dept", "fig", "no",
        "approx", "al", "st", "ave", "blvd", "rd", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        // Finnish
        "esim", "jne", "yms", "n", "v", "klo", "mm", "ns", "ts", "ks", "miel", "o.s", "puh", "s", "seur", "vrt", "tms", "nk",
        // Russian
        "г", "ул", "д", "кв", "руб", "коп", "т.д", "т.п", "и.т.д", "и.т.п", "напр", "см", "стр",
    )

    /**
     * Splits [committedText] into sentences, selecting the last [sentenceCount] sentences to rewrite.
     *
     * @param committedText Text currently committed in the active text field during this utterance.
     * @param sentenceCount Number of sentences from the tail of [committedText] to rewrite across the seam.
     *
     * @return [SeamSplit] containing the exact text to delete from the editor ([overlapText])
     *         and the stripped overlap for merging with the new ASR segment ([strippedOverlap]).
     */
    fun split(
        committedText: String,
        sentenceCount: Int = 2,
    ): SeamSplit {
        if (committedText.isBlank() || sentenceCount <= 0) {
            return SeamSplit(
                overlapText = "",
                strippedOverlap = "",
            )
        }

        val starts = findSentenceStartIndices(committedText)
        if (starts.isEmpty()) {
            return SeamSplit(
                overlapText = "",
                strippedOverlap = "",
            )
        }

        // Pick the start index for the last `sentenceCount` sentences
        val targetIndex = starts[maxOf(0, starts.size - sentenceCount)]
        val overlapText = committedText.substring(targetIndex)
        val strippedOverlap = overlapText.trimEnd { it in TERMINAL_CHARS }

        return SeamSplit(
            overlapText = overlapText,
            strippedOverlap = strippedOverlap,
        )
    }

    /**
     * Finds the 0-based character indices where each sentence starts in [text].
     *
     * Sentences are separated by terminal punctuation (`.`, `!`, `?`, `…`) followed by whitespace,
     * or by newlines. Punctuation following abbreviations (e.g. "Dr.", "esim.", "e.g.") or inside
     * decimal numbers (e.g. "3.14") is ignored.
     */
    fun findSentenceStartIndices(text: String): List<Int> {
        val firstNonWs = text.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs == -1) return emptyList()

        val starts = mutableListOf(firstNonWs)
        var i = firstNonWs
        val len = text.length

        while (i < len) {
            val c = text[i]

            if (c == '\n' || c == '\r') {
                // Newline boundary
                while (i < len && (text[i] == '\n' || text[i] == '\r' || text[i].isWhitespace())) {
                    i++
                }
                if (i < len) {
                    starts.add(i)
                }
                continue
            }

            if (c in TERMINAL_PUNCTUATION) {
                // Check if part of a punctuation cluster (e.g. "...", "?!", "...")
                val punctStart = i
                while (i < len && text[i] in TERMINAL_PUNCTUATION) {
                    i++
                }
                val punctEnd = i // exclusive
                val cluster = text.substring(punctStart, punctEnd)

                // If single period, check for abbreviations, numbers, initials
                var isBoundary = true
                if (cluster == ".") {
                    // Check decimal numbers: e.g. "3.14"
                    val prevIsDigit = punctStart > 0 && text[punctStart - 1].isDigit()
                    val nextIsDigit = punctEnd < len && text[punctEnd].isDigit()
                    if (prevIsDigit && nextIsDigit) {
                        isBoundary = false
                    }

                    // Check if followed by letters (e.g. domain.com)
                    if (punctEnd < len && !text[punctEnd].isWhitespace() && text[punctEnd] !in TERMINAL_CHARS) {
                        isBoundary = false
                    }

                    // Check abbreviation or single-letter initial
                    if (isBoundary) {
                        var tokenStart = punctStart - 1
                        while (tokenStart >= 0 && (text[tokenStart].isLetter() || text[tokenStart] == '.')) {
                            tokenStart--
                        }
                        val rawToken = text.substring(tokenStart + 1, punctStart)
                        val lowerToken = rawToken.lowercase().trim('.')
                        val normalizedToken = lowerToken.replace(".", "")

                        if (TITLES.contains(lowerToken)) {
                            isBoundary = false
                        } else if (rawToken.length == 1 && rawToken[0].isUpperCase()) {
                            // Single letter initial (e.g. "John F. Kennedy")
                            isBoundary = false
                        } else if (GENERAL_ABBREVIATIONS.contains(lowerToken) || GENERAL_ABBREVIATIONS.contains(normalizedToken)) {
                            // Find the next non-whitespace character
                            var peek = punctEnd
                            while (peek < len && text[peek].isWhitespace()) {
                                peek++
                            }
                            val nextNonWs = if (peek < len) text[peek] else null
                            // If followed by lowercase, it's mid-sentence -> ignore
                            // If followed by uppercase, it marks the end of the sentence
                            if (nextNonWs == null || nextNonWs.isLowerCase()) {
                                isBoundary = false
                            }
                        }
                    }
                }

                // If this is a valid boundary, check if followed by whitespace (or end of text)
                if (isBoundary) {
                    if (punctEnd < len && text[punctEnd].isWhitespace()) {
                        // Skip all following whitespace
                        var nextChar = punctEnd
                        while (nextChar < len && text[nextChar].isWhitespace()) {
                            nextChar++
                        }
                        if (nextChar < len) {
                            starts.add(nextChar)
                            i = nextChar
                            continue
                        }
                    }
                }
            }

            i++
        }

        return starts
    }
}
