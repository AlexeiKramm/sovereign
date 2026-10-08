package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Constructing the transport must not throw. Java-WebSocket 1.6.0's `WebSocketServer(address, int,
 * drafts)` treats the `int` as a **decoder-thread count** and requires >= 1, so an earlier revision
 * that passed `0` there aborted server startup with `IllegalArgumentException`; this pins the
 * drafts-carrying two-argument form and the bounded `Draft_6455`.
 */
class SovereignWebSocketServerConstructionTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private class NoPairing : PairingAuthority {
        override val serverSpki = "spki"
        override suspend fun approve(deviceId: String, name: String) = PairingDecision.APPROVED
        override fun issue(deviceId: String, name: String) = PairedDevice(deviceId, name, "token")
        override fun authenticate(deviceId: String, token: String): PairedDevice? = null
        override fun lockoutRemainingMs() = 0L
        override fun attemptsRemaining() = 5
        override fun recordFailedProof() {}
    }

    private class NoCapture : CaptureHost {
        override fun begin(listener: CaptureListener) = StartOutcome.Failed("no capture in this test")
        override suspend fun finish() = CaptureResult("", "")
        override fun cancel() {}
    }

    @Test
    fun `the transport constructs with a bounded draft`() {
        val tls = ServerTls.loadOrCreate(temp.root)
        val server = SovereignWebSocketServer(
            boundPort = 0,
            tls = tls,
            identity = ServerIdentity("id", "name"),
            pairing = NoPairing(),
            capture = NoCapture(),
            dispatcher = Dispatchers.Unconfined,
        )
        try {
            assertNotNull(server)
        } finally {
            server.shutdown()
        }
    }
}
