package fi.ak.smartvoice.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.remember
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.device.DeviceHardware
import fi.ak.smartvoice.engine.EnginePreference
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ServerStatus

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Appearance, engine preferences, and timing configuration",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- 1. APPEARANCE & THEME ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "App Theme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Choose light, dark, or follow your system Android theme",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        }

        // --- 2. AI CLEANUP BACKEND ---
        // --- 2. LLM ACCELERATION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "LLM Acceleration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Google LiteRT-LM runtime inference processor on ${hwProfile.chipBrand}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
                                    SuggestionChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                "Optimal",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                    )
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
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Re-test Compatibility")
                }
            }
        }

        // --- 3. ENGINE PREFERENCE ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Speech Processing Tier",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

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
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = pref.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = when (pref) {
                                    EnginePreference.LOCAL -> "All speech is recognized on this phone with zero cloud network calls"
                                    EnginePreference.REMOTE -> "Transcribes audio through your private self-hosted server"
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Speech Detection & Silence Timings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Tune silence detection thresholds for punctuation and auto-stop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = pauseField.text,
                    onValueChange = viewModel::onPauseFieldChange,
                    label = { Text("Pause Threshold (seconds)") },
                    supportingText = { Text("Silence duration before sending audio to cleanup (default: 2.0s)") },
                    isError = pauseField.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = stopField.text,
                    onValueChange = viewModel::onStopFieldChange,
                    label = { Text("Auto-Stop Recording (seconds)") },
                    supportingText = { Text("Silence duration that finishes dictation (0 = manual stop only)") },
                    isError = stopField.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                timingNote?.let { note ->
                    if (note.isNotBlank()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // --- 5. BATTERY & MEMORY ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Battery & Memory Optimization",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

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
                        )
                        Text(
                            text = "Releases RAM and NPU/GPU hardware immediately to conserve battery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = unloadOnScreenOff,
                        onCheckedChange = viewModel::onUnloadOnScreenOffChange,
                    )
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Idle Memory Timeout",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Unload resident neural models after inactivity:",
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
                            )
                        }
                    }
                }
            }
        }

        // --- 6. SERVER URL ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Self-Hosted Remote Server",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Optional LAN/Tailscale server address for high-accuracy remote fallback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    label = { Text("Server Base URL") },
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
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is ServerStatus.Unreachable -> Text(
                            "Unreachable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(
                            "Status not checked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(onClick = { viewModel.checkServer() }) {
                        Text("Test Connection")
                    }
                }
            }
        }

        // --- ABOUT / BUILD INFO ---
        Text(
            text = "Smart Voice v${buildVersion(context)} • Local On-Device AI IME",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

private fun buildVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    "dev"
}
