package fi.ak.smartvoice.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WerCalculatorTest {

    @Test
    fun `tokenize splits words and punctuation marks`() {
        val text = "And so, my fellow Americans: ask not what your country can do for you — ask what you can do for your country."
        val tokens = WerCalculator.tokenize(text)
        assertEquals(
            listOf(
                "and", "so", ",", "my", "fellow", "americans", ":", "ask", "not", "what", "your",
                "country", "can", "do", "for", "you", "—", "ask", "what", "you", "can", "do",
                "for", "your", "country", "."
            ),
            tokens,
        )
    }

    @Test
    fun `tokenize handles finnish letters and empty strings`() {
        assertEquals(emptyList<String>(), WerCalculator.tokenize(""))
        assertEquals(emptyList<String>(), WerCalculator.tokenize("   "))
        val finnish = "Tämä on testi, eikö vain?"
        assertEquals(listOf("tämä", "on", "testi", ",", "eikö", "vain", "?"), WerCalculator.tokenize(finnish))
    }

    @Test
    fun `calculateWer exact match returns zero`() {
        val text = "God as a dialectical principle of the universe."
        assertEquals(0.0, WerCalculator.calculateWer(text, text), 0.0001)
        assertEquals(0.0, WerCalculator.calculateCer(text, text), 0.0001)
    }

    @Test
    fun `calculateWer detects missing punctuation`() {
        val ref = "And so, my fellow Americans: ask not what your country can do for you — ask what you can do for your country."
        val hyp = "And so my fellow Americans ask not what your country can do for you. Ask what you can do for your country"

        val wer = WerCalculator.calculateWer(ref, hyp)
        // Missing comma, missing colon, dot instead of em dash, missing final dot -> 4 errors / 26 tokens = 15.38%
        assertTrue("WER should be > 10%, was $wer", wer > 0.10)
        assertTrue("WER should be < 20%, was $wer", wer < 0.20)

        val cer = WerCalculator.calculateCer(ref, hyp)
        assertTrue("CER should be > 0.0, was $cer", cer > 0.0)
    }

    @Test
    fun `calculateWer edge cases empty strings`() {
        assertEquals(0.0, WerCalculator.calculateWer("", ""), 0.0001)
        assertEquals(1.0, WerCalculator.calculateWer("hello", ""), 0.0001)
        assertEquals(1.0, WerCalculator.calculateWer("", "hello"), 0.0001)
    }
}
