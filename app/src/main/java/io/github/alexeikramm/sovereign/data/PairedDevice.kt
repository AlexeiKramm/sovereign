package io.github.alexeikramm.sovereign.data

import kotlinx.serialization.Serializable

/**
 * One desktop the phone has approved (PROTOCOL.md §4).
 *
 * [token] is a bearer credential and lives **only** inside the Keystore-wrapped
 * [PairedDeviceStore] — never in `AppSettings`, never in `SettingsBackup` (D-107, REVIEW M4). The
 * token is what the desktop presents in `HELLO`; the phone verifies it and never needs it back.
 */
@Serializable
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val token: String,
    val enabled: Boolean = true,
    /** When the phone issued the token (`System.currentTimeMillis`), for display/ordering. */
    val pairedAtMs: Long = 0L,
)

/** The whole registry, as persisted (wrapped) by [PairedDeviceStore]. */
@Serializable
data class PairedDeviceRegistryData(
    val devices: List<PairedDevice> = emptyList(),
)
