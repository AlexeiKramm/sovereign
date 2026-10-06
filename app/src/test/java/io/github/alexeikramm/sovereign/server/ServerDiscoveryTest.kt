package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** The mDNS TXT contract (PROTOCOL.md §2) and instance name — pure, so it can be pinned here. */
class ServerDiscoveryTest {

    private val deviceId = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

    @Test
    fun `txt record carries exactly the protocol keys`() {
        val attrs = ServerDiscovery.txtAttributes(
            deviceId = deviceId,
            name = "Phone",
            spkiSha256 = "abc123",
            paired = true,
        )

        assertEquals(
            setOf("role", "id", "name", "protover", "spki", "paired"),
            attrs.keys,
        )
        assertEquals("server", attrs["role"])
        assertEquals(deviceId, attrs["id"])
        assertEquals("Phone", attrs["name"])
        assertEquals(ServerProtocol.VERSION.toString(), attrs["protover"])
        assertEquals("abc123", attrs["spki"])
        assertEquals("1", attrs["paired"])
        assertEquals("0", ServerDiscovery.txtAttributes(deviceId, "n", "s", false)["paired"])
    }

    @Test
    fun `instance name is Sovereign plus the first eight of the id`() {
        assertEquals("Sovereign-1a2b3c4d", ServerDiscovery.instanceName(deviceId))
    }
}
