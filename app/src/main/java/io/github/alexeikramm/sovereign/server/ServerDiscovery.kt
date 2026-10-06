package io.github.alexeikramm.sovereign.server

/**
 * The mDNS/DNS-SD announcement the phone publishes (PROTOCOL.md §2). The phone advertises; the
 * desktop browses. Only the pure parts live here — the `NsdManager` registration is Android and sits
 * in the service — so the TXT contract can be pinned by a host unit test.
 */
object ServerDiscovery {

    /** The registration type `NsdManager` expects; the wire form is `_sovereign._tcp.local`. */
    const val NSD_SERVICE_TYPE = "_sovereign._tcp"

    /** `Sovereign-<first 8 of deviceId>` (PROTOCOL.md §2). */
    fun instanceName(deviceId: String): String = "Sovereign-${deviceId.take(8)}"

    /**
     * The TXT record keys of PROTOCOL.md §2. `paired` is a hint only: whether any client is paired,
     * so a client that already holds a token can prefer a pinned connection over a fresh pairing.
     */
    fun txtAttributes(
        deviceId: String,
        name: String,
        spkiSha256: String,
        paired: Boolean,
    ): Map<String, String> = linkedMapOf(
        "role" to "server",
        "id" to deviceId,
        "name" to name,
        "protover" to ServerProtocol.VERSION.toString(),
        "spki" to spkiSha256,
        "paired" to if (paired) "1" else "0",
    )
}
