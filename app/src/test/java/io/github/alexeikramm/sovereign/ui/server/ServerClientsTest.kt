package io.github.alexeikramm.sovereign.ui.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import io.github.alexeikramm.sovereign.server.ServerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClientsTest {

    private fun paired(id: String, name: String, enabled: Boolean = true) =
        PairedDevice(deviceId = id, name = name, token = "t-$id", enabled = enabled)

    private fun connected(id: String, name: String, address: String? = null) =
        ServerClient(deviceId = id, name = name, address = address)

    @Test
    fun joinsLiveClientsAndKeepsRegistryOrder() {
        val rows = ServerClients.rows(
            paired = listOf(paired("a", "Foxtop"), paired("b", "Laptop")),
            connected = listOf(connected("b", "Laptop")),
        )
        assertEquals(listOf("a", "b"), rows.map { it.deviceId })
        assertFalse(rows[0].connected)
        assertTrue(rows[1].connected)
        assertTrue(rows.all { it.paired })
    }

    @Test
    fun statusIsConnectionThenEnableSwitch() {
        val connectedRow = ServerClients.rows(
            paired = listOf(paired("a", "Foxtop")),
            connected = listOf(connected("a", "Foxtop")),
        ).single()
        assertEquals("Connected", ServerClients.statusLabel(connectedRow))

        val offlineRow = ServerClients.rows(
            paired = listOf(paired("a", "Foxtop")),
            connected = emptyList(),
        ).single()
        assertEquals("Offline", ServerClients.statusLabel(offlineRow))
    }

    @Test
    fun disabledClientReadsDisabled() {
        val row = ServerClients.rows(
            paired = listOf(paired("a", "Foxtop", enabled = false)),
            connected = emptyList(),
        ).single()
        assertFalse(row.enabled)
        assertEquals("Disabled", ServerClients.statusLabel(row))
    }

    @Test
    fun connectedClientWithoutARegistryEntryIsShownReadOnly() {
        val rows = ServerClients.rows(
            paired = emptyList(),
            connected = listOf(connected("z", "Ghost")),
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertFalse(row.paired)
        assertTrue(row.connected)
        assertEquals("Connected", ServerClients.statusLabel(row))
    }

    @Test
    fun emptyInputsProduceNoRows() {
        assertTrue(ServerClients.rows(emptyList(), emptyList()).isEmpty())
    }
}
