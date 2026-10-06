package io.github.alexeikramm.sovereign.server

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The phone's LAN address, formatted for the dashboard's manual-entry fallback (PROTOCOL.md §2:
 * "manual host:port is first-class" because mDNS is blocked by AP isolation and misrouted by VPNs).
 *
 * The selection is a pure function so it is host-testable; only [localIpv4] touches the JVM's NIC
 * list.
 */
object ServerEndpoint {

    /**
     * Picks the address a desktop on the same LAN can reach: a site-local IPv4 first, then any other
     * non-loopback, non-link-local IPv4. Returns null when the phone has no usable address (e.g. the
     * interface is down), so the caller renders "unavailable" rather than something misleading.
     */
    fun pickIpv4(addresses: Collection<InetAddress>): String? {
        val usable = addresses.filterIsInstance<Inet4Address>().filterNot {
            it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress
        }
        return (usable.firstOrNull { it.isSiteLocalAddress } ?: usable.firstOrNull())?.hostAddress
    }

    /** The best LAN IPv4 of this device, or null. */
    fun localIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val addresses = interfaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
        pickIpv4(addresses)
    }.getOrNull()

    /** The `wss://` endpoint a desktop enters in the manual field. */
    fun url(ip: String, port: Int = ServerProtocol.DEFAULT_PORT): String =
        "wss://$ip:$port${ServerProtocol.PATH}"
}
