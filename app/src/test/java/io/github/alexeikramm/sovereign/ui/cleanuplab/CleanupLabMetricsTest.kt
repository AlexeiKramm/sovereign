package io.github.alexeikramm.sovereign.ui.cleanuplab

import io.github.alexeikramm.sovereign.data.WordDiffCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lab's metric engine. Pure, so the numbers that drive a model/profile decision are pinned here
 * rather than read off a device screenshot.
 */
class CleanupLabMetricsTest {

    private fun tokens(text: String) = WordDiffCalculator.tokenizeWords(text)

    @Test
    fun `counts the six named fillers`() {
        val raw = "um I think like we should kind of move it you know I mean honestly"
        assertEquals(5, CleanupLabMetrics.countFillers(tokens(raw)))
        assertEquals(0, CleanupLabMetrics.countFillers(tokens("I think we should move it honestly")))
    }

    @Test
    fun `counts adjacent stutters`() {
        assertEquals(2, CleanupLabMetrics.countStutters(tokens("we we had the the meeting")))
        assertEquals(0, CleanupLabMetrics.countStutters(tokens("we had the meeting")))
    }

    @Test
    fun `removed content excludes fillers and function words`() {
        val removed = CleanupLabMetrics.removedWords(
            "The quick brown fox jumps over the lazy dog",
            "The quick fox jumps over the dog",
        )
        assertEquals(listOf("brown", "lazy"), removed)
        assertEquals(2, CleanupLabMetrics.contentWordsRemoved(
            "The quick brown fox jumps over the lazy dog",
            "The quick fox jumps over the dog",
        ))
    }

    @Test
    fun `aggregate metrics describe a filler-heavy cleanup`() {
        val rawUnit = "um I think like we should kind of move it you know"
        val cleanedUnit = "I think we should move it."
        val unit = LabUnitResult(
            index = 1,
            rawUnit = rawUnit,
            cleanedUnit = cleanedUnit,
            cleanedOk = true,
            tokensBefore = 0,
            tokensAfter = 120,
            turnsInConversation = 0,
            rebuiltAfter = false,
            rebuildReason = null,
            audioStartSeconds = 0f,
            audioEndSeconds = 4f,
            activeMs = 900,
            ttftMs = 200.0,
            decodeTimeMs = 700.0,
            promptTokens = 300,
            generatedTokens = 12,
        )

        val metrics = CleanupLabMetrics.compute(rawUnit, cleanedUnit, listOf(unit))

        assertEquals(4, metrics.rawFillers)
        assertEquals(0, metrics.leftoverFillers)
        assertEquals(1.0, metrics.fillerRemovalRate, 1e-9)
        assertEquals(1, metrics.totalUnits)
        assertEquals(0, metrics.failedUnits)
        assertEquals(0, metrics.contentWordsRemoved)
    }

    @Test
    fun `counts digits and number words for rule five`() {
        assertEquals(2, CleanupLabMetrics.countDigits("it is 3 PM and 45 euros"))
        assertEquals(0, CleanupLabMetrics.countDigits("it is three PM"))
        assertEquals(3, CleanupLabMetrics.countNumberWords(tokens("twenty twenty five")))
    }

    @Test
    fun `positions map each unit onto its context-window location`() {
        fun unit(index: Int, before: Int, raw: String, cleaned: String) = LabUnitResult(
            index = index,
            rawUnit = raw,
            cleanedUnit = cleaned,
            cleanedOk = true,
            tokensBefore = before,
            tokensAfter = before + 50,
            turnsInConversation = index - 1,
            rebuiltAfter = index == 2,
            rebuildReason = if (index == 2) "TURNS" else null,
            audioStartSeconds = 0f,
            audioEndSeconds = index * 5f,
            activeMs = 100,
            ttftMs = 0.0,
            decodeTimeMs = 0.0,
            promptTokens = 0,
            generatedTokens = 0,
        )

        val positions = CleanupLabMetrics.positions(
            listOf(
                unit(1, before = 0, raw = "um hello", cleaned = "Hello."),
                unit(2, before = 2500, raw = "like hello", cleaned = "Hello."),
            ),
        )

        assertEquals(listOf(0, 2500), positions.map { it.tokensInContext })
        assertEquals(listOf(1.0, 1.0), positions.map { it.fillerRemovalRate })
        assertTrue(positions[1].rebuiltAfter)
        assertEquals(1, positions[1].turnsInConversation)
    }
}
