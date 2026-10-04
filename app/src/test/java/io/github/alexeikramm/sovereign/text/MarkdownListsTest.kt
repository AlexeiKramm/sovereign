package io.github.alexeikramm.sovereign.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic Markdown list hygiene that stops Obsidian (and any CommonMark consumer) from
 * absorbing prose into a bullet via lazy continuation.
 */
class MarkdownListsTest {

    @Test
    fun `inserts a blank line between a list and following prose`() {
        assertEquals("intro:\n- a\n- b\n\nafter", MarkdownLists.normalize("intro:\n- a\n- b\nafter"))
    }

    @Test
    fun `leaves an already separated list unchanged`() {
        assertEquals("- a\n- b\n\nafter", MarkdownLists.normalize("- a\n- b\n\nafter"))
    }

    @Test
    fun `leaves prose unchanged`() {
        assertEquals("Just prose.\nStill prose.", MarkdownLists.normalize("Just prose.\nStill prose."))
    }

    @Test
    fun `leaves a list that ends the text unchanged`() {
        assertEquals("- a\n- b", MarkdownLists.normalize("- a\n- b"))
    }

    @Test
    fun `handles numbered lists`() {
        assertEquals("1. a\n2. b\n\nafter", MarkdownLists.normalize("1. a\n2. b\nafter"))
    }

    @Test
    fun `endsWithListItem detects a trailing bullet`() {
        assertTrue(MarkdownLists.endsWithListItem("- a\n- b"))
        assertTrue(MarkdownLists.endsWithListItem("- a\n- b  "))
        assertFalse(MarkdownLists.endsWithListItem("- a\nafter."))
        assertFalse(MarkdownLists.endsWithListItem("plain"))
    }
}
