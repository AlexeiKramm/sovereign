package fi.ak.smartvoice.text

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
}
