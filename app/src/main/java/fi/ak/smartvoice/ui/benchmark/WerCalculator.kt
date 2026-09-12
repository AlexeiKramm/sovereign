package fi.ak.smartvoice.ui.benchmark

/**
 * Calculates Word Error Rate (WER) and Character Error Rate (CER) for transcription evaluation.
 *
 * In speech cleanup evaluation, punctuation marks (periods, commas, colons, dashes, question marks)
 * are critical tokens because the cleanup stage restores missing punctuation and capitalization.
 * This calculator tokenizes words and punctuation marks so that missing or incorrect punctuation
 * is accurately reflected in error rates rather than ignored.
 */
object WerCalculator {

    /**
     * Splits text into normalized tokens (words and individual punctuation marks).
     *
     * Example: `"And so, my fellow Americans:"` ->
     * `["and", "so", ",", "my", "fellow", "americans", ":"]`
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("[\\p{L}\\p{N}]+|[.,:;!?—\\-]")
        return regex.findAll(text.lowercase())
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * Computes Word Error Rate: Levenshtein distance between token lists divided by reference token count.
     * Returns 0.0 for an exact match.
     */
    fun calculateWer(reference: String, hypothesis: String): Double {
        val refTokens = tokenize(reference)
        val hypTokens = tokenize(hypothesis)
        if (refTokens.isEmpty()) return if (hypTokens.isEmpty()) 0.0 else 1.0
        val dist = levenshtein(refTokens, hypTokens)
        return dist.toDouble() / refTokens.size
    }

    /**
     * Computes Character Error Rate: Levenshtein distance between normalized character sequences
     * divided by reference character count.
     */
    fun calculateCer(reference: String, hypothesis: String): Double {
        val refChars = reference.lowercase().trim().replace(Regex("\\s+"), " ").toList()
        val hypChars = hypothesis.lowercase().trim().replace(Regex("\\s+"), " ").toList()
        if (refChars.isEmpty()) return if (hypChars.isEmpty()) 0.0 else 1.0
        val dist = levenshtein(refChars, hypChars)
        return dist.toDouble() / refChars.size
    }

    /**
     * Computes Levenshtein edit distance between two sequences.
     */
    fun <T> levenshtein(s: List<T>, t: List<T>): Int {
        val m = s.size
        val n = t.size
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }
}
