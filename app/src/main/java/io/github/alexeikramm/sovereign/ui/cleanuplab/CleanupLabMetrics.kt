package io.github.alexeikramm.sovereign.ui.cleanuplab

import io.github.alexeikramm.sovereign.data.InlineDiffToken
import io.github.alexeikramm.sovereign.data.WordDiffCalculator
import java.util.Locale

/**
 * The quality numbers the Cleanup Lab is built to produce, keyed to the Smart Clean rules:
 * filler removal (rule 1), stutter/repetition removal (rule 2), content preservation and additions
 * (rule 4 + the safety core), and number-words vs digits (rule 5), plus failed passes and latency.
 *
 * Pure and host-tested on purpose: the phone records, this decides. Everything works on the **raw ASR**
 * as the reference — a spontaneous fixture has no human transcript.
 *
 * Deliberately *not* computed over the whole joined utterance: the alignment in [WordDiffCalculator] is
 * O(words²) and a 33-minute take is thousands of words, so per-unit (small) alignment is summed instead.
 */
data class LabMetrics(
    val totalUnits: Int,
    val failedUnits: Int,
    val rawWords: Int,
    val cleanedWords: Int,
    val wordsRemoved: Int,
    val wordsChanged: Int,
    val wordsAdded: Int,
    val rawFillers: Int,
    val leftoverFillers: Int,
    val rawStutters: Int,
    val leftoverStutters: Int,
    val rawNumberWords: Int,
    val cleanedNumberWords: Int,
    val digitsInCleaned: Int,
    val contentWordsRemoved: Int,
) {
    /** Fraction of the raw filler words that are gone from the cleaned text (1.0 when none were present). */
    val fillerRemovalRate: Double
        get() = if (rawFillers == 0) 1.0 else (rawFillers - leftoverFillers).toDouble() / rawFillers

    val stutterRemovalRate: Double
        get() = if (rawStutters == 0) 1.0 else (rawStutters - leftoverStutters).toDouble() / rawStutters

    val failureRate: Double
        get() = if (totalUnits == 0) 0.0 else failedUnits.toDouble() / totalUnits

    /** Content words deleted that the model did not re-add — the "did it drop facts?" signal. */
    val contentRemovedPer100Words: Double
        get() = if (rawWords == 0) 0.0 else contentWordsRemoved * 100.0 / rawWords
}

/** One point on the quality-vs-context curve: a unit, where it sat in the cache, and how it did. */
data class LabPositionPoint(
    val unitIndex: Int,
    val tokensInContext: Int,
    val turnsInConversation: Int,
    val audioSeconds: Float,
    val rawFillers: Int,
    val leftoverFillers: Int,
    val contentWordsRemoved: Int,
    val rebuiltAfter: Boolean,
    val cleanedOk: Boolean,
) {
    val fillerRemovalRate: Double
        get() = if (rawFillers == 0) 1.0 else (rawFillers - leftoverFillers).toDouble() / rawFillers
}

object CleanupLabMetrics {

    /** The six fillers Smart Clean names, as token sequences. Order matters only for readability. */
    val FILLERS: List<List<String>> = listOf(
        listOf("um"),
        listOf("uh"),
        listOf("you", "know"),
        listOf("i", "mean"),
        listOf("kind", "of"),
        listOf("like"),
    )

    private val FILLER_WORDS: Set<String> = FILLERS.flatten().toSet()

    private val NUMBER_WORDS: Set<String> = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million",
    )

    /** Function words: a removal is "content" only if it is none of these and not a filler. */
    private val STOPWORDS: Set<String> = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at", "for",
        "with", "from", "by", "as", "is", "are", "was", "were", "be", "been", "am", "i", "you", "he",
        "she", "it", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its", "our",
        "their", "this", "that", "these", "those", "there", "here", "not", "no", "yes", "do", "does",
        "did", "done", "have", "has", "had", "will", "would", "can", "could", "should", "may", "might",
        "must", "just", "very", "really", "too", "also", "well", "right", "yeah", "okay", "ok",
    )

    fun countFillers(tokens: List<String>): Int {
        var count = 0
        for (filler in FILLERS) {
            var i = 0
            while (i <= tokens.size - filler.size) {
                if (tokens.subList(i, i + filler.size) == filler) count++
                i++
            }
        }
        return count
    }

    /** Adjacent duplicate words — the stutter/repetition signal for rule 2. */
    fun countStutters(tokens: List<String>): Int =
        (1 until tokens.size).count { tokens[it] == tokens[it - 1] }

    fun countNumberWords(tokens: List<String>): Int = tokens.count { it in NUMBER_WORDS }

    fun countDigits(text: String): Int = Regex("\\d+").findAll(text).count()

    /** Words the model deleted outright (pure removals, not substitutions), from the inline diff. */
    fun removedWords(rawText: String, cleanedText: String): List<String> =
        WordDiffCalculator.computeInlineDiff(rawText, cleanedText).tokens
            .filterIsInstance<InlineDiffToken.Removal>()
            .flatMap { WordDiffCalculator.tokenizeWords(it.text) }

    /** Removed words that carry meaning — not fillers and not function words. */
    fun contentWordsRemoved(rawText: String, cleanedText: String): Int =
        removedWords(rawText, cleanedText).count { it !in FILLER_WORDS && it !in STOPWORDS }

    /**
     * Aggregate metrics for a run. Filler/stutter/number counts are linear; alignment is summed per
     * unit so a multi-hour transcript never allocates a full quadratic table.
     */
    fun compute(rawText: String, cleanedText: String, units: List<LabUnitResult>): LabMetrics {
        val rawTokens = WordDiffCalculator.tokenizeWords(rawText)
        val cleanedTokens = WordDiffCalculator.tokenizeWords(cleanedText)

        var removed = 0
        var changed = 0
        var added = 0
        var contentRemoved = 0
        for (unit in units) {
            // A failed pass returns its input unchanged; diffing it would read the seam overlap as
            // removed content. Failures are reported through `failedUnits`, not as deletions.
            if (!unit.cleanedOk) continue
            val diff = WordDiffCalculator.calculate(unit.modelInput, unit.cleanedUnit)
            removed += diff.wordsRemoved
            changed += diff.wordsChanged
            added += diff.wordsAdded
            contentRemoved += contentWordsRemoved(unit.modelInput, unit.cleanedUnit)
        }

        return LabMetrics(
            totalUnits = units.size,
            failedUnits = units.count { !it.cleanedOk },
            rawWords = rawTokens.size,
            cleanedWords = cleanedTokens.size,
            wordsRemoved = removed,
            wordsChanged = changed,
            wordsAdded = added,
            rawFillers = countFillers(rawTokens),
            leftoverFillers = countFillers(cleanedTokens),
            rawStutters = countStutters(rawTokens),
            leftoverStutters = countStutters(cleanedTokens),
            rawNumberWords = countNumberWords(rawTokens),
            cleanedNumberWords = countNumberWords(cleanedTokens),
            digitsInCleaned = countDigits(cleanedText),
            contentWordsRemoved = contentRemoved,
        )
    }

    /** The per-unit quality-vs-context curve, in unit order. */
    fun positions(units: List<LabUnitResult>): List<LabPositionPoint> = units.map { unit ->
        // Both sides carry the seam overlap, so this is the pass's own transformation. Comparing the
        // bare raw unit to a cleaned unit that repeats the overlap would read as a negative removal.
        val inputTokens = WordDiffCalculator.tokenizeWords(unit.modelInput)
        val cleanedTokens = WordDiffCalculator.tokenizeWords(unit.cleanedUnit)
        LabPositionPoint(
            unitIndex = unit.index,
            tokensInContext = unit.tokensBefore,
            turnsInConversation = unit.turnsInConversation,
            audioSeconds = unit.audioEndSeconds,
            rawFillers = countFillers(inputTokens),
            leftoverFillers = countFillers(cleanedTokens),
            contentWordsRemoved = if (unit.cleanedOk) contentWordsRemoved(unit.modelInput, unit.cleanedUnit) else 0,
            rebuiltAfter = unit.rebuiltAfter,
            cleanedOk = unit.cleanedOk,
        )
    }

    /** A compact, locale-pinned summary row for the results table. */
    fun describe(metrics: LabMetrics): String =
        String.format(
            Locale.US,
            "fillers %.0f%% · leftover %d · stutters %.0f%% · removed content %d (%.1f/100w) · failed %d",
            metrics.fillerRemovalRate * 100,
            metrics.leftoverFillers,
            metrics.stutterRemovalRate * 100,
            metrics.contentWordsRemoved,
            metrics.contentRemovedPer100Words,
            metrics.failedUnits,
        )
}
