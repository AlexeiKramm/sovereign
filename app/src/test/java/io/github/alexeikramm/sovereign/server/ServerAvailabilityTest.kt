package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.dictation.SessionArbiter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arbiter-holder → `SERVER_STATE` mapping (CHUNK-8). The wire frame is what a dormant desktop
 * uses to decide whether starting a dictation could even succeed, so every holder must map to the
 * right state and name.
 */
class ServerAvailabilityTest {

    private val identity = ServerIdentity("phone-id", "Phone")

    @Test
    fun `a free arbiter is IDLE with no holder`() {
        val payload = serverStatePayload(null, identity)
        assertEquals(ServerState.IDLE, payload.state)
        assertEquals("", payload.holderDeviceId)
        assertEquals("", payload.holderName)
    }

    @Test
    fun `a phone-local capture is RESERVED by the phone`() {
        for (owner in listOf(SessionArbiter.Owner.PILL, SessionArbiter.Owner.TRANSCRIBE)) {
            val payload = serverStatePayload(owner, identity)
            assertEquals("$owner should be RESERVED", ServerState.RESERVED, payload.state)
            assertEquals(identity.deviceId, payload.holderDeviceId)
            assertEquals(identity.serverName, payload.holderName)
        }
    }

    @Test
    fun `a desktop session is BUSY`() {
        val payload = serverStatePayload(SessionArbiter.Owner.SERVER, identity)
        assertEquals(ServerState.BUSY, payload.state)
        assertEquals("another desktop", payload.holderName)
    }
}
