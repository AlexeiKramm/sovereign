package io.github.alexeikramm.sovereign.text

import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceFinalizerTest {

    @Test
    fun `empty or blank text produces NOOP`() {
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve(null))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve(""))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("   "))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("\n\t  "))
    }

    @Test
    fun `already punctuated text produces NOOP`() {
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Hello world."))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Hello world.  "))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Is this working?"))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Great job!"))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Wait…"))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Items:"))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("First;"))
        assertEquals(UtteranceFinalizer.Action.NOOP, UtteranceFinalizer.resolve("Multi line\n"))
    }

    @Test
    fun `trailing comma produces REPLACE_COMMA`() {
        assertEquals(UtteranceFinalizer.Action.REPLACE_COMMA, UtteranceFinalizer.resolve("I went to the store,"))
        assertEquals(UtteranceFinalizer.Action.REPLACE_COMMA, UtteranceFinalizer.resolve("I went to the store,   "))
        assertEquals(UtteranceFinalizer.Action.REPLACE_COMMA, UtteranceFinalizer.resolve(","))
    }

    @Test
    fun `unpunctuated text produces APPEND_PERIOD`() {
        assertEquals(UtteranceFinalizer.Action.APPEND_PERIOD, UtteranceFinalizer.resolve("Hello world"))
        assertEquals(UtteranceFinalizer.Action.APPEND_PERIOD, UtteranceFinalizer.resolve("Hello world   "))
        assertEquals(UtteranceFinalizer.Action.APPEND_PERIOD, UtteranceFinalizer.resolve("Testing 123"))
        assertEquals(UtteranceFinalizer.Action.APPEND_PERIOD, UtteranceFinalizer.resolve("Moi kaikki"))
    }

    @Test
    fun `forInsertion trims and adds exactly one trailing space`() {
        assertEquals("Hello world ", UtteranceFinalizer.forInsertion("Hello world"))
        assertEquals("Hello world ", UtteranceFinalizer.forInsertion("  Hello world   "))
        // A sentence end still gets the separator, so the next dictation is not glued on.
        assertEquals("Done. ", UtteranceFinalizer.forInsertion("Done."))
        assertEquals("Really? ", UtteranceFinalizer.forInsertion("Really? "))
    }

    @Test
    fun `forInsertion ends a list capture with a blank line, not a space`() {
        // A capture that ends on a bullet must not glue the next capture onto it (Obsidian would
        // absorb the following prose into that bullet).
        assertEquals("- a\n- b\n\n", UtteranceFinalizer.forInsertion("- a\n- b"))
        assertEquals("intro:\n- a\n\n", UtteranceFinalizer.forInsertion("intro:\n- a"))
    }

    @Test
    fun `forInsertion leaves nothing to insert for blank text`() {
        assertEquals("", UtteranceFinalizer.forInsertion(null))
        assertEquals("", UtteranceFinalizer.forInsertion(""))
        assertEquals("", UtteranceFinalizer.forInsertion("   \n\t "))
    }
}
