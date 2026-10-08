package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import kotlinx.coroutines.Dispatchers
import org.java_websocket.enums.HandshakeState
import org.java_websocket.enums.Role
import org.java_websocket.handshake.HandshakeImpl1Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the transport's construction *and* its WebSocket handshake contract — the two are not the
 * same, and an earlier revision proved it: the bounded `Draft_6455` was built with an **empty
 * protocol list**, and `Draft_6455.acceptHandshakeAsServer` requires a matched protocol AND a matched
 * extension, so the server answered every upgrade with `404 WebSocket Upgrade Failure`. Construction
 * still succeeded, so only a handshake-level assertion catches this.
 *
 * It also pins Java-WebSocket 1.6.0's `WebSocketServer(address, int, drafts)`, whose `int` is a
 * **decoder-thread count** requiring >= 1 — the reason the drafts-carrying two-argument form is used.
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

    @Test
    fun `the served draft matches a standard client handshake`() {
        // Mirror the server's own decode path: copyInstance() then setParseMode(SERVER) before accept.
        val draft = serverDrafts().single().copyInstance()
        draft.setParseMode(Role.SERVER)
        val handshake = HandshakeImpl1Client().apply {
            resourceDescriptor = ServerProtocol.PATH
            put("Upgrade", "websocket")
            put("Connection", "Upgrade")
            put("Sec-WebSocket-Version", "13")
            put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
        }
        assertEquals(HandshakeState.MATCHED, draft.acceptHandshakeAsServer(handshake))
    }
}
