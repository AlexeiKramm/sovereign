package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The deadline the Home row and the notification count down from (D-136). `onRunning` derives it
 * from the timeout; a stop clears it.
 */
class ServerRuntimeDeadlineTest {

    @Test
    fun `starting a timed session arms the deadline`() {
        val runtime = ServerRuntime()
        runtime.onRunning(endpoint = null, startedAtRealtimeMs = 1_000L, timeoutMinutes = 60)

        assertEquals(1_000L + 60L * 60_000L, runtime.snapshot().endsAtRealtimeMs)
    }

    @Test
    fun `a manual session has no deadline`() {
        val runtime = ServerRuntime()
        runtime.onRunning(endpoint = null, startedAtRealtimeMs = 1_000L, timeoutMinutes = 0)

        assertNull(runtime.snapshot().endsAtRealtimeMs)
    }

    @Test
    fun `moving the deadline updates the snapshot`() {
        val runtime = ServerRuntime()
        runtime.onRunning(endpoint = null, startedAtRealtimeMs = 1_000L, timeoutMinutes = 60)

        runtime.onDeadlineMoved(5_000L)

        assertEquals(5_000L, runtime.snapshot().endsAtRealtimeMs)
    }

    @Test
    fun `a clean stop clears the deadline`() {
        val runtime = ServerRuntime()
        runtime.onRunning(endpoint = null, startedAtRealtimeMs = 1_000L, timeoutMinutes = 60)

        runtime.onStopped()

        assertNull(runtime.snapshot().endsAtRealtimeMs)
    }
}
