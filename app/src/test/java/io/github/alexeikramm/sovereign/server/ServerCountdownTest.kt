package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact auto-stop countdown label (D-136), shared by the Home status row. Pins the rounding and
 * the manual-session sentinel so the row never reads "0 m left" while the server is still armed.
 */
class ServerCountdownTest {

    @Test
    fun `a null remaining means a manual session`() {
        assertEquals("until stopped", ServerCountdown.left(null))
    }

    @Test
    fun `hours and minutes read together`() {
        assertEquals("1 h 42 m left", ServerCountdown.left(102L * 60_000L))
    }

    @Test
    fun `whole hours omit the minutes part`() {
        assertEquals("2 h left", ServerCountdown.left(2L * 3_600_000L))
    }

    @Test
    fun `under an hour shows minutes only`() {
        assertEquals("42 m left", ServerCountdown.left(42L * 60_000L))
    }

    @Test
    fun `a partial minute rounds up`() {
        assertEquals("1 m left", ServerCountdown.left(1L))
    }

    @Test
    fun `a non-positive remaining is treated as manual`() {
        assertEquals("until stopped", ServerCountdown.left(0L))
        assertEquals("until stopped", ServerCountdown.left(-5L))
    }
}
