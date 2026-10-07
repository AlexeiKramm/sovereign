package io.github.alexeikramm.sovereign.server

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bind gate that lets the service tell "listening" apart from "the port failed to bind"
 * (Java-WebSocket binds asynchronously, so `start()` returning is not the outcome — the device bug
 * this fixes showed the server claiming to be up after a `BindException`).
 */
class BindGateTest {

    @Test
    fun `await returns once bound`() = runTest {
        val gate = BindGate()
        gate.bound()
        withTimeout(1_000) { gate.await() }
    }

    @Test
    fun `await throws the startup failure`() = runTest {
        val gate = BindGate()
        gate.failed(IllegalStateException("Address already in use"))

        val thrown = runCatching { withTimeout(1_000) { gate.await() } }.exceptionOrNull()
        assertTrue("expected the failure to propagate, got $thrown", thrown is IllegalStateException)
        assertEquals("Address already in use", thrown?.message)
    }

    @Test
    fun `a failure after a successful bind is ignored`() = runTest {
        val gate = BindGate()
        gate.bound()
        // A later server-level fatal reports onError(null, …); it must not undo a real bind.
        gate.failed(IllegalStateException("late worker fatal"))
        withTimeout(1_000) { gate.await() }
    }

    @Test
    fun `the first outcome wins`() = runTest {
        val gate = BindGate()
        gate.failed(IllegalStateException("bind failed"))
        gate.bound()

        val thrown = runCatching { withTimeout(1_000) { gate.await() } }.exceptionOrNull()
        assertTrue("expected the failure to win, got $thrown", thrown is IllegalStateException)
        assertEquals("bind failed", thrown?.message)
    }
}
