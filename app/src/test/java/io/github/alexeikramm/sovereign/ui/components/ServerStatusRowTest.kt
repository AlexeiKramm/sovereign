package io.github.alexeikramm.sovereign.ui.components

import io.github.alexeikramm.sovereign.server.ServerClient
import io.github.alexeikramm.sovereign.server.ServerLifecycle
import io.github.alexeikramm.sovereign.server.ServerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home status row's detail line (D-136): the locked copy, the "client only when connected"
 * rule, and the manual countdown.
 */
class ServerStatusRowTest {

    private fun client(id: String, name: String) = ServerClient(deviceId = id, name = name, address = null)

    @Test
    fun `off reads the benefit hint`() {
        assertEquals(
            "Off · dictate on your PC",
            serverRowValue(ServerSnapshot(), pairedCount = 0, nowRealtimeMs = 0L),
        )
    }

    @Test
    fun `starting is called out`() {
        assertEquals(
            "Starting…",
            serverRowValue(ServerSnapshot(lifecycle = ServerLifecycle.STARTING), pairedCount = 0, nowRealtimeMs = 0L),
        )
    }

    @Test
    fun `serving shows paired and the countdown, with no client token when nobody is connected`() {
        val snapshot = ServerSnapshot(
            lifecycle = ServerLifecycle.RUNNING,
            endsAtRealtimeMs = 100L + 102L * 60_000L,
        )
        assertEquals(
            "Serving · 2 paired · 1 h 42 m left",
            serverRowValue(snapshot, pairedCount = 2, nowRealtimeMs = 100L),
        )
    }

    @Test
    fun `the client token appears only when someone is connected`() {
        val snapshot = ServerSnapshot(
            lifecycle = ServerLifecycle.RUNNING,
            endsAtRealtimeMs = 100L + 102L * 60_000L,
            clients = listOf(client("d", "Foxtop")),
        )
        assertEquals(
            "Serving · 1 client · 2 paired · 1 h 42 m left",
            serverRowValue(snapshot, pairedCount = 2, nowRealtimeMs = 100L),
        )
    }

    @Test
    fun `a manual session reads until stopped`() {
        val snapshot = ServerSnapshot(lifecycle = ServerLifecycle.RUNNING, endsAtRealtimeMs = null)
        assertEquals(
            "Serving · 2 paired · until stopped",
            serverRowValue(snapshot, pairedCount = 2, nowRealtimeMs = 0L),
        )
    }

    @Test
    fun `a failure is shown verbatim`() {
        val snapshot = ServerSnapshot(
            lifecycle = ServerLifecycle.STOPPED,
            error = "Could not start the foreground service",
        )
        assertEquals(
            "Could not start the foreground service",
            serverRowValue(snapshot, pairedCount = 0, nowRealtimeMs = 0L),
        )
    }
}
