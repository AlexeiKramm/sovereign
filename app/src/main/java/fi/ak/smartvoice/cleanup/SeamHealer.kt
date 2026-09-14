package fi.ak.smartvoice.cleanup

/**
 * Result of splitting committed text for seam healing.
 *
 * @property anchorContext Preceding words used strictly as context to ensure the start of the overlap is not erroneously capitalized.
 * @property overlapText The exact substring committed at the tail of the editor to delete if the caret hasn't moved.
 * @property strippedOverlap The overlap text with provisional terminal punctuation and trailing whitespace stripped.
 */
data class SeamSplit(
    val anchorContext: String,
    val overlapText: String,
    val strippedOverlap: String,
)

/**
 * Calculates the overlap and anchor context from already-committed text to heal
 * transcription seams across pause-delimited boundaries.
 */
object SeamHealer {

    private val TERMINAL_CHARS = setOf('.', '?', '!', '…', ':', ';', ',', '\n', '\r', ' ', '\t')

    /**
     * Splits [committedText] into an anchor context and a mutable overlap tail.
     *
     * @param committedText Text currently committed in the active text field during this utterance.
     * @param overlapWords Number of words from the tail of [committedText] to rewrite/heal across the seam.
     * @param totalContextWords Total context window from the tail (anchor context + overlap).
     *
     * @return [SeamSplit] containing the read-only anchor, the exact text to delete from the editor,
     *         and the stripped overlap for merging with the new ASR segment.
     */
    fun split(
        committedText: String,
        overlapWords: Int = 5,
        totalContextWords: Int = 15,
    ): SeamSplit {
        if (committedText.isBlank() || overlapWords <= 0) {
            return SeamSplit(
                anchorContext = "",
                overlapText = "",
                strippedOverlap = "",
            )
        }

        // Find non-whitespace word sequences and their indices
        val wordMatches = Regex("\\S+").findAll(committedText).toList()
        if (wordMatches.isEmpty()) {
            return SeamSplit(
                anchorContext = "",
                overlapText = "",
                strippedOverlap = "",
            )
        }

        val totalWords = wordMatches.size
        val effectiveOverlapWords = minOf(overlapWords, totalWords)
        val overlapStartIndex = totalWords - effectiveOverlapWords

        // The overlap starts at the beginning of the word at overlapStartIndex
        // and extends to the very end of committedText (preserving trailing spaces/punctuation)
        val overlapStartChar = wordMatches[overlapStartIndex].range.first
        val overlapText = committedText.substring(overlapStartChar)

        // Stripped overlap: strips trailing terminal punctuation and spaces so it flows
        // naturally into the incoming speech segment
        val strippedOverlap = overlapText.trimEnd { it in TERMINAL_CHARS }

        // Anchor context: preceding words up to (totalContextWords - effectiveOverlapWords)
        val maxAnchorWords = maxOf(0, totalContextWords - effectiveOverlapWords)
        val desiredAnchorWords = minOf(maxAnchorWords, overlapStartIndex)
        val anchorContext = if (desiredAnchorWords > 0) {
            val anchorStartIndex = overlapStartIndex - desiredAnchorWords
            val anchorStartChar = wordMatches[anchorStartIndex].range.first
            committedText.substring(anchorStartChar, overlapStartChar).trim()
        } else {
            ""
        }

        return SeamSplit(
            anchorContext = anchorContext,
            overlapText = overlapText,
            strippedOverlap = strippedOverlap,
        )
    }
}
