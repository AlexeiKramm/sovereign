package fi.ak.smartvoice.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffCalculatorTest {

    @Test
    fun `tokenizeWords handles punctuation contractions and casing`() {
        val tokens = WordDiffCalculator.tokenizeWords("Hello, world! It's a test... Right?")
        assertEquals(listOf("hello", "world", "it's", "a", "test", "right"), tokens)
    }

    @Test
    fun `verbatim returns zero cleaned words and identical raw and delivered counts`() {
        val result = WordDiffCalculator.verbatim("Just speaking words here")
        assertEquals(4, result.rawWords)
        assertEquals(4, result.deliveredWords)
        assertEquals(0, result.wordsRemoved)
        assertEquals(0, result.wordsChanged)
        assertEquals(0, result.wordsAdded)
        assertEquals(0, result.wordsCleaned)
    }

    @Test
    fun `pure filler removal increments wordsRemoved and wordsCleaned`() {
        val raw = "So um uh I went to the store"
        val clean = "I went to the store."
        val result = WordDiffCalculator.calculate(raw, clean)

        assertEquals(8, result.rawWords)
        assertEquals(5, result.deliveredWords)
        assertEquals(3, result.wordsRemoved) // "so", "um", "uh"
        assertEquals(0, result.wordsChanged)
        assertEquals(0, result.wordsAdded)
        assertEquals(3, result.wordsCleaned)
    }

    @Test
    fun `pure grammar substitution increments wordsChanged and wordsCleaned`() {
        val raw = "She dont know nothing"
        val clean = "She doesn't know anything."
        val result = WordDiffCalculator.calculate(raw, clean)

        assertEquals(4, result.rawWords)
        assertEquals(4, result.deliveredWords)
        assertEquals(0, result.wordsRemoved)
        assertEquals(2, result.wordsChanged) // "dont" -> "doesn't", "nothing" -> "anything"
        assertEquals(0, result.wordsAdded)
        assertEquals(2, result.wordsCleaned)
    }

    @Test
    fun `mixed edits remove fillers and correct words`() {
        val raw = "Well um she dont like it"
        val clean = "She doesn't like it."
        val result = WordDiffCalculator.calculate(raw, clean)

        assertEquals(6, result.rawWords)
        assertEquals(4, result.deliveredWords)
        assertEquals(2, result.wordsRemoved) // "well", "um"
        assertEquals(1, result.wordsChanged) // "dont" -> "doesn't"
        assertEquals(0, result.wordsAdded)
        assertEquals(3, result.wordsCleaned)
    }

    @Test
    fun `insertions increment wordsAdded`() {
        val raw = "In spite of"
        val clean = "In spite of it all."
        val result = WordDiffCalculator.calculate(raw, clean)

        assertEquals(3, result.rawWords)
        assertEquals(5, result.deliveredWords)
        assertEquals(0, result.wordsRemoved)
        assertEquals(0, result.wordsChanged)
        assertEquals(2, result.wordsAdded) // "it", "all"
        assertEquals(0, result.wordsCleaned)
    }

    @Test
    fun `empty strings return zero counts without error`() {
        val result = WordDiffCalculator.calculate("", "")
        assertEquals(0, result.rawWords)
        assertEquals(0, result.deliveredWords)
        assertEquals(0, result.wordsCleaned)
    }

    @Test
    fun `identical punctuation difference does not count as word edits`() {
        val raw = "hello world how are you"
        val clean = "Hello, world! How are you?"
        val result = WordDiffCalculator.calculate(raw, clean)

        assertEquals(5, result.rawWords)
        assertEquals(5, result.deliveredWords)
        assertEquals(0, result.wordsRemoved)
        assertEquals(0, result.wordsChanged)
        assertEquals(0, result.wordsAdded)
        assertEquals(0, result.wordsCleaned)
    }

    @Test
    fun `calculateWordDiffSegments reconstructs full text and identifies modifications`() {
        val raw = "right so the problem is that"
        val clean = "Right, so the problem is that."
        val segments = WordDiffCalculator.calculateWordDiffSegments(raw, clean)

        // Concatenating all segment texts exactly reconstructs clean text
        val reconstructed = segments.joinToString("") { it.text }
        assertEquals(clean, reconstructed)

        // First word "Right," is modified (capitalized + comma)
        assertEquals("Right,", segments.first().text)
        assertEquals(true, segments.first().isModified)

        // Last word "that." has period added
        assertEquals("that.", segments.last().text)
        assertEquals(true, segments.last().isModified)
    }

    @Test
    fun `calculateWordDiffSegments handles verbatim identical strings`() {
        val text = "identical verbatim sentence"
        val segments = WordDiffCalculator.calculateWordDiffSegments(text, text)
        assertEquals(1, segments.size)
        assertEquals(text, segments[0].text)
        assertEquals(false, segments[0].isModified)
    }

    @Test
    fun `computeInlineDiff with identical text returns Match without edits`() {
        val text = "This is a clean sentence."
        val diff = WordDiffCalculator.computeInlineDiff(text, text)
        assertEquals(false, diff.hasEdits)
        assertEquals(1, diff.tokens.size)
        assertEquals(InlineDiffToken.Match(text), diff.tokens[0])
    }

    @Test
    fun `computeInlineDiff with casing and punctuation difference treats as Match without edits`() {
        val raw = "hello world how are you"
        val clean = "Hello, world! How are you?"
        val diff = WordDiffCalculator.computeInlineDiff(raw, clean)
        assertEquals(false, diff.hasEdits)
        assertEquals(5, diff.tokens.size)
        assertEquals("Hello,", (diff.tokens[0] as InlineDiffToken.Match).text)
        assertEquals("world!", (diff.tokens[1] as InlineDiffToken.Match).text)
    }

    @Test
    fun `computeInlineDiff with filler removal detects Removal tokens and coalesces adjacent fillers`() {
        val raw = "So um uh I went to the store"
        val clean = "So, I went to the store."
        val diff = WordDiffCalculator.computeInlineDiff(raw, clean)
        assertEquals(true, diff.hasEdits)
        val removals = diff.tokens.filterIsInstance<InlineDiffToken.Removal>()
        assertEquals(1, removals.size)
        assertEquals("um uh", removals[0].text)
    }

    @Test
    fun `computeInlineDiff with grammar substitution detects Substitution tokens`() {
        val raw = "She dont know"
        val clean = "She doesn't know."
        val diff = WordDiffCalculator.computeInlineDiff(raw, clean)
        assertEquals(true, diff.hasEdits)
        val substitutions = diff.tokens.filterIsInstance<InlineDiffToken.Substitution>()
        assertEquals(1, substitutions.size)
        assertEquals("dont", substitutions[0].original)
        assertEquals("doesn't", substitutions[0].replacement)
    }

    @Test
    fun `computeInlineDiff with multi-word substitution coalesces into single token`() {
        val raw = "I wanna go"
        val clean = "I want to go."
        val diff = WordDiffCalculator.computeInlineDiff(raw, clean)
        assertEquals(true, diff.hasEdits)
        val substitutions = diff.tokens.filterIsInstance<InlineDiffToken.Substitution>()
        assertEquals(1, substitutions.size)
        assertEquals("wanna", substitutions[0].original)
        assertEquals("want to", substitutions[0].replacement)
    }

    @Test
    fun `computeInlineDiff with insertion detects Insertion tokens`() {
        val raw = "went store"
        val clean = "went to the store."
        val diff = WordDiffCalculator.computeInlineDiff(raw, clean)
        assertEquals(true, diff.hasEdits)
        val insertions = diff.tokens.filterIsInstance<InlineDiffToken.Insertion>()
        assertEquals(1, insertions.size)
        assertEquals("to the", insertions[0].text)
    }
}
