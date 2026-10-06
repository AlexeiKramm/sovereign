package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide state the Chunk-2a dashboard reads. Before this chunk the Chunk-1 service kept
 * `running` private and the engine emitted state only to the wire, so the UI had nothing to observe;
 * these pin the transitions the dashboard depends on.
 */
class ServerRuntimeTest {

    @Test
    fun `start moves to RUNNING and carries the endpoint and timeout`() {
        val runtime = ServerRuntime()
        runtime.onStarting()
        assertEquals(ServerLifecycle.STARTING, runtime.snapshot().lifecycle)

        runtime.onRunning("wss://192.0.2.50:8765/sovereign", startedAtRealtimeMs = 42L, timeoutMinutes = 120)

        val snapshot = runtime.snapshot()
        assertEquals(ServerLifecycle.RUNNING, snapshot.lifecycle)
        assertEquals("wss://192.0.2.50:8765/sovereign", snapshot.endpoint)
        assertEquals(42L, snapshot.startedAtRealtimeMs)
        assertEquals(120, snapshot.timeoutMinutes)
        assertNull(snapshot.error)
    }

    @Test
    fun `a failed start keeps the reason visible after teardown`() {
        val runtime = ServerRuntime()
        runtime.onStarting()
        runtime.onFailed("microphone unavailable")

        assertEquals(ServerLifecycle.STOPPED, runtime.snapshot().lifecycle)
        assertEquals("microphone unavailable", runtime.snapshot().error)

        // The service stops itself after a failure, so onStopped follows; the reason must survive.
        runtime.onStopped()
        assertEquals("microphone unavailable", runtime.snapshot().error)
        assertEquals(ServerLifecycle.STOPPED, runtime.snapshot().lifecycle)
    }

    @Test
    fun `a clean stop hides the previous failure and clears the session view`() {
        val runtime = ServerRuntime()
        runtime.onRunning(endpoint = "wss://10.0.0.5:8765/sovereign", startedAtRealtimeMs = 1L, timeoutMinutes = 0)
        runtime.onClientConnected("dev-1", "Desktop", "10.0.0.9:51234")
        runtime.setPhase(ServerPipeline.TRANSCRIBING)
        runtime.setCleaning(true)
        runtime.onTelemetry(120L, 300L, "hello world")

        runtime.onStarting()

        val snapshot = runtime.snapshot()
        assertNull(snapshot.error)
        assertTrue(snapshot.clients.isEmpty())
        assertEquals(ServerPipeline.IDLE, snapshot.phase)
        assertEquals(false, snapshot.cleaning)
        assertEquals(0, snapshot.committedWords)

        runtime.onStopped()
        assertEquals(ServerLifecycle.STOPPED, runtime.snapshot().lifecycle)
        assertNull(runtime.snapshot().endpoint)
    }

    @Test
    fun `a reconnecting device replaces its previous entry rather than duplicating`() {
        val runtime = ServerRuntime()
        runtime.onClientConnected("dev-1", "Desktop", "10.0.0.9:1")
        runtime.onClientConnected("dev-2", "Workstation", "10.0.0.10:2")
        runtime.onClientConnected("dev-1", "Desktop", "10.0.0.9:9")

        val clients = runtime.snapshot().clients
        assertEquals(2, clients.size)
        assertEquals("10.0.0.9:9", clients.first { it.deviceId == "dev-1" }.address)

        runtime.onClientDisconnected("dev-1")
        assertEquals(listOf("dev-2"), runtime.snapshot().clients.map { it.deviceId })
    }

    @Test
    fun `opening a capture clears the previous utterance's telemetry`() {
        val runtime = ServerRuntime()
        runtime.setPhase(ServerPipeline.TRANSCRIBING)
        runtime.setCleaning(true)
        runtime.onTelemetry(999L, 999L, "previous utterance words here")

        runtime.onCaptureStarted()

        // The telemetry resets; the phase is the host's to set (it publishes WARMING separately).
        val snapshot = runtime.snapshot()
        assertNull(snapshot.lastAsrMs)
        assertNull(snapshot.lastCleanupMs)
        assertEquals(0, snapshot.committedWords)
    }

    @Test
    fun `a cleanup pass lights Cleaning alongside the ASR phase`() {
        val runtime = ServerRuntime()
        runtime.setPhase(ServerPipeline.TRANSCRIBING)
        runtime.setCleaning(true)

        val stages = runtime.snapshot().stages
        assertTrue(ServerPipeline.TRANSCRIBING in stages)
        assertTrue(ServerPipeline.CLEANING in stages)
        assertFalse(ServerPipeline.IDLE in stages)
    }

    @Test
    fun `Idle lights only when nothing else is active`() {
        val runtime = ServerRuntime()
        assertEquals(setOf(ServerPipeline.IDLE), runtime.snapshot().stages)

        runtime.setCleaning(true)
        assertEquals(setOf(ServerPipeline.CLEANING), runtime.snapshot().stages)

        runtime.setCleaning(false)
        runtime.setPhase(ServerPipeline.LISTENING)
        assertEquals(setOf(ServerPipeline.LISTENING), runtime.snapshot().stages)
    }

    @Test
    fun `telemetry records the unit's latencies and the snapshot's word count`() {
        val runtime = ServerRuntime()
        runtime.onTelemetry(120L, 300L, "  one   two three ")

        val snapshot = runtime.snapshot()
        assertEquals(120L, snapshot.lastAsrMs)
        assertEquals(300L, snapshot.lastCleanupMs)
        assertEquals(3, snapshot.committedWords)
    }

    @Test
    fun `the heartbeat timestamp is kept verbatim`() {
        val runtime = ServerRuntime()
        runtime.onHeartbeat(1_234_567L)
        assertEquals(1_234_567L, runtime.snapshot().lastHeartbeatRealtimeMs)
    }
}
