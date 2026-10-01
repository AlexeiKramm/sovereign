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

    /**
     * Splits [cleanedText] into verbatim segments marking which words or punctuation were
     * modified, capitalized, punctuated, or added by the cleanup model compared to [rawText].
     * Concatenating all segment texts exactly reconstructs [cleanedText].
     */
    fun calculateWordDiffSegments(rawText: String, cleanedText: String): List<DiffSegment> {
        if (cleanedText.isBlank() || rawText == cleanedText) {
            return listOf(DiffSegment(cleanedText, isModified = false))
        }

        val wordRegex = Regex("\\S+")
        val rawWords = rawText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val cleanMatches = wordRegex.findAll(cleanedText).toList()

        if (cleanMatches.isEmpty()) {
            return listOf(DiffSegment(cleanedText, isModified = false))
        }

        val rawNormalized = rawWords.map { normalizeForDiff(it) }
        val cleanNormalized = cleanMatches.map { normalizeForDiff(it.value) }

        val m = rawNormalized.size
        val n = cleanNormalized.size

        val lcs = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                if (rawNormalized[i - 1] == cleanNormalized[j - 1] && rawNormalized[i - 1].isNotEmpty()) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1
                } else {
                    lcs[i][j] = maxOf(lcs[i - 1][j], lcs[i][j - 1])
                }
            }
        }

        val cleanToRawMap = mutableMapOf<Int, Int>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            if (rawNormalized[i - 1] == cleanNormalized[j - 1] && rawNormalized[i - 1].isNotEmpty() &&
                lcs[i][j] == lcs[i - 1][j - 1] + 1
            ) {
                cleanToRawMap[j - 1] = i - 1
                i--
                j--
            } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        val segments = mutableListOf<DiffSegment>()
        var lastEnd = 0

        for (idx in cleanMatches.indices) {
            val match = cleanMatches[idx]
            if (match.range.first > lastEnd) {
                segments.add(DiffSegment(cleanedText.substring(lastEnd, match.range.first), isModified = false))
            }

            val rawIndex = cleanToRawMap[idx]
            val isModified = if (rawIndex == null) {
                true
            } else {
                val rawWord = rawWords[rawIndex]
                val cleanWord = match.value
                rawWord != cleanWord
            }

            segments.add(DiffSegment(match.value, isModified = isModified))
            lastEnd = match.range.last + 1
        }

        if (lastEnd < cleanedText.length) {
            segments.add(DiffSegment(cleanedText.substring(lastEnd), isModified = false))
        }

        val merged = mutableListOf<DiffSegment>()
        for (seg in segments) {
            if (merged.isNotEmpty() && merged.last().isModified == seg.isModified) {
                merged[merged.lastIndex] = DiffSegment(merged.last().text + seg.text, seg.isModified)
            } else {
                merged.add(seg)
            }
        }

        return merged
    }

    private fun normalizeForDiff(token: String): String {
        return token.lowercase().filter { it.isLetterOrDigit() }
    }

    /**
     * Computes an inline word-level diff aligning [rawText] against [cleanedText].
     *
     * - Preserved words (including minor punctuation/casing changes) become [InlineDiffToken.Match].
     * - Deleted words (fillers, stutters) become [InlineDiffToken.Removal].
     * - Altered words become [InlineDiffToken.Substitution].
     * - Inserted words become [InlineDiffToken.Insertion].
     * Adjacent non-match operations are automatically coalesced into unified edit tokens.
     */
    fun computeInlineDiff(rawText: String, cleanedText: String): InlineDiff {
        if (rawText.isBlank() && cleanedText.isBlank()) {
            return InlineDiff(emptyList(), hasEdits = false)
        }
        if (rawText.isBlank()) {
            return InlineDiff(listOf(InlineDiffToken.Match(cleanedText)), hasEdits = false)
        }
        if (cleanedText.isBlank()) {
            return InlineDiff(listOf(InlineDiffToken.Removal(rawText)), hasEdits = true)
        }
        if (rawText == cleanedText) {
            return InlineDiff(listOf(InlineDiffToken.Match(cleanedText)), hasEdits = false)
        }

        val rawWords = rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cleanWords = cleanedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (rawWords.isEmpty() && cleanWords.isEmpty()) {
            return InlineDiff(emptyList(), hasEdits = false)
        }
        if (rawWords.isEmpty()) {
            return InlineDiff(cleanWords.map { InlineDiffToken.Insertion(it) }, hasEdits = true)
        }
        if (cleanWords.isEmpty()) {
            return InlineDiff(rawWords.map { InlineDiffToken.Removal(it) }, hasEdits = true)
        }

        val m = rawWords.size
        val n = cleanWords.size
        val dp = Array(m + 1) { DoubleArray(n + 1) }

        for (i in 0..m) dp[i][0] = i.toDouble()
        for (j in 0..n) dp[0][j] = j.toDouble()

        for (i in 1..m) {
            for (j in 1..n) {
                val r = rawWords[i - 1]
                val c = cleanWords[j - 1]
                val cost = when {
                    r == c -> 0.0
                    normalizeForDiff(r) == normalizeForDiff(c) && normalizeForDiff(r).isNotEmpty() -> 0.05
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
        val rawOps = mutableListOf<StepOp>()

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val r = rawWords[i - 1]
                val c = cleanWords[j - 1]
                val cost = when {
                    r == c -> 0.0
                    normalizeForDiff(r) == normalizeForDiff(c) && normalizeForDiff(r).isNotEmpty() -> 0.05
                    else -> 1.0
                }
                if (Math.abs(dp[i][j] - (dp[i - 1][j - 1] + cost)) < 1e-6) {
                    if (r == c || (normalizeForDiff(r) == normalizeForDiff(c) && normalizeForDiff(r).isNotEmpty())) {
                        rawOps.add(StepOp(DiffOpType.MATCH, r, c))
                    } else {
                        rawOps.add(StepOp(DiffOpType.SUB, r, c))
                    }
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && (j == 0 || Math.abs(dp[i][j] - (dp[i - 1][j] + 1.0)) < 1e-6)) {
                rawOps.add(StepOp(DiffOpType.DEL, rawWords[i - 1], null))
                i--
            } else if (j > 0) {
                rawOps.add(StepOp(DiffOpType.INS, null, cleanWords[j - 1]))
                j--
            }
        }

        rawOps.reverse()

        val tokens = mutableListOf<InlineDiffToken>()
        val pendingRaw = mutableListOf<String>()
        val pendingClean = mutableListOf<String>()

        fun flushPending() {
            if (pendingRaw.isEmpty() && pendingClean.isEmpty()) return
            if (pendingRaw.isNotEmpty() && pendingClean.isEmpty()) {
                tokens.add(InlineDiffToken.Removal(pendingRaw.joinToString(" ")))
            } else if (pendingRaw.isEmpty() && pendingClean.isNotEmpty()) {
                tokens.add(InlineDiffToken.Insertion(pendingClean.joinToString(" ")))
            } else {
                tokens.add(
                    InlineDiffToken.Substitution(
                        original = pendingRaw.joinToString(" "),
                        replacement = pendingClean.joinToString(" "),
                    )
                )
            }
            pendingRaw.clear()
            pendingClean.clear()
        }

        for (op in rawOps) {
            when (op.type) {
                DiffOpType.MATCH -> {
                    flushPending()
                    tokens.add(InlineDiffToken.Match(op.cleanWord ?: ""))
                }
                DiffOpType.DEL -> {
                    op.rawWord?.let { pendingRaw.add(it) }
                }
                DiffOpType.INS -> {
                    op.cleanWord?.let { pendingClean.add(it) }
                }
                DiffOpType.SUB -> {
                    op.rawWord?.let { pendingRaw.add(it) }
                    op.cleanWord?.let { pendingClean.add(it) }
                }
            }
        }
        flushPending()

        return InlineDiff(tokens)
    }
}

private enum class DiffOpType { MATCH, SUB, DEL, INS }
private data class StepOp(val type: DiffOpType, val rawWord: String?, val cleanWord: String?)

/**
 * An atomic token within an inline diff comparing raw speech to cleaned text.
 */
sealed class InlineDiffToken {
    /** An unchanged word or phrase (carrying normalized capitalization and punctuation). */
    data class Match(val text: String) : InlineDiffToken()

    /** Words present in raw speech but deleted/removed in cleanup (e.g. fillers, stutters). */
    data class Removal(val text: String) : InlineDiffToken()

    /** Words in raw speech substituted by the cleanup model (e.g. grammar, vocabulary fixes). */
    data class Substitution(val original: String, val replacement: String) : InlineDiffToken()

    /** Words inserted by the cleanup model not present in raw speech. */
    data class Insertion(val text: String) : InlineDiffToken()
}

/**
 * Result of computing an inline diff between raw speech and cleaned text.
 */
data class InlineDiff(
    val tokens: List<InlineDiffToken>,
    val hasEdits: Boolean = tokens.any { it !is InlineDiffToken.Match },
)

/**
 * A continuous segment of cleaned text indicating whether it represents a delta spot
 * modified or added during LLM cleanup.
 */
data class DiffSegment(
    val text: String,
    val isModified: Boolean,
)
