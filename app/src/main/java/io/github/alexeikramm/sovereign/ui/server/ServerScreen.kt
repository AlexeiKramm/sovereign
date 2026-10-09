package io.github.alexeikramm.sovereign.ui.server

import android.os.SystemClock
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import io.github.alexeikramm.sovereign.theme.ColorRoles
import io.github.alexeikramm.sovereign.ui.components.PresenceDot
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
@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val snapshot by ServerRuntime.shared.state.collectAsStateWithLifecycle()
    val settings = remember { AppSettings(context) }
    var timeoutMinutes by remember { mutableIntStateOf(settings.serverTimeoutMinutes) }
    var keepScreenAwake by remember { mutableStateOf(settings.serverKeepScreenAwake) }
    var dimWhenIdle by remember { mutableStateOf(settings.serverDimWhenIdle) }
    // The cog opens the settings panel; otherwise the hub shows the ON/OFF dashboard.
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // The paired-client registry (Chunk 2b) is phone-local config: readable whether or not the
    // server is up. A pairing completed elsewhere shows up once a client connects.
    val pairedStore = remember { PairedDeviceStore(context) }
    var pairedDevices by remember { mutableStateOf(pairedStore.load()) }
    val pairingRequest by AppPairingPrompter.pending.collectAsStateWithLifecycle()
    fun reloadPairedDevices() {
        pairedDevices = pairedStore.load()
    }
    LaunchedEffect(snapshot.clients) { reloadPairedDevices() }

    if (onNavigateUp != null) {
        BackHandler { if (showSettings) showSettings = false else onNavigateUp() }
    }

    if (showSettings) {
        ServerSettingsPanel(
            modifier = modifier,
            snapshot = snapshot,
            timeoutMinutes = timeoutMinutes,
            onSelectTimeout = {
                timeoutMinutes = it
                settings.serverTimeoutMinutes = it
            },
            pairedDevices = pairedDevices,
            onTogglePaired = { deviceId, enabled ->
                pairedStore.setEnabled(deviceId, enabled)
                // Switching a client off revokes a *live* session at once (D-133); enabling only
                // affects its next connect, so there is nothing to disconnect.
                if (!enabled) ServerController.disconnectClient(context, deviceId)
                reloadPairedDevices()
            },
            onUnpair = { deviceId ->
                pairedStore.remove(deviceId)
                reloadPairedDevices()
            },
            keepScreenAwake = keepScreenAwake,
            onKeepScreenAwakeChange = {
                keepScreenAwake = it
                settings.serverKeepScreenAwake = it
            },
            dimWhenIdle = dimWhenIdle,
            onDimWhenIdleChange = {
                dimWhenIdle = it
                settings.serverDimWhenIdle = it
            },
            onToggleServer = { enabled ->
                if (enabled) ServerController.start(context, timeoutMinutes) else ServerController.stop(context)
            },
            onBack = { showSettings = false },
        )
    } else {
        ServerHubBody(
            modifier = modifier,
            snapshot = snapshot,
            keepScreenAwake = keepScreenAwake,
            dimWhenIdle = dimWhenIdle,
            onOpenSettings = { showSettings = true },
            onStart = { ServerController.start(context, timeoutMinutes) },
            onStop = { ServerController.stop(context) },
        )
    }

    pairingRequest?.let { request ->
        PairingApprovalDialog(request = request) { approved ->
            AppPairingPrompter.resolve(request.id, approved)
            reloadPairedDevices()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsPanel(
    snapshot: ServerSnapshot,
    timeoutMinutes: Int,
    onSelectTimeout: (Int) -> Unit,
    pairedDevices: List<PairedDevice>,
    onTogglePaired: (String, Boolean) -> Unit,
    onUnpair: (String) -> Unit,
    keepScreenAwake: Boolean,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    dimWhenIdle: Boolean,
    onDimWhenIdleChange: (Boolean) -> Unit,
    onToggleServer: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                            text = "Server settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                ControlCard(snapshot = snapshot, onToggle = onToggleServer)
            }

            item {
                ClientsCard(
                    paired = pairedDevices,
                    connected = snapshot.clients,
                    onToggle = onTogglePaired,
                    onUnpair = onUnpair,
                )
            }

            item {
                TimeoutCard(selectedMinutes = timeoutMinutes, onSelect = onSelectTimeout)
            }

            item {
                OptionsCard(
                    keepScreenAwake = keepScreenAwake,
                    onKeepScreenAwakeChange = onKeepScreenAwakeChange,
                    dimWhenIdle = dimWhenIdle,
                    onDimWhenIdleChange = onDimWhenIdleChange,
                )
            }

            item {
                DiagnosticsCard(snapshot = snapshot)
            }
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
                        text = "Server",
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
        }
    }
}

private fun statusSubtitle(snapshot: ServerSnapshot): String = when {
    !snapshot.error.isNullOrBlank() -> snapshot.error
    snapshot.lifecycle == ServerLifecycle.STARTING -> "Starting…"
    snapshot.lifecycle == ServerLifecycle.RUNNING && snapshot.clients.size == 1 ->
        "Serving · ${snapshot.clients.first().name}"
    snapshot.lifecycle == ServerLifecycle.RUNNING && snapshot.clients.size > 1 ->
        "Serving · ${snapshot.clients.size} clients"
    snapshot.lifecycle == ServerLifecycle.RUNNING -> "Waiting for a client to connect"
    else -> "Off — your phone is not serving"
}

// --- Options ---------------------------------------------------------------------------------

@Composable
private fun OptionsCard(
    keepScreenAwake: Boolean,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    dimWhenIdle: Boolean,
    onDimWhenIdleChange: (Boolean) -> Unit,
) {
    Column {
        SettingsSectionHeader(title = "OPTIONS")
        SettingsGroupCard {
            Column {
                OptionRow(
                    title = "Keep screen awake",
                    subtitle = "Hold the display on while the server dashboard is open. The server " +
                        "keeps running with the screen off either way.",
                    checked = keepScreenAwake,
                    onCheckedChange = onKeepScreenAwakeChange,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                OptionRow(
                    title = "Dim when idle",
                    subtitle = "After 10 seconds untouched, darken the screen to save power. " +
                        "Tap anywhere to brighten.",
                    checked = dimWhenIdle,
                    onCheckedChange = onDimWhenIdleChange,
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
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

// --- Clients (registry joined with live connections) -------------------------------------------

@Composable
private fun ClientsCard(
    paired: List<PairedDevice>,
    connected: List<ServerClient>,
    onToggle: (String, Boolean) -> Unit,
    onUnpair: (String) -> Unit,
) {
    val rows = remember(paired, connected) { ServerClients.rows(paired, connected) }
    var pendingUnpair by remember { mutableStateOf<PairedDevice?>(null) }

    Column {
        SettingsSectionHeader(title = "CLIENTS")
        SettingsGroupCard {
            Column {
                if (rows.isEmpty()) {
                    Text(
                        text = "No clients yet. Start the server, then pair from the client app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        ClientRowItem(
                            row = row,
                            onToggle = { enabled -> onToggle(row.deviceId, enabled) },
                            onUnpair = {
                                pendingUnpair = paired.firstOrNull { it.deviceId == row.deviceId }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingUnpair?.let { device ->
        UnpairDialog(
            device = device,
            onDecision = { confirmed ->
                if (confirmed) onUnpair(device.deviceId)
                pendingUnpair = null
            },
        )
    }
}

@Composable
private fun ClientRowItem(
    row: ClientRow,
    onToggle: (Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val okColor = if (isDark) ColorRoles.OkDark else ColorRoles.OkLight
    // Disabled wins (D-133): a switched-off client is grey even while its socket is still closing.
    val dotColor = when {
        !row.enabled -> MaterialTheme.colorScheme.outline
        row.connected -> okColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val nameColor = if (row.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceDot(color = dotColor)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = nameColor,
            )
            Text(
                text = ServerClients.statusLabel(row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.paired) {
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
            RowMenu(onUnpair = onUnpair)
        }
    }
}

@Composable
private fun RowMenu(onUnpair: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Unpair") },
                onClick = {
                    open = false
                    onUnpair()
                },
            )
        }
    }
}

@Composable
private fun UnpairDialog(device: PairedDevice, onDecision: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text("Unpair ${device.name}?") },
        text = {
            Text("This client will need to pair again before it can dictate.")
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) {
                Text("Unpair", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text("Cancel") }
        },
    )
}

// --- Diagnostics -----------------------------------------------------------------------------

@Composable
private fun DiagnosticsCard(snapshot: ServerSnapshot) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // The heartbeat age is a live number, so tick once a second while the section is open.
    var nowRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(expanded, snapshot.lastHeartbeatRealtimeMs) {
        if (!expanded) return@LaunchedEffect
        while (true) {
            nowRealtimeMs = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    val heartbeat = snapshot.lastHeartbeatRealtimeMs?.let { formatAgo(nowRealtimeMs - it) } ?: "—"

    Column {
        CollapsibleSectionHeader(
            title = "DIAGNOSTICS",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
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

                    // The manual fallback address (the desktop's `-addr` flag). Debug-only: the
                    // tray pairs over mDNS/the subnet sweep and never asks for it (D-131/D-130).
                    if (!snapshot.endpoint.isNullOrBlank()) {
                        Text(
                            text = "CONNECT ADDRESS\n${snapshot.endpoint}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.primary,
        )
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
