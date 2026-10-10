package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The auto-stop deadline arithmetic (D-136): a timed session is armed, a manual one is not. */
class ServerSessionTimingTest {

    @Test
    fun `a timed session gets a deadline`() {
        assertEquals(
            100L + 120L * 60_000L,
            ServerSessionTiming.deadlineFrom(startedAtRealtimeMs = 100L, timeoutMinutes = 120),
        )
    }

    @Test
    fun `a manual session has no deadline`() {
        assertNull(ServerSessionTiming.deadlineFrom(startedAtRealtimeMs = 100L, timeoutMinutes = 0))
    }
}
