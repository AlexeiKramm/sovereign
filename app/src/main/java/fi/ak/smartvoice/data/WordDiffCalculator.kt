package fi.ak.smartvoice.data

/**
 * Results of comparing raw speech recognition output against post-cleanup LLM output.
 *
 * @param rawWords Total number of words in the raw speech.
 * @param deliveredWords Total number of words in the final output delivered to the user.
 * @param wordsRemoved Number of words removed from raw speech (e.g. fillers like "um", "uh", stutters).
 * @param wordsChanged Number of words altered for grammar, agreement, vocabulary, or formatting.
 * @param wordsAdded Number of words inserted by the cleanup model.
 * @param wordsCleaned Total count of words modified during cleanup (wordsRemoved + wordsChanged).
 */
data class WordDiffResult(
    val rawWords: Int,
    val deliveredWords: Int,
    val wordsRemoved: Int,
    val wordsChanged: Int,
    val wordsAdded: Int,
    val wordsCleaned: Int = wordsRemoved + wordsChanged,
)

object WordDiffCalculator {

    /**
     * Splits text into normalized lowercase word tokens, stripping punctuation and symbols.
     * E.g. "So, um... I went." -> ["so", "um", "i", "went"]
     */
    fun tokenizeWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("[\\p{L}\\p{N}']+")
        return regex.findAll(text.lowercase())
            .map { it.value.trim { ch -> ch == '\'' } }
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * Returns a verbatim diff result where no cleanup was performed.
     */
    fun verbatim(rawText: String): WordDiffResult {
        val words = tokenizeWords(rawText).size
        return WordDiffResult(
            rawWords = words,
            deliveredWords = words,
            wordsRemoved = 0,
            wordsChanged = 0,
            wordsAdded = 0,
            wordsCleaned = 0,
        )
    }

    /**
     * Compares raw ASR text and cleaned LLM text using dynamic programming sequence alignment
     * to identify removed words (fillers), changed words (grammar/substitutions), and added words.
     */
    fun calculate(rawText: String, cleanedText: String): WordDiffResult {
        val rawTokens = tokenizeWords(rawText)
        val cleanTokens = tokenizeWords(cleanedText)

        val m = rawTokens.size
        val n = cleanTokens.size

        if (m == 0 && n == 0) {
            return WordDiffResult(0, 0, 0, 0, 0, 0)
        }
        if (m == 0) {
            return WordDiffResult(
                rawWords = 0,
                deliveredWords = n,
                wordsRemoved = 0,
                wordsChanged = 0,
                wordsAdded = n,
                wordsCleaned = 0,
            )
        }
        if (n == 0) {
            return WordDiffResult(
                rawWords = m,
                deliveredWords = 0,
                wordsRemoved = m,
                wordsChanged = 0,
                wordsAdded = 0,
                wordsCleaned = m,
            )
        }

        // DP table for Levenshtein edit distance between token sequences
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val match = rawTokens[i - 1] == cleanTokens[j - 1]
                val cost = if (match) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,        // deletion (word removed)
                    dp[i][j - 1] + 1,        // insertion (word added)
                    dp[i - 1][j - 1] + cost, // match or substitution (word changed)
                )
            }
        }

        // Backtrack to count operations
        var i = m
        var j = n
        var removed = 0
        var changed = 0
        var added = 0

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val match = rawTokens[i - 1] == cleanTokens[j - 1]
                val cost = if (match) 0 else 1
                if (dp[i][j] == dp[i - 1][j - 1] + cost) {
                    if (!match) {
                        changed++
                    }
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                removed++
                i--
            } else if (j > 0 && dp[i][j] == dp[i][j - 1] + 1) {
                added++
                j--
            } else {
                if (i > 0) { removed++; i-- }
                else if (j > 0) { added++; j-- }
            }
        }

        return WordDiffResult(
            rawWords = m,
            deliveredWords = n,
            wordsRemoved = removed,
            wordsChanged = changed,
            wordsAdded = added,
            wordsCleaned = removed + changed,
        )
    }
}
