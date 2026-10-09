package io.github.alexeikramm.sovereign.ui.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import io.github.alexeikramm.sovereign.server.ServerClient

/**
 * One row in Server settings' merged **Clients** list (server-active-screen CHUNK-3): a paired client
 * joined with its live connection state, so the paired and connected views are never two lists.
 *
 * [paired] is `false` only for the defensive case of a client that is connected but has no registry
 * entry — it should not happen (authentication requires a paired token), so such a row is shown
 * read-only rather than silently dropped.
 */
data class ClientRow(
    val deviceId: String,
    val name: String,
    val enabled: Boolean,
    val connected: Boolean,
    val paired: Boolean,
)

/**
 * The pure join behind the Clients list. Kept out of Compose so the ordering and edge cases are
 * unit-tested without a device.
 */
object ServerClients {

    /**
     * Joins the paired registry (the source of truth and the display order) with the live clients by
     * `deviceId`, then appends any connected-but-unpaired client defensively. A paired client with no
     * live entry is offline; a live client with a paired entry is connected.
     */
    fun rows(paired: List<PairedDevice>, connected: List<ServerClient>): List<ClientRow> {
        val liveIds = connected.mapTo(HashSet()) { it.deviceId }
        val pairedRows = paired.map { device ->
            ClientRow(
                deviceId = device.deviceId,
                name = device.name,
                enabled = device.enabled,
                connected = device.deviceId in liveIds,
                paired = true,
            )
        }
        val pairedIds = paired.mapTo(HashSet()) { it.deviceId }
        val orphanRows = connected
            .filterNot { it.deviceId in pairedIds }
            .map { ClientRow(it.deviceId, it.name, enabled = true, connected = true, paired = false) }
        return pairedRows + orphanRows
    }

    /**
     * The short status word for a row. **Disabled wins** (D-133): a client switched off reads
     * "Disabled" even in the brief window before its live socket has closed, so the row never claims
     * a revoked client is connected.
     */
    fun statusLabel(row: ClientRow): String = when {
        !row.enabled -> "Disabled"
        row.connected -> "Connected"
        else -> "Offline"
    }
}
