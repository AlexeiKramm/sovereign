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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.GraphicEq
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ServerStatus
import fi.ak.smartvoice.ui.benchmark.BenchmarkScreen
import fi.ak.smartvoice.ui.models.ModelsScreen
import fi.ak.smartvoice.ui.transcribe.TranscribeHubScreen
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge
import fi.ak.smartvoice.ui.components.SettingsItemRow
import fi.ak.smartvoice.ui.components.SettingsSectionHeader
import fi.ak.smartvoice.ui.stats.StatsScreen

enum class SettingsSubScreen {
    MAIN,
    STATS,
    BENCHMARK,
    MODELS,
    TRANSCRIBE,
}

@Composable
fun SettingsScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onOpenSetup: () -> Unit = {},
) {
    var currentSubScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }

    when (currentSubScreen) {
        SettingsSubScreen.STATS -> {
            StatsScreen(
                modifier = modifier,
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
            )
            return
        }
        SettingsSubScreen.BENCHMARK -> {
            BenchmarkScreen(
                modifier = modifier,
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
            )
            return
        }
        SettingsSubScreen.MODELS -> {
            SubScreenHost(
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
                modifier = modifier,
            ) {
                ModelsScreen(viewModel = viewModel)
            }
            return
        }
        SettingsSubScreen.TRANSCRIBE -> {
            SubScreenHost(
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
                modifier = modifier,
            ) {
                TranscribeHubScreen(viewModel = viewModel)
            }
            return
        }
        SettingsSubScreen.MAIN -> {
            // Render main settings below
        }
    }

    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val enginePreference by viewModel.enginePreference.collectAsStateWithLifecycle()
    val pauseField by viewModel.pauseField.collectAsStateWithLifecycle()
    val stopField by viewModel.stopField.collectAsStateWithLifecycle()
    val healingSentencesCount by viewModel.healingSentencesCount.collectAsStateWithLifecycle()
    val timingNote by viewModel.timingNote.collectAsStateWithLifecycle()
    val unloadOnScreenOff by viewModel.unloadOnScreenOff.collectAsStateWithLifecycle()
    val unloadOnKeyboardClose by viewModel.unloadOnKeyboardClose.collectAsStateWithLifecycle()
    val idleTimeoutMinutes by viewModel.modelIdleTimeoutMinutes.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val litertProcessor by viewModel.litertProcessor.collectAsStateWithLifecycle()
    val litertGpuSupported by viewModel.litertGpuSupported.collectAsStateWithLifecycle()
    val litertCompatNote by viewModel.litertCompatibilityNote.collectAsStateWithLifecycle()
    val litertSpeculativeDecoding by viewModel.litertSpeculativeDecoding.collectAsStateWithLifecycle()
    val selectedLitertModel by viewModel.selectedLitertModel.collectAsStateWithLifecycle()
    val hwProfile = remember { DeviceHardware.getProfile(context) }

    val floatingPillEnabled by viewModel.floatingPillEnabled.collectAsStateWithLifecycle()
    val floatingPillOnlyWhenKeyboardOpen by viewModel.floatingPillOnlyWhenKeyboardOpen.collectAsStateWithLifecycle()
    val floatingPillAutoCommit by viewModel.floatingPillAutoCommit.collectAsStateWithLifecycle()
    val isOverlayPermissionGranted by viewModel.isOverlayPermissionGranted.collectAsStateWithLifecycle()
    val isAccessibilityPermissionGranted by viewModel.isAccessibilityPermissionGranted.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshFloatingPillPermissions()
        onPauseOrDispose { }
    }

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

        // --- 0. SETUP ---
        SettingsSectionHeader(title = "Setup")
        SettingsGroupCard {
            SettingsItemRow(
                title = "Run setup again",
                subtitle = "Walk through permissions and voice models again",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.Refresh,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentDescription = "Setup wizard",
                    )
                },
                trailing = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Run setup again",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenSetup,
            )
        }

        // --- 1. APPEARANCE & THEME ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Palette,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
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

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = AppThemeMode.entries.size),
                            label = { Text(mode.label) },
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
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Re-test Compatibility", style = MaterialTheme.typography.labelMedium)
                }

                SettingsDivider(startIndent = 0.dp)

                val modelSupportsSpeculative = selectedLitertModel.supportsSpeculativeDecoding
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Speculative Decoding (MTP)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    text = if (modelSupportsSpeculative) "Supported" else "Unsupported",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (modelSupportsSpeculative) BadgeGreen else MaterialTheme.colorScheme.outline,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            text = if (modelSupportsSpeculative) {
                                "Accelerates token generation by predicting multiple tokens in parallel with embedded drafter heads"
                            } else {
                                "The selected model does not include speculative drafting heads"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = litertSpeculativeDecoding && modelSupportsSpeculative,
                        enabled = modelSupportsSpeculative,
                        onCheckedChange = { viewModel.setLitertSpeculativeDecoding(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }

        // --- 3. TRANSCRIPTION MODE ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Mic,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentDescription = "Engine Mode",
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
                            text = "Choose between local on-device transcription and server tier",
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
                            .padding(vertical = 4.dp)
                            .selectable(
                                selected = isSelected,
                                onClick = { viewModel.onEnginePreferenceChange(pref) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.onEnginePreferenceChange(pref) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
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

        // --- 4. FLOATING DICTATION PILL (WISPR FLOW) ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.GraphicEq,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentDescription = "Floating Dictation Pill",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Floating Dictation Pill",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Wispr Flow-style floating mic over any active keyboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = floatingPillEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setFloatingPillEnabled(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                if (floatingPillEnabled) {
                    SettingsDivider(startIndent = 0.dp)

                    // Permission Warnings & Deep links if needed
                    if (!isOverlayPermissionGranted || !isAccessibilityPermissionGranted) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Permissions Required",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = "The floating pill needs 'Display over other apps' to float on screen and 'Accessibility Service' to detect keyboards and inject dictation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (!isOverlayPermissionGranted) {
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                                context.startActivity(intent)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Grant Overlay", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    if (!isAccessibilityPermissionGranted) {
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                context.startActivity(intent)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Enable Accessibility", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Setting 1: Only when keyboard is open (Default: true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setFloatingPillOnlyWhenKeyboardOpen(!floatingPillOnlyWhenKeyboardOpen)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Only when keyboard is open",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Pill automatically shows when another keyboard opens and hides when dismissed. If off, pill remains docked on screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = floatingPillOnlyWhenKeyboardOpen,
                            onCheckedChange = { viewModel.setFloatingPillOnlyWhenKeyboardOpen(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }

                    SettingsDivider(startIndent = 0.dp)

                    // Setting 2: Auto-commit on silence stop
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setFloatingPillAutoCommit(!floatingPillAutoCommit)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-commit on speech end",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Directly inject text into the active field when speech stops without waiting for checkmark tap",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = floatingPillAutoCommit,
                            onCheckedChange = { viewModel.setFloatingPillAutoCommit(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        }

        // --- 5. SPEECH TIMINGS ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Timer,
                        badgeColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
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
                    supportingText = { Text("Pause duration before finalizing a sentence and sending it to cleanup (default: 2.0s)") },
                    isError = pauseField.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = stopField.text,
                    onValueChange = viewModel::onStopFieldChange,
                    label = { Text("Auto-Stop Silence (seconds)") },
                    supportingText = { Text("Continuous silence before automatically turning off the microphone (default: 6.0s, 0 = off)") },
                    isError = stopField.invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                SettingsDivider(startIndent = 0.dp)

                OutlinedTextField(
                    value = healingSentencesCount.toString(),
                    onValueChange = { str ->
                        str.toIntOrNull()?.let { viewModel.onHealingSentencesCountChange(it) }
                    },
                    label = { Text("Seam Healing Context (sentences)") },
                    supportingText = { Text("Number of sentences from previous speech to rewrite across pause cuts (default: 2, 0 = append only)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    timingNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.resetTimingsToDefaults() },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text("Reset Defaults", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // --- 5. BATTERY & MEMORY OPTIMIZATION ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.BatteryChargingFull,
                        badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentDescription = "Battery & Memory",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Battery & Memory Optimization",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Manage RAM usage and background battery drain",
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
                            text = "Unload Models on Screen Off",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Frees ~1.5 GB RAM and eliminates background battery consumption while device is locked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = unloadOnScreenOff,
                        onCheckedChange = { viewModel.onUnloadOnScreenOffChange(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                SettingsDivider(startIndent = 0.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Unload Models on Keyboard Close",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Frees RAM immediately when the keyboard is dismissed (requires ~1.5s reload on next tap)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = unloadOnKeyboardClose,
                        onCheckedChange = { viewModel.onUnloadOnKeyboardCloseChange(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                SettingsDivider(startIndent = 0.dp)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Idle Memory Timeout",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Automatically unload models after a period of inactivity:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IdleTimeoutDropdownSelector(
                        selectedMinutes = idleTimeoutMinutes,
                        onMinutesSelected = { viewModel.onModelIdleTimeoutMinutesChange(it) },
                    )
                }
            }
        }

        // --- 6. SERVER URL ---
        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Cloud,
                        badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
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
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
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
                            color = MaterialTheme.colorScheme.primary,
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
                    ) {
                        Text("Test Connection", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // --- 7. DIAGNOSTICS & TELEMETRY TOOLS ---
        SettingsSectionHeader(title = "Diagnostics & Tools")
        SettingsGroupCard {
            SettingsItemRow(
                title = "Usage & Battery Statistics",
                subtitle = "Audio metrics, power draw, and transcription history",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.BarChart,
                        badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentDescription = "Usage Statistics",
                    )
                },
                trailing = {
                    androidx.compose.material3.Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open Usage Stats",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { currentSubScreen = SettingsSubScreen.STATS },
            )
            SettingsDivider(startIndent = 72.dp)
            SettingsItemRow(
                title = "Model Benchmarking",
                subtitle = "Measure ASR RTF and LLM tokens/sec latency",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.Speed,
                        badgeColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                        contentDescription = "Model Benchmarks",
                    )
                },
                trailing = {
                    androidx.compose.material3.Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open Benchmarks",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { currentSubScreen = SettingsSubScreen.BENCHMARK },
            )
            SettingsDivider(startIndent = 72.dp)
            SettingsItemRow(
                title = "Voice & AI models",
                subtitle = "Download, switch, and free up storage for on-device models",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.Cloud,
                        badgeColor = BadgeBlue,
                        iconTint = Color.White,
                        contentDescription = "Models",
                    )
                },
                trailing = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open model manager",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { currentSubScreen = SettingsSubScreen.MODELS },
            )
            SettingsDivider(startIndent = 72.dp)
            SettingsItemRow(
                title = "Transcribe a test",
                subtitle = "Record or pick an audio file to check the pipeline",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.GraphicEq,
                        badgeColor = BadgeGreen,
                        iconTint = Color.White,
                        contentDescription = "Transcribe test",
                    )
                },
                trailing = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open transcribe test",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { currentSubScreen = SettingsSubScreen.TRANSCRIBE },
            )
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

/**
 * Minimal host for a sub-screen that brings its own header: just a back
 * affordance, with the system back gesture wired to the same destination.
 */
@Composable
private fun SubScreenHost(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onNavigateUp)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        content()
    }
}

@Composable
private fun IdleTimeoutDropdownSelector(
    selectedMinutes: Int,
    onMinutesSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val timeoutOptions = listOf(
        0 to ("Never" to "Keep models in memory indefinitely"),
        1 to ("1 minute" to "Aggressive memory saving"),
        2 to ("2 minutes" to "Fast unload after use"),
        5 to ("5 minutes" to "Balanced memory & latency"),
        10 to ("10 minutes (Recommended)" to "Good balance for typical usage"),
        15 to ("15 minutes" to "Maximum responsiveness"),
    )

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedCard(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    cardWidthDp = with(density) { coordinates.size.width.toDp() }
                },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val currentOption = timeoutOptions.firstOrNull { it.first == selectedMinutes }
                    val currentTitle = currentOption?.second?.first ?: if (selectedMinutes == 0) "Never" else "$selectedMinutes minutes"
                    val currentDesc = currentOption?.second?.second ?: "Unload after $selectedMinutes minutes"
                    Text(
                        currentTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        currentDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select idle timeout",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(if (cardWidthDp > 0.dp) cardWidthDp else 280.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            timeoutOptions.forEach { (minutes, info) ->
                val (title, desc) = info
                val isSelected = minutes == selectedMinutes
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onMinutesSelected(minutes)
                        expanded = false
                    },
                )
            }
        }
    }
}
