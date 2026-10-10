package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `＋ 30 min` extension arithmetic and the notification's action set (D-136): extend only a
 * session that actually has an auto-stop, and never move the deadline backwards.
 */
class ServerSessionExtendTest {

    @Test
    fun `extending adds 30 minutes`() {
        assertEquals(
            1_000L + 30L * 60_000L,
            ServerSessionTiming.extendedDeadline(currentDeadlineRealtimeMs = 1_000L, nowRealtimeMs = 500L),
        )
    }

    @Test
    fun `extending a past deadline restarts from now, not from the stale deadline`() {
        assertEquals(
            2_000L + 30L * 60_000L,
            ServerSessionTiming.extendedDeadline(currentDeadlineRealtimeMs = 1_000L, nowRealtimeMs = 2_000L),
        )
    }

    @Test
    fun `a timed session offers the extend action`() {
        assertTrue(ServerSessionTiming.showsExtendAction(endsAtRealtimeMs = 1_000L))
    }

    @Test
    fun `a manual session does not offer the extend action`() {
        assertFalse(ServerSessionTiming.showsExtendAction(endsAtRealtimeMs = null))
    }
}
