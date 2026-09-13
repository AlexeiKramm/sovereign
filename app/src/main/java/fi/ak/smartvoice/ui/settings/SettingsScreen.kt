package fi.ak.smartvoice.ui.settings

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.device.DeviceHardware
import fi.ak.smartvoice.engine.EnginePreference
import fi.ak.smartvoice.theme.BadgeBlue
import fi.ak.smartvoice.theme.BadgeGreen
import fi.ak.smartvoice.theme.BadgeOrange
import fi.ak.smartvoice.theme.BadgeSlate
import fi.ak.smartvoice.theme.PrimaryBlue
import fi.ak.smartvoice.theme.SettingsDarkBorder
import fi.ak.smartvoice.theme.SettingsDarkSurface
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ServerStatus
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge

@Composable
fun SettingsScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val enginePreference by viewModel.enginePreference.collectAsStateWithLifecycle()
    val pauseField by viewModel.pauseField.collectAsStateWithLifecycle()
    val stableField by viewModel.stableField.collectAsStateWithLifecycle()
    val stopField by viewModel.stopField.collectAsStateWithLifecycle()
    val timingNote by viewModel.timingNote.collectAsStateWithLifecycle()
    val unloadOnScreenOff by viewModel.unloadOnScreenOff.collectAsStateWithLifecycle()
    val idleTimeoutMinutes by viewModel.modelIdleTimeoutMinutes.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val litertProcessor by viewModel.litertProcessor.collectAsStateWithLifecycle()
    val litertGpuSupported by viewModel.litertGpuSupported.collectAsStateWithLifecycle()
    val litertCompatNote by viewModel.litertCompatibilityNote.collectAsStateWithLifecycle()
    val hwProfile = remember { DeviceHardware.getProfile(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Appearance, performance, and voice settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- 1. APPEARANCE & THEME ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Palette,
                        badgeColor = BadgeBlue,
                        contentDescription = "Theme",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "App Theme",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Choose light, dark, or follow your system Android theme",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BadgeBlue.copy(alpha = 0.2f),
                                selectedLabelColor = BadgeBlue,
                            ),
                        )
                    }
                }
            }
        }

        // --- 2. HARDWARE ACCELERATION ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Memory,
                        badgeColor = BadgeGreen,
                        contentDescription = "Hardware Acceleration",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Hardware Acceleration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Processor used for on-device AI cleanup:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LiteRtProcessor.entries.forEach { proc ->
                    val isSelected = litertProcessor == proc
                    val isSupported = when (proc) {
                        LiteRtProcessor.CPU -> true
                        LiteRtProcessor.GPU -> litertGpuSupported
                    }
                    val containerAlpha = if (isSupported) 1f else 0.45f

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .selectable(
                                selected = isSelected,
                                enabled = isSupported,
                                onClick = { if (isSupported) viewModel.setLitertProcessor(proc) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            enabled = isSupported,
                            onClick = { if (isSupported) viewModel.setLitertProcessor(proc) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = BadgeGreen,
                                unselectedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = proc.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = containerAlpha),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (proc == LiteRtProcessor.GPU && isSupported) {
                                    Surface(
                                        color = SettingsDarkSurface,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, SettingsDarkBorder),
                                    ) {
                                        Text(
                                            text = "Optimal",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BadgeGreen,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = proc.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = containerAlpha),
                            )
                        }
                    }
                }

                if (litertCompatNote.isNotBlank()) {
                    Text(
                        text = litertCompatNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.probeHardwareCompatibility() },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SettingsDarkBorder),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Re-test Compatibility", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // --- 3. TRANSCRIPTION MODE ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Mic,
                        badgeColor = BadgeBlue,
                        contentDescription = "Transcription Mode",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Transcription Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Choose on-device private or server transcription",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                EnginePreference.entries.forEach { pref ->
                    val isSelected = enginePreference == pref
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = isSelected, onClick = { viewModel.onEnginePreferenceChange(pref) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.onEnginePreferenceChange(pref) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = PrimaryBlue,
                                unselectedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = pref.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when (pref) {
                                    EnginePreference.LOCAL -> "On-Device: Fully private and offline with zero network calls"
                                    EnginePreference.REMOTE -> "Self-Hosted: Transcribes via your private server"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // --- 4. SPEECH TIMINGS ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Timer,
                        badgeColor = BadgeOrange,
                        contentDescription = "Voice Pauses",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Voice Pauses & Silence",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Adjust how long pauses trigger sentence breaks or stop recording",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = pauseField.text,
                    onValueChange = viewModel::onPauseFieldChange,
                    label = { Text("Sentence Pause (seconds)") },
                    supportingText = { Text("Pause duration before formatting a sentence (default: 2.0s)") },
                    isError = pauseField.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsDarkSurface,
                        unfocusedContainerColor = SettingsDarkSurface,
                        focusedBorderColor = BadgeOrange,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = stopField.text,
                    onValueChange = viewModel::onStopFieldChange,
                    label = { Text("Auto-Stop Silence (seconds)") },
                    supportingText = { Text("Silence duration before ending recording (0 = manual stop only)") },
                    isError = stopField.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsDarkSurface,
                        unfocusedContainerColor = SettingsDarkSurface,
                        focusedBorderColor = BadgeOrange,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                timingNote?.let { note ->
                    if (note.isNotBlank()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = BadgeOrange,
                        )
                    }
                }
            }
        }

        // --- 5. BATTERY & MEMORY ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.BatteryChargingFull,
                        badgeColor = BadgeGreen,
                        contentDescription = "Battery & Memory",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Battery & Memory",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Manage background memory footprint and battery savings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Unload Models When Screen Turns Off",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Unloads models immediately when screen turns off to save battery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = unloadOnScreenOff,
                        onCheckedChange = viewModel::onUnloadOnScreenOffChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BadgeGreen,
                        ),
                    )
                }

                SettingsDivider(startIndent = 0.dp)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Inactivity Timeout",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Unload models from memory after:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppSettings.IDLE_TIMEOUT_OPTIONS.forEach { mins ->
                            FilterChip(
                                selected = idleTimeoutMinutes == mins,
                                onClick = { viewModel.onModelIdleTimeoutMinutesChange(mins) },
                                label = { Text(if (mins == 0) "Never" else "$mins min") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BadgeGreen.copy(alpha = 0.2f),
                                    selectedLabelColor = BadgeGreen,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // --- 6. SERVER URL ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Cloud,
                        badgeColor = BadgeSlate,
                        contentDescription = "Remote Server",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Remote Server",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Optional private server address for remote transcription",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    label = { Text("Server Base URL") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsDarkSurface,
                        unfocusedContainerColor = SettingsDarkSurface,
                        focusedBorderColor = BadgeSlate,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (serverStatus) {
                        is ServerStatus.Ready -> Text(
                            "Connected (contract ${(serverStatus as ServerStatus.Ready).contractVersion})",
                            style = MaterialTheme.typography.bodySmall,
                            color = BadgeGreen,
                            fontWeight = FontWeight.Medium,
                        )
                        is ServerStatus.Unreachable -> Text(
                            "Unreachable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                        else -> Text(
                            "Status not checked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(
                        onClick = { viewModel.checkServer() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BadgeSlate),
                    ) {
                        Text("Test Connection", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // --- ABOUT / BUILD INFO ---
        Text(
            text = "Smart Voice v${buildVersion(context)} • Local On-Device AI IME",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp),
        )
    }
}

private fun buildVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    "dev"
}

