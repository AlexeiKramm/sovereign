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

    /**
     * Computes a word-by-word alignment diff between a reference text and hypothesis text.
     * Highlights exact matches, word substitutions, deletions (missing words), and insertions (extra words).
     */
    fun computeDiff(reference: String, hypothesis: String): List<DiffItem> {
        val refWords = reference.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hypWords = hypothesis.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (refWords.isEmpty()) {
            return hypWords.map { DiffItem(DiffOp.INSERTION, it) }
        }
        if (hypWords.isEmpty()) {
            return refWords.map { DiffItem(DiffOp.DELETION, "", it) }
        }

        fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() }

        val m = refWords.size
        val n = hypWords.size
        val dp = Array(m + 1) { DoubleArray(n + 1) }

        for (i in 0..m) dp[i][0] = i.toDouble()
        for (j in 0..n) dp[0][j] = j.toDouble()

        for (i in 1..m) {
            for (j in 1..n) {
                val r = refWords[i - 1]
                val h = hypWords[j - 1]
                val cost = when {
                    r == h -> 0.0
                    norm(r) == norm(h) -> 0.2
                    else -> 1.0
                }
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1.0,
                    dp[i][j - 1] + 1.0,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }

        var i = m
        var j = n
        val result = mutableListOf<DiffItem>()

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val r = refWords[i - 1]
                val h = hypWords[j - 1]
                val cost = when {
                    r == h -> 0.0
                    norm(r) == norm(h) -> 0.2
                    else -> 1.0
                }
                if (Math.abs(dp[i][j] - (dp[i - 1][j - 1] + cost)) < 1e-6) {
                    if (r == h) {
                        result.add(DiffItem(DiffOp.MATCH, h))
                    } else {
                        result.add(DiffItem(DiffOp.SUBSTITUTION, h, r))
                    }
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && (j == 0 || Math.abs(dp[i][j] - (dp[i - 1][j] + 1.0)) < 1e-6)) {
                result.add(DiffItem(DiffOp.DELETION, "", refWords[i - 1]))
                i--
            } else if (j > 0) {
                result.add(DiffItem(DiffOp.INSERTION, hypWords[j - 1]))
                j--
            }
        }

        return result.reversed()
    }
}

enum class DiffOp {
    MATCH,
    SUBSTITUTION,
    INSERTION,
    DELETION,
}

data class DiffItem(
    val op: DiffOp,
    val text: String,
    val expected: String? = null,
)
