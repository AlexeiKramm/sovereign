package io.github.alexeikramm.sovereign.ui.components

import io.github.alexeikramm.sovereign.data.InlineDiffToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DIFF view's spacing rule: a punctuation edit the cleanup made belongs to the word beside
 * it, so `issue` → `issue.` must render as `issue[+ .]`, never `issue [+ .]`.
 */
class InlineDiffSpacingTest {

    @Test
    fun `a punctuation edit attaches to the word it belongs to`() {
        assertFalse(needsSpaceBefore(InlineDiffToken.Insertion(".")))
        assertFalse(needsSpaceBefore(InlineDiffToken.Insertion(",")))
        assertFalse(needsSpaceBefore(InlineDiffToken.Removal(".")))
        assertFalse(needsSpaceBefore(InlineDiffToken.Removal(",")))
    }

    @Test
    fun `word tokens and word edits stay whitespace-separated`() {
        assertTrue(needsSpaceBefore(InlineDiffToken.Match("issue")))
        assertTrue(needsSpaceBefore(InlineDiffToken.Insertion("to the")))
        assertTrue(needsSpaceBefore(InlineDiffToken.Removal("um uh")))
        assertTrue(needsSpaceBefore(InlineDiffToken.Substitution("Make", "make")))
    }
}
