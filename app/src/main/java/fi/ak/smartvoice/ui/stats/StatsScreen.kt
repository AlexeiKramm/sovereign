package fi.ak.smartvoice.ui.stats

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.ak.smartvoice.data.UsageStatsData
import fi.ak.smartvoice.data.UsageStatsStore
import fi.ak.smartvoice.data.UtteranceRecord
import fi.ak.smartvoice.theme.BadgeBlue
import fi.ak.smartvoice.theme.BadgeCyan
import fi.ak.smartvoice.theme.BadgeGreen
import fi.ak.smartvoice.theme.BadgeOrange
import fi.ak.smartvoice.theme.BadgeSlate
import fi.ak.smartvoice.theme.BadgeViolet
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember { UsageStatsStore(context) }
    var stats by remember { mutableStateOf(UsageStatsData()) }
    var powerState by remember { mutableStateOf(readDevicePowerState(context)) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun refresh() {
        stats = store.load()
        powerState = readDevicePowerState(context)
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    if (onNavigateUp != null) {
        BackHandler { onNavigateUp() }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = "Reset Statistics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            shape = RoundedCornerShape(20.dp),
            text = {
                Text(
                    text = "Are you sure you want to reset all tracked dictation and audio metrics? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        refresh()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val content = @Composable { paddingModifier: Modifier ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .then(paddingModifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onNavigateUp == null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Usage Statistics",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Summary of your dictation time and words saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            IconButton(onClick = { refresh() }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (stats.totalUtterances > 0) {
                                IconButton(onClick = { showResetDialog = true }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Reset Stats",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                // High-level overview cards (2x2 grid style)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatMetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Audio Recorded",
                            value = formatDuration(stats.totalAudioSeconds),
                            subtitle = "${stats.totalUtterances} utterances",
                            accentColor = MaterialTheme.colorScheme.primary,
                        )
                        StatMetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Time Saved Typing",
                            value = "%.1f min".format(stats.typingMinutesSaved),
                            subtitle = "based on 40 WPM typing speed",
                            accentColor = BadgeGreen,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatMetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Words Spoken",
                            value = "${stats.totalRawWords}",
                            subtitle = "total spoken",
                            accentColor = BadgeCyan,
                        )
                        StatMetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Words Cleaned",
                            value = "${stats.totalCleanedWords}",
                            subtitle = "via AI rewriting",
                            accentColor = BadgeViolet,
                        )
                    }
                }
            }

            // Power Diagnostics Card
            item {
                PowerDiagnosticsCard(powerState = powerState)
            }

            item {
                SettingsGroupCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Default.AutoAwesome,
                                badgeColor = BadgeViolet,
                                contentDescription = "AI Impact",
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "AI Polish Impact",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val ratioText = if (stats.totalRawWords > 0) {
                                    val pct = ((stats.totalWordsCleaned.toDouble() / stats.totalRawWords) * 100).toInt()
                                    "$pct% of spoken words improved (${stats.totalWordsRemoved} fillers removed, ${stats.totalWordsChanged} grammar fixes, ${stats.totalWordsAdded} words added)."
                                } else {
                                    "Start dictating to see cleanup metrics."
                                }
                                Text(
                                    text = ratioText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Recent Utterances Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    SettingsIconBadge(
                        icon = Icons.Default.History,
                        badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentDescription = "Recent Dictations",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Recent Dictations",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            item {
                if (stats.recentRecords.isEmpty()) {
                    Text(
                        text = "No recorded dictations yet. Tap the microphone on your keyboard or test screen to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                } else {
                    SettingsGroupCard {
                        stats.recentRecords.forEachIndexed { index, record ->
                            UtteranceRecordRow(record = record)
                            if (index < stats.recentRecords.lastIndex) {
                                SettingsDivider(startIndent = 16.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (onNavigateUp != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Usage Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { refresh() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                            )
                        }
                        if (stats.totalUtterances > 0) {
                            IconButton(onClick = { showResetDialog = true }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Reset Stats",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        ) { innerPadding ->
            content(Modifier.padding(innerPadding))
        }
    } else {
        content(Modifier)
    }
}

@Composable
private fun StatMetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UtteranceRecordRow(record: UtteranceRecord) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(record.timestampMs) { dateFormat.format(Date(record.timestampMs)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${record.engine}  •  %.1f s audio".format(record.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detailsText = if (record.wordsCleaned > 0) {
                val breakdown = buildList {
                    if (record.wordsRemoved > 0) add("${record.wordsRemoved} removed")
                    if (record.wordsChanged > 0) add("${record.wordsChanged} fixed")
                    if (record.wordsAdded > 0) add("${record.wordsAdded} added")
                }.joinToString(", ")
                if (breakdown.isNotEmpty()) {
                    "${record.rawWords} words spoken  •  $breakdown"
                } else {
                    "${record.rawWords} words spoken  •  ${record.wordsCleaned} cleaned"
                }
            } else {
                "${record.rawWords} words spoken  •  No edits needed"
            }
            Text(
                text = detailsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun formatDuration(seconds: Double): String {
    val totalSec = seconds.toLong()
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

data class DevicePowerState(
    val batteryPct: Int = -1,
    val isCharging: Boolean = false,
    val chargeType: String = "",
    val batteryTempC: Float = 0f,
    val thermalStatus: String = "Normal",
    val thermalHeadroom: Float? = null,
)

fun readDevicePowerState(context: Context): DevicePowerState {
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val chargeType = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC Charging"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB Charging"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Charging"
        else -> if (isCharging) "Charging" else "Discharging"
    }

    val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    val batteryTempC = rawTemp / 10f

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
        when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool / Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttle"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttle"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Heat"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency Heat"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Normal"
        }
    } else {
        "Normal"
    }

    val rawHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null) {
        runCatching { powerManager.getThermalHeadroom(30) }.getOrNull()
    } else {
        null
    }
    val thermalHeadroom = if (rawHeadroom != null && !rawHeadroom.isNaN()) rawHeadroom else null

    return DevicePowerState(
        batteryPct = batteryPct,
        isCharging = isCharging,
        chargeType = chargeType,
        batteryTempC = batteryTempC,
        thermalStatus = thermalStatus,
        thermalHeadroom = thermalHeadroom,
    )
}

@Composable
fun PowerDiagnosticsCard(powerState: DevicePowerState, modifier: Modifier = Modifier) {
    SettingsGroupCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBadge(
                    icon = Icons.Default.BatteryChargingFull,
                    badgeColor = BadgeGreen,
                    contentDescription = "Battery & Power",
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Device Battery & Temperature",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Real-time battery and hardware status during dictation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery Level",
                    value = if (powerState.batteryPct >= 0) "${powerState.batteryPct}%" else "--",
                    subtitle = powerState.chargeType,
                    accentColor = BadgeGreen,
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery Temp",
                    value = if (powerState.batteryTempC > 0) "%.1f°C".format(powerState.batteryTempC) else "--",
                    subtitle = if (powerState.batteryTempC < 37f) "Cool & optimal" else "Warm",
                    accentColor = BadgeGreen,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Thermal Status",
                    value = powerState.thermalStatus,
                    subtitle = "System state",
                    accentColor = BadgeOrange,
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Thermal Headroom",
                    value = if (powerState.thermalHeadroom != null) {
                        "%.0f%%".format(maxOf(0f, 1f - powerState.thermalHeadroom) * 100f)
                    } else {
                        "Nominal"
                    },
                    subtitle = "Thermal safety margin",
                    accentColor = BadgeOrange,
                )
            }
        }
    }
}
