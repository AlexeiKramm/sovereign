package io.github.alexeikramm.sovereign.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.PairedDevice
import io.github.alexeikramm.sovereign.data.PairedDeviceStore
import io.github.alexeikramm.sovereign.server.AppPairingPrompter
import io.github.alexeikramm.sovereign.server.ServerClient
import io.github.alexeikramm.sovereign.server.ServerController
import io.github.alexeikramm.sovereign.server.ServerLifecycle
import io.github.alexeikramm.sovereign.server.ServerPipeline
import io.github.alexeikramm.sovereign.server.ServerRuntime
import io.github.alexeikramm.sovereign.server.ServerSnapshot
import io.github.alexeikramm.sovereign.ui.components.SettingsGroupCard
import io.github.alexeikramm.sovereign.ui.components.SettingsIconBadge
import io.github.alexeikramm.sovereign.ui.components.SettingsSectionHeader
import io.github.alexeikramm.sovereign.ui.components.TopAppBarWithScrollShadow
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The phone-side control surface for the desktop server (Chunk 2a): a start/stop toggle, the live
 * diagnostics dashboard, and the session-timeout selector. Chunk 2b adds the paired-desktop
 * registry (view, enable/disable, unpair) and hosts the mutual-accept [PairingApprovalDialog].
 *
 * All live values come from [ServerRuntime], which the foreground service publishes into; nothing
 * here invents state. Reached from the Home card or the server notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val snapshot by ServerRuntime.shared.state.collectAsStateWithLifecycle()
    val settings = remember { AppSettings(context) }
    var timeoutMinutes by remember { mutableIntStateOf(settings.serverTimeoutMinutes) }

    // The paired-desktop registry (Chunk 2b) is phone-local config: readable whether or not the
    // server is up, and edited here. A pairing completed elsewhere shows up once a desktop connects.
    val pairedStore = remember { PairedDeviceStore(context) }
    var pairedDevices by remember { mutableStateOf(pairedStore.load()) }
    val pairingRequest by AppPairingPrompter.pending.collectAsStateWithLifecycle()
    fun reloadPairedDevices() {
        pairedDevices = pairedStore.load()
    }
    LaunchedEffect(snapshot.clients) { reloadPairedDevices() }

    if (onNavigateUp != null) {
        BackHandler { onNavigateUp() }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBarWithScrollShadow(scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Desktop Server",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        if (onNavigateUp != null) {
                            IconButton(onClick = onNavigateUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ControlCard(
                    snapshot = snapshot,
                    onToggle = { enabled ->
                        if (enabled) {
                            ServerController.start(context, timeoutMinutes)
                        } else {
                            ServerController.stop(context)
                        }
                    },
                )
            }

            item {
                TimeoutCard(
                    selectedMinutes = timeoutMinutes,
                    onSelect = {
                        timeoutMinutes = it
                        settings.serverTimeoutMinutes = it
                    },
                )
            }

            item {
                PairedDevicesCard(
                    devices = pairedDevices,
                    onToggle = { device, enabled ->
                        pairedStore.setEnabled(device.deviceId, enabled)
                        reloadPairedDevices()
                    },
                    onUnpair = { device ->
                        pairedStore.remove(device.deviceId)
                        reloadPairedDevices()
                    },
                )
            }

            item {
                ConnectedDevicesCard(clients = snapshot.clients)
            }

            item {
                DiagnosticsCard(snapshot = snapshot)
            }
        }
    }

    pairingRequest?.let { request ->
        PairingApprovalDialog(request = request) { approved ->
            AppPairingPrompter.resolve(request.id, approved)
            reloadPairedDevices()
        }
    }
}

// --- Control ---------------------------------------------------------------------------------

@Composable
private fun ControlCard(snapshot: ServerSnapshot, onToggle: (Boolean) -> Unit) {
    val running = snapshot.lifecycle != ServerLifecycle.STOPPED
    val busy = snapshot.lifecycle == ServerLifecycle.STARTING

    SettingsGroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIconBadge(
                    icon = Icons.Default.Computer,
                    badgeColor = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Desktop server",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusSubtitle(snapshot),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (snapshot.error.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Switch(
                    checked = running,
                    onCheckedChange = onToggle,
                    enabled = !busy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            if (running && !snapshot.endpoint.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                EndpointRow(snapshot.endpoint)
            }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: String) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CONNECT ADDRESS",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sovereign server address", endpoint))
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy address",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusSubtitle(snapshot: ServerSnapshot): String = when {
    !snapshot.error.isNullOrBlank() -> snapshot.error
    snapshot.lifecycle == ServerLifecycle.STARTING -> "Starting…"
    snapshot.lifecycle == ServerLifecycle.RUNNING && snapshot.clients.isNotEmpty() ->
        "Serving · ${snapshot.clients.size} desktop connected"
    snapshot.lifecycle == ServerLifecycle.RUNNING -> "Waiting for a desktop to connect"
    else -> "Off — your phone is not serving"
}

// --- Session timeout -------------------------------------------------------------------------

@Composable
private fun TimeoutCard(selectedMinutes: Int, onSelect: (Int) -> Unit) {
    Column {
        SettingsSectionHeader(title = "SESSION TIMEOUT")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Stop the server automatically after a set time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.SERVER_TIMEOUT_OPTIONS.forEach { minutes ->
                        OptionPill(
                            label = timeoutLabel(minutes),
                            selected = minutes == selectedMinutes,
                            onClick = { onSelect(minutes) },
                        )
                    }
                }
                Text(
                    text = "Takes effect the next time the server starts.",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun timeoutLabel(minutes: Int): String = when (minutes) {
    0 -> "Manual"
    60 -> "1 hour"
    120 -> "2 hours"
    else -> "$minutes min"
}

// --- Connected devices -----------------------------------------------------------------------

@Composable
private fun ConnectedDevicesCard(clients: List<ServerClient>) {
    Column {
        SettingsSectionHeader(title = "CONNECTED DESKTOPS")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (clients.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "None connected. Start the server, then connect the desktop client.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    clients.forEach { client ->
                        Column {
                            Text(
                                text = client.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!client.address.isNullOrBlank()) {
                                Text(
                                    text = client.address,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Paired devices (registry) ----------------------------------------------------------------

@Composable
private fun PairedDevicesCard(
    devices: List<PairedDevice>,
    onToggle: (PairedDevice, Boolean) -> Unit,
    onUnpair: (PairedDevice) -> Unit,
) {
    Column {
        SettingsSectionHeader(title = "PAIRED DESKTOPS")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (devices.isEmpty()) {
                    Text(
                        text = "No desktops paired. Start the server, then pair from the desktop app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = device.deviceId,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = device.enabled,
                                onCheckedChange = { onToggle(device, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                            IconButton(onClick = { onUnpair(device) }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Unpair ${device.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Diagnostics -----------------------------------------------------------------------------

@Composable
private fun DiagnosticsCard(snapshot: ServerSnapshot) {
    // The heartbeat age is a live number, so tick once a second while the screen is visible.
    var nowRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(snapshot.lastHeartbeatRealtimeMs) {
        while (true) {
            nowRealtimeMs = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    val heartbeat = snapshot.lastHeartbeatRealtimeMs?.let { formatAgo(nowRealtimeMs - it) } ?: "—"

    Column {
        SettingsSectionHeader(title = "PIPELINE")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = stageColor(snapshot.stages).copy(alpha = 0.18f),
                ) {
                    Text(
                        text = stageHeadline(snapshot.stages),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = stageColor(snapshot.stages),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    STAGES.forEach { (stage, label) ->
                        StagePill(label = label, active = stage in snapshot.stages)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TelemetryTile(
                        label = "LAST ASR",
                        value = snapshot.lastAsrMs?.let { "${it} ms" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryTile(
                        label = "LAST CLEANUP",
                        value = snapshot.lastCleanupMs?.let { "${it} ms" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TelemetryTile(
                        label = "WORDS",
                        value = snapshot.committedWords.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryTile(
                        label = "HEARTBEAT",
                        value = heartbeat,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun OptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) null else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun StagePill(label: String, active: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private val STAGES = listOf(
    ServerPipeline.IDLE to "Idle",
    ServerPipeline.WARMING to "Warming",
    ServerPipeline.LISTENING to "Listening",
    ServerPipeline.TRANSCRIBING to "Transcribing",
    ServerPipeline.CLEANING to "Cleaning",
)

/** Display order for the headline; excludes `ERROR`, which is handled on its own. */
private val STAGE_ORDER = listOf(
    ServerPipeline.IDLE,
    ServerPipeline.WARMING,
    ServerPipeline.LISTENING,
    ServerPipeline.TRANSCRIBING,
    ServerPipeline.CLEANING,
)

private fun stageLabel(pipeline: ServerPipeline): String = when (pipeline) {
    ServerPipeline.IDLE -> "Idle"
    ServerPipeline.WARMING -> "Warming up"
    ServerPipeline.LISTENING -> "Listening"
    ServerPipeline.TRANSCRIBING -> "Transcribing"
    ServerPipeline.CLEANING -> "Cleaning text"
    ServerPipeline.ERROR -> "Engine error"
}

/** Joins every lit stage, so concurrent ASR + cleanup reads "Transcribing + Cleaning text". */
private fun stageHeadline(stages: Set<ServerPipeline>): String {
    if (ServerPipeline.ERROR in stages) return stageLabel(ServerPipeline.ERROR)
    val lit = STAGE_ORDER.filter { it in stages }
    if (lit.isEmpty()) return stageLabel(ServerPipeline.IDLE)
    return lit.joinToString(" + ") { stageLabel(it) }
}

@Composable
private fun stageColor(stages: Set<ServerPipeline>): Color = when {
    ServerPipeline.ERROR in stages -> MaterialTheme.colorScheme.error
    stages == setOf(ServerPipeline.IDLE) -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

private fun formatAgo(millis: Long): String {
    if (millis < 0) return "just now"
    return when {
        millis < 1_000 -> "just now"
        millis < 60_000 -> "${millis / 1000} s ago"
        else -> String.format(Locale.US, "%.1f min ago", millis / 60_000.0)
    }
}
