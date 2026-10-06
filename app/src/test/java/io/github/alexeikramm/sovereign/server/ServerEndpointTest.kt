package io.github.alexeikramm.sovereign.server

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The manual-entry address the dashboard shows (PROTOCOL.md §2: the desktop MUST offer a
 * "connect by address" field, and mDNS is blocked on the networks this targets). The selection is a
 * pure function so it can be pinned without a real NIC.
 */
class ServerEndpointTest {

    private fun addresses(vararg literals: String): List<InetAddress> =
        literals.map { InetAddress.getByName(it) }

    @Test
    fun `a site-local IPv4 is preferred over a routable one`() {
        val picked = ServerEndpoint.pickIpv4(addresses("8.8.8.8", "192.0.2.50"))
        assertEquals("192.0.2.50", picked)
    }

    @Test
    fun `loopback, link-local and any-local addresses are ignored`() {
        val picked = ServerEndpoint.pickIpv4(addresses("127.0.0.1", "169.254.1.1", "0.0.0.0", "10.0.0.5"))
        assertEquals("10.0.0.5", picked)
    }

    @Test
    fun `a non-site-local IPv4 is used when nothing better exists`() {
        val picked = ServerEndpoint.pickIpv4(addresses("127.0.0.1", "203.0.113.7"))
        assertEquals("203.0.113.7", picked)
    }

    @Test
    fun `an IPv6-only host yields no address`() {
        assertNull(ServerEndpoint.pickIpv4(addresses("::1", "fe80::1")))
    }

    @Test
    fun `the endpoint is the wss url the desktop enters`() {
        assertEquals(
            "wss://192.0.2.50:8765/sovereign",
            ServerEndpoint.url("192.0.2.50"),
        )
    }
}
