package fi.ak.smartvoice.ui.stats

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.ak.smartvoice.data.UsageStatsData
import fi.ak.smartvoice.data.UsageStatsStore
import fi.ak.smartvoice.data.UtteranceRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Statistics") },
            text = { Text("Are you sure you want to reset all tracked dictation and audio metrics? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        refresh()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                    )
                    Text(
                        text = "Insights from your dictation & cleanup sessions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    if (stats.totalUtterances > 0) {
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Reset Stats", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            // High-level overview cards (2x2 grid style)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatMetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Audio Recorded",
                        value = formatDuration(stats.totalAudioSeconds),
                        subtitle = "${stats.totalUtterances} utterances",
                    )
                    StatMetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Time Saved Typing",
                        value = "%.1f min".format(stats.typingMinutesSaved),
                        subtitle = "at ~40 WPM typing speed",
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatMetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Words Spoken",
                        value = "${stats.totalRawWords}",
                        subtitle = "raw ASR output",
                    )
                    StatMetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Words Cleaned",
                        value = "${stats.totalWordsCleaned}",
                        subtitle = if (stats.totalWordsRemoved > 0 || stats.totalWordsChanged > 0) "${stats.totalWordsRemoved} removed • ${stats.totalWordsChanged} fixed" else if (stats.totalWordsCleaned > 0) "${stats.totalWordsCleaned} modified" else "no edits required",
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Voice Cleanup Impact",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val ratioText = if (stats.totalRawWords > 0) {
                        val pct = ((stats.totalWordsCleaned.toDouble() / stats.totalRawWords) * 100).toInt()
                        "$pct% of spoken words modified by cleanup (${stats.totalWordsRemoved} fillers removed, ${stats.totalWordsChanged} grammar/word fixes, ${stats.totalWordsAdded} words inserted)."
                    } else {
                        "Start dictating to see cleanup metrics."
                    }
                    Text(
                        text = ratioText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            PowerDiagnosticsCard(powerState = powerState)
        }

        item {
            Text(
                text = "Recent Dictations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (stats.recentRecords.isEmpty()) {
            item {
                Text(
                    text = "No recorded dictations yet. Tap the microphone on your keyboard or test screen to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(stats.recentRecords) { record ->
                UtteranceRecordItem(record = record)
            }
        }
    }
}

@Composable
private fun StatMetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
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
private fun UtteranceRecordItem(record: UtteranceRecord) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(record.timestampMs) { dateFormat.format(Date(record.timestampMs)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${record.engine}  •  %.1f s audio".format(record.durationSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val detailsText = if (record.wordsCleaned > 0) {
                    val breakdown = buildList {
                        if (record.wordsRemoved > 0) add("${record.wordsRemoved} removed")
                        if (record.wordsChanged > 0) add("${record.wordsChanged} fixed")
                        if (record.wordsAdded > 0) add("${record.wordsAdded} added")
                    }.joinToString(", ")
                    if (breakdown.isNotEmpty()) {
                        "${record.rawWords} words spoken  •  ${record.wordsCleaned} cleaned ($breakdown)"
                    } else {
                        "${record.rawWords} words spoken  •  ${record.wordsCleaned} cleaned"
                    }
                } else {
                    "${record.rawWords} words spoken  •  verbatim"
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

private fun formatWordDelta(raw: Int, clean: Int): String {
    val diff = clean - raw
    return when {
        diff > 0 -> "+$diff words (expanded/punctuated)"
        diff < 0 -> "${diff} words (fillers removed)"
        else -> "exact word parity"
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Live Device Power & Thermals",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Monitors hardware thermal headroom and battery temperature to ensure background coolness.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery Level",
                    value = if (powerState.batteryPct >= 0) "${powerState.batteryPct}%" else "--",
                    subtitle = powerState.chargeType,
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery Temp",
                    value = if (powerState.batteryTempC > 0) "%.1f°C".format(powerState.batteryTempC) else "--",
                    subtitle = if (powerState.batteryTempC < 37f) "Cool & optimal" else "Warm",
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Thermal Status",
                    value = powerState.thermalStatus,
                    subtitle = "SoC governor state",
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Thermal Headroom",
                    value = if (powerState.thermalHeadroom != null) {
                        "%.0f%%".format(maxOf(0f, 1f - powerState.thermalHeadroom) * 100f)
                    } else {
                        "Nominal"
                    },
                    subtitle = "hardware headroom",
                )
            }
        }
    }
}
