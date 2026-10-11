package io.github.alexeikramm.sovereign.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary rule for a continuous streaming transcript (D-140): the tail is the text after the
 * committed snapshot, and a revision is the snapshot ceasing to be a prefix of the transcript.
 */
class TranscriptTailTest {

    @Test
    fun `an empty snapshot makes the whole transcript the tail`() {
        val split = TranscriptTail.split(transcript = "hello world", committed = "")
        assertEquals("hello world", split.tail)
        assertFalse(split.revised)
        assertEquals(0, split.divergedChars)
    }

    @Test
    fun `a clean append yields the suffix and no revision`() {
        val split = TranscriptTail.split(transcript = "hello world", committed = "hello")
        assertEquals(" world", split.tail)
        assertFalse(split.revised)
        assertEquals(0, split.divergedChars)
    }

    @Test
    fun `an unchanged transcript yields an empty tail`() {
        val split = TranscriptTail.split(transcript = "hello world", committed = "hello world")
        assertEquals("", split.tail)
        assertFalse(split.revised)
    }

    @Test
    fun `a revision at the seam starts the tail at the divergence point`() {
        // Committed "hello world", transcript rewritten to "hello there".
        val split = TranscriptTail.split(transcript = "hello there", committed = "hello world")
        assertTrue(split.revised)
        assertEquals("there", split.tail)
        assertEquals("common prefix is \"hello \"", 6, TranscriptTail.commonPrefixLength("hello there", "hello world"))
        assertEquals(5, split.divergedChars)
    }

    @Test
    fun `a revision that shortens the committed text is reported, not swallowed`() {
        val split = TranscriptTail.split(transcript = "hello", committed = "hello world")
        assertTrue(split.revised)
        assertEquals("", split.tail)
        assertEquals(6, split.divergedChars)
    }

    @Test
    fun `common prefix length handles the shared-prefix boundary`() {
        assertEquals(2, TranscriptTail.commonPrefixLength("abc", "abd"))
        assertEquals(0, TranscriptTail.commonPrefixLength("xyz", "abc"))
        assertEquals(3, TranscriptTail.commonPrefixLength("abc", "abc"))
        assertEquals(1, TranscriptTail.commonPrefixLength("a", "abc"))
    }
}
