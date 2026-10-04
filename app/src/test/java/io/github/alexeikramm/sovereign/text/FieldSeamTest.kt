package io.github.alexeikramm.sovereign.text

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldSeamTest {

    @Test
    fun `mid-sentence lowercases the opening letter`() {
        val seam = FieldSeam(endsMidSentence = true, needsSpace = true)
        assertEquals("three in the afternoon.", seam.fitCase("Three in the afternoon."))
    }

    @Test
    fun `mid-sentence leaves an already-lowercase opening alone`() {
        val seam = FieldSeam(endsMidSentence = true, needsSpace = false)
        assertEquals("three in the afternoon.", seam.fitCase("three in the afternoon."))
    }

    @Test
    fun `mid-sentence keeps the pronoun I and its contractions capitalised`() {
        val seam = FieldSeam(endsMidSentence = true, needsSpace = true)
        assertEquals("I think so.", seam.fitCase("I think so."))
        assertEquals("I'm ready.", seam.fitCase("I'm ready."))
        assertEquals("I", seam.fitCase("I"))
    }

    @Test
    fun `at a sentence end the opening letter is capitalised`() {
        val seam = FieldSeam(endsMidSentence = false, needsSpace = true)
        assertEquals("Three in the afternoon.", seam.fitCase("three in the afternoon."))
    }

    @Test
    fun `at a sentence end an already-capitalised opening is unchanged`() {
        val seam = FieldSeam(endsMidSentence = false, needsSpace = false)
        assertEquals("Three in the afternoon.", seam.fitCase("Three in the afternoon."))
    }

    @Test
    fun `leadingSpace reflects whether the field ends on a non-space character`() {
        assertEquals(" ", FieldSeam(endsMidSentence = true, needsSpace = true).leadingSpace())
        assertEquals("", FieldSeam(endsMidSentence = true, needsSpace = false).leadingSpace())
    }

    @Test
    fun `blank text is returned unchanged`() {
        assertEquals("", FieldSeam(endsMidSentence = true, needsSpace = true).fitCase(""))
    }
}
