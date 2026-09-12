package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading and showing the timing fields. A tunable number lives in a text field, so this is the code
 * between a person's typing and a threshold that decides when their text is committed — including the
 * half-typed values that must *not* be applied.
 */
class ListenerTimingsTest {

    private val max = 60f

    @Test
    fun `a plain or decimal entry parses`() {
        assertEquals(2f, ListenerTimings.parseSeconds("2", max))
        assertEquals(2.5f, ListenerTimings.parseSeconds("2.5", max))
        assertEquals(0.25f, ListenerTimings.parseSeconds("0.25", max))
        assertEquals(3f, ListenerTimings.parseSeconds("  3  ", max))
    }

    @Test
    fun `a comma is a decimal mark, because that is how the number is written in these languages`() {
        assertEquals(2.5f, ListenerTimings.parseSeconds("2,5", max))
    }

    @Test
    fun `a half-typed or absent entry is not a value`() {
        assertNull(ListenerTimings.parseSeconds("", max))
        assertNull(ListenerTimings.parseSeconds("   ", max))
        assertNull(ListenerTimings.parseSeconds("abc", max))
        assertNull(ListenerTimings.parseSeconds("2s", max))
        // "2." does parse in Java, and that is fine: it is 2 seconds, not a lurch to something else.
        assertEquals(2f, ListenerTimings.parseSeconds("2.", max))
    }

    @Test
    fun `a negative entry never reaches a threshold`() {
        assertNull(ListenerTimings.parseSeconds("-1", max))
        assertNull(ListenerTimings.parseSeconds("-0.5", max))
    }

    @Test
    fun `zero is a value, because zero means never stop`() {
        assertEquals(0f, ListenerTimings.parseSeconds("0", max))
    }

    @Test
    fun `an absurd entry is clamped rather than accepted`() {
        assertEquals(max, ListenerTimings.parseSeconds("999", max))
    }

    @Test
    fun `a stored value is shown without trailing zeros`() {
        assertEquals("0", ListenerTimings.formatSeconds(0f))
        assertEquals("2", ListenerTimings.formatSeconds(2f))
        assertEquals("2.5", ListenerTimings.formatSeconds(2.5f))
        assertEquals("1.25", ListenerTimings.formatSeconds(1.25f))
        assertEquals("60", ListenerTimings.formatSeconds(60f))
    }

    @Test
    fun `what is shown can be read back`() {
        listOf(0f, 0.5f, 2f, 2.5f, 6f, 12.75f).forEach { seconds ->
            assertEquals(seconds, ListenerTimings.parseSeconds(ListenerTimings.formatSeconds(seconds), max))
        }
    }
}
