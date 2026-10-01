package fi.ak.smartvoice.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.engine.EnginePreference
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.theme.ThemePaletteStyle
import fi.ak.smartvoice.theme.ThemePresets
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ServerStatus
import fi.ak.smartvoice.ui.backup.BackupScreen
import fi.ak.smartvoice.ui.benchmark.BenchmarkScreen
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge
import fi.ak.smartvoice.ui.components.SettingsItemRow
import fi.ak.smartvoice.ui.components.SettingsSliderRow
import fi.ak.smartvoice.ui.components.SettingsStepperRow
import fi.ak.smartvoice.ui.components.SettingsToggleRow
import fi.ak.smartvoice.ui.components.SubScreenHost
import fi.ak.smartvoice.ui.history.HistoryScreen
import fi.ak.smartvoice.ui.models.ModelsScreen
import fi.ak.smartvoice.ui.stats.StatsScreen
import fi.ak.smartvoice.ui.transcribe.TranscribeHubScreen
import java.util.Locale
import kotlin.math.roundToInt

enum class SettingsSubScreen {
    MAIN,
    HISTORY,
    STATS,
    BENCHMARK,
    MODELS,
    TRANSCRIBE,
    BACKUP,
    LEGACY,
}

/**
 * Settings screen refined to 26dp rounded cards, strict sentence case, and dynamic M3 tokens.
 */
@Composable
fun SettingsScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onOpenSetup: () -> Unit = {},
) {
    var currentSubScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }

    when (currentSubScreen) {
        SettingsSubScreen.HISTORY -> {
            HistoryScreen(
                viewModel = viewModel,
                modifier = modifier,
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
            )
            return
        }
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
        SettingsSubScreen.BACKUP -> {
            BackupScreen(
                viewModel = viewModel,
                modifier = modifier,
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
            )
            return
        }
        SettingsSubScreen.LEGACY -> {
            SubScreenHost(
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
                modifier = modifier,
            ) {
                LegacySettingsScreen(viewModel = viewModel)
            }
            return
        }
        SettingsSubScreen.MAIN -> {
            // Render main settings
        }
    }

    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeSeedColor by viewModel.themeSeedColor.collectAsStateWithLifecycle()
    val themePaletteStyle by viewModel.themePaletteStyle.collectAsStateWithLifecycle()
    val themeUseSystemDynamic by viewModel.themeUseSystemDynamic.collectAsStateWithLifecycle()

    val pauseField by viewModel.pauseField.collectAsStateWithLifecycle()
    val stopField by viewModel.stopField.collectAsStateWithLifecycle()

    val floatingPillEnabled by viewModel.floatingPillEnabled.collectAsStateWithLifecycle()
    val floatingPillOnlyWhenKeyboardOpen by viewModel.floatingPillOnlyWhenKeyboardOpen.collectAsStateWithLifecycle()
    val floatingPillAutoCommit by viewModel.floatingPillAutoCommit.collectAsStateWithLifecycle()
    val floatingPillEdgeAnchoring by viewModel.floatingPillEdgeAnchoring.collectAsStateWithLifecycle()
    val quickDictateOnHold by viewModel.quickDictateOnHold.collectAsStateWithLifecycle()
    val hapticFeedbackOnHold by viewModel.hapticFeedbackOnHold.collectAsStateWithLifecycle()
    val keepScreenOnWhileRecording by viewModel.keepScreenOnWhileRecording.collectAsStateWithLifecycle()

    val cleanupEnabled by viewModel.cleanupEnabled.collectAsStateWithLifecycle()
    val litertProcessor by viewModel.litertProcessor.collectAsStateWithLifecycle()
    val litertGpuSupported by viewModel.litertGpuSupported.collectAsStateWithLifecycle()
    val litertSpeculativeDecoding by viewModel.litertSpeculativeDecoding.collectAsStateWithLifecycle()
    val healingSentencesCount by viewModel.healingSentencesCount.collectAsStateWithLifecycle()
    val idleTimeoutMinutes by viewModel.modelIdleTimeoutMinutes.collectAsStateWithLifecycle()
    val unloadOnScreenOff by viewModel.unloadOnScreenOff.collectAsStateWithLifecycle()
    val unloadOnKeyboardClose by viewModel.unloadOnKeyboardClose.collectAsStateWithLifecycle()

    val historyEnabled by viewModel.historyEnabled.collectAsStateWithLifecycle()
    val dictationHistory by viewModel.dictationHistory.collectAsStateWithLifecycle()

    val enginePreference by viewModel.enginePreference.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()

    val pauseSeconds = SettingsControls.snap(
        pauseField.seconds ?: AppSettings.DEFAULT_PAUSE_SILENCE_S,
        SettingsControls.PAUSE_MIN_SECONDS,
        SettingsControls.PAUSE_MAX_SECONDS,
    )
    val stopSeconds = SettingsControls.snap(
        stopField.seconds ?: AppSettings.DEFAULT_STOP_AFTER_SILENCE_S,
        SettingsControls.STOP_MIN_SECONDS,
        SettingsControls.STOP_MAX_SECONDS,
    )

    val overlayGranted = remember(context) { Settings.canDrawOverlays(context) }
    val accessibilityGranted = remember(context) {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabledServices.contains(context.packageName)
    }

    var devLegacyExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // --- SCREEN HEADER ---
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Preferences, pill overlay & engine configuration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- PERMISSION WARNING ---
        if (!overlayGranted || !accessibilityGranted) {
            PermissionWarningCard(
                context = context,
                overlayGranted = overlayGranted,
                accessibilityGranted = accessibilityGranted,
            )
        }

        // --- 1. DICTATION FEEL ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "DICTATION FEEL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.GraphicEq,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "Dictation feel",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Hold-to-dictate, haptic feedback, and silence detection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingsToggleRow(
                        title = "Hold the pill to dictate",
                        subtitle = "Press and hold the pill to speak, then release to immediately insert text",
                        checked = quickDictateOnHold,
                        onCheckedChange = viewModel::setQuickDictateOnHold,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Haptic feedback on hold",
                        subtitle = "Vibrate lightly when hold-to-dictate starts and stops",
                        checked = hapticFeedbackOnHold,
                        onCheckedChange = viewModel::setHapticFeedbackOnHold,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Auto-insert on silence",
                        subtitle = "Automatically inserts cleaned text when you stop speaking",
                        checked = floatingPillAutoCommit,
                        onCheckedChange = viewModel::setFloatingPillAutoCommit,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsStepperRow(
                        title = "Pause sensitivity",
                        subtitle = "How long you can pause before speech splits into a new chunk",
                        valueLabel = SettingsControls.formatSeconds(pauseSeconds),
                        onMinusClick = {
                            viewModel.setPauseSilenceSeconds(
                                SettingsControls.snap(
                                    pauseSeconds - SettingsControls.TIMING_STEP_SECONDS,
                                    SettingsControls.PAUSE_MIN_SECONDS,
                                    SettingsControls.PAUSE_MAX_SECONDS,
                                )
                            )
                        },
                        onPlusClick = {
                            viewModel.setPauseSilenceSeconds(
                                SettingsControls.snap(
                                    pauseSeconds + SettingsControls.TIMING_STEP_SECONDS,
                                    SettingsControls.PAUSE_MIN_SECONDS,
                                    SettingsControls.PAUSE_MAX_SECONDS,
                                )
                            )
                        },
                        minusEnabled = pauseSeconds > SettingsControls.PAUSE_MIN_SECONDS,
                        plusEnabled = pauseSeconds < SettingsControls.PAUSE_MAX_SECONDS,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsStepperRow(
                        title = "Auto-stop silence",
                        subtitle = "How many seconds of silence before the microphone turns off",
                        valueLabel = SettingsControls.formatSeconds(stopSeconds),
                        onMinusClick = {
                            viewModel.setStopAfterSilenceSeconds(
                                SettingsControls.snap(
                                    stopSeconds - 1.0f,
                                    SettingsControls.STOP_MIN_SECONDS,
                                    SettingsControls.STOP_MAX_SECONDS,
                                    1.0f,
                                )
                            )
                        },
                        onPlusClick = {
                            viewModel.setStopAfterSilenceSeconds(
                                SettingsControls.snap(
                                    stopSeconds + 1.0f,
                                    SettingsControls.STOP_MIN_SECONDS,
                                    SettingsControls.STOP_MAX_SECONDS,
                                    1.0f,
                                )
                            )
                        },
                        minusEnabled = stopSeconds > SettingsControls.STOP_MIN_SECONDS,
                        plusEnabled = stopSeconds < SettingsControls.STOP_MAX_SECONDS,
                    )
                }
            }
        }

        // --- 2. FLOATING PILL ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "FLOATING PILL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.Mic,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "Floating pill",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Overlay visibility and screen edge behavior",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingsToggleRow(
                        title = "Show only when keyboard is open",
                        subtitle = "Keep the pill hidden until your keyboard appears",
                        checked = floatingPillOnlyWhenKeyboardOpen,
                        onCheckedChange = viewModel::setFloatingPillOnlyWhenKeyboardOpen,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Snap pill to screen edges",
                        subtitle = "Snaps the resting pill to the nearest screen edge so it stays out of your way",
                        checked = floatingPillEdgeAnchoring,
                        onCheckedChange = viewModel::setFloatingPillEdgeAnchoring,
                    )
                }
            }
        }

        // --- 3. LLM CLEANUP ENGINE ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "LLM CLEANUP ENGINE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.AutoFixHigh,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "LLM cleanup engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Hardware acceleration, speculative decoding, and context window",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingsToggleRow(
                        title = "Enable LLM cleanup pass",
                        subtitle = "Runs on-device LLM to polish text output",
                        checked = cleanupEnabled,
                        onCheckedChange = viewModel::setCleanupEnabled,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LLM execution backend",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Hardware acceleration for faster LLM cleanup",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.probeHardwareCompatibility() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "Re-test GPU",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            LiteRtProcessor.entries.forEachIndexed { index, proc ->
                                val isSupported = when (proc) {
                                    LiteRtProcessor.CPU -> true
                                    LiteRtProcessor.GPU -> litertGpuSupported
                                }
                                SegmentedButton(
                                    selected = litertProcessor == proc,
                                    onClick = { if (isSupported) viewModel.setLitertProcessor(proc) },
                                    enabled = isSupported,
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = LiteRtProcessor.entries.size,
                                    ),
                                    label = {
                                        Text(if (proc == LiteRtProcessor.GPU) "GPU (OpenCL)" else "CPU")
                                    },
                                )
                            }
                        }
                    }

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Speculative decoding (MTP)",
                        subtitle = "Predicts multiple tokens at once for faster cleanup pass",
                        checked = litertSpeculativeDecoding,
                        onCheckedChange = viewModel::setLitertSpeculativeDecoding,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsStepperRow(
                        title = "Healing context sentences",
                        subtitle = "How many previous sentences the LLM uses as context across pauses",
                        valueLabel = "$healingSentencesCount",
                        onMinusClick = {
                            if (healingSentencesCount > 1) {
                                viewModel.onHealingSentencesCountChange(healingSentencesCount - 1)
                            }
                        },
                        onPlusClick = {
                            if (healingSentencesCount < 10) {
                                viewModel.onHealingSentencesCountChange(healingSentencesCount + 1)
                            }
                        },
                        minusEnabled = healingSentencesCount > 1,
                        plusEnabled = healingSentencesCount < 10,
                    )
                }
            }
        }

        // --- 4. APPEARANCE & THEME ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "APPEARANCE & THEME",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AppearanceCard(
                themeMode = themeMode,
                onThemeModeChange = viewModel::setThemeMode,
                seedColor = themeSeedColor,
                onSeedColorChange = viewModel::setThemeSeedColor,
                paletteStyle = themePaletteStyle,
                onPaletteStyleChange = viewModel::setThemePaletteStyle,
                useSystemDynamic = themeUseSystemDynamic,
                onUseSystemDynamicChange = viewModel::setThemeUseSystemDynamic,
            )
        }

        // --- 5. BATTERY & MEMORY ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "BATTERY & MEMORY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.BatteryChargingFull,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "Battery & memory",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Unload models to preserve battery and free RAM",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingsToggleRow(
                        title = "Unload models on screen off",
                        subtitle = "Free RAM by unloading models when screen is turned off",
                        checked = unloadOnScreenOff,
                        onCheckedChange = viewModel::onUnloadOnScreenOffChange,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Unload idle timeout",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Unload models from RAM after inactivity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        IdleTimeoutDropdownSelector(
                            selectedMinutes = idleTimeoutMinutes,
                            onMinutesSelected = viewModel::onModelIdleTimeoutMinutesChange,
                        )
                    }

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Keep screen awake while dictating",
                        subtitle = "Prevent display from dimming or locking during dictation",
                        checked = keepScreenOnWhileRecording,
                        onCheckedChange = viewModel::onKeepScreenOnWhileRecordingChange,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Unload models on keyboard close",
                        subtitle = "Free RAM when software keyboard is dismissed",
                        checked = unloadOnKeyboardClose,
                        onCheckedChange = viewModel::onUnloadOnKeyboardCloseChange,
                    )
                }
            }
        }

        // --- 6. DATA & MAINTENANCE ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "DATA & MAINTENANCE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.Storage,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "Data & maintenance",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Dictation history, backups, and setup wizard",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsToggleRow(
                            title = "Save dictation history",
                            subtitle = "Keep recent transcripts stored securely on-device",
                            checked = historyEnabled,
                            onCheckedChange = viewModel::setHistoryEnabled,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { currentSubScreen = SettingsSubScreen.HISTORY },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "View dictation history (${dictationHistory.records.size} items)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            TextButton(
                                onClick = { viewModel.clearHistory() },
                                enabled = dictationHistory.records.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (dictationHistory.records.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }

                    SettingsDivider(startIndent = 0.dp)

                    SettingsItemRow(
                        title = "Backup & restore",
                        subtitle = "Export or restore your vocabulary, profiles, and settings",
                        badge = {
                            SettingsIconBadge(
                                icon = Icons.Default.Refresh,
                                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        trailing = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Open backup and restore",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { currentSubScreen = SettingsSubScreen.BACKUP },
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsItemRow(
                        title = "Run setup again",
                        subtitle = "Walk through permissions and initial setup wizard",
                        badge = {
                            SettingsIconBadge(
                                icon = Icons.Default.Refresh,
                                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        trailing = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Run setup wizard again",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = onOpenSetup,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    // Expandable Developer & legacy controls
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { devLegacyExpanded = !devLegacyExpanded }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Developer & legacy controls",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                imageVector = if (devLegacyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (devLegacyExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (devLegacyExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SettingsItemRow(
                                    title = "Usage & battery statistics",
                                    subtitle = "Audio hours & thermal state (legacy; scheduled for removal)",
                                    badge = {
                                        SettingsIconBadge(
                                            icon = Icons.Default.BarChart,
                                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    },
                                    trailing = {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = "Open usage statistics",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = { currentSubScreen = SettingsSubScreen.STATS },
                                )

                                SettingsDivider(startIndent = 0.dp)

                                SettingsToggleRow(
                                    title = "Floating dictation pill",
                                    subtitle = "Master overlay switch (legacy; scheduled for removal)",
                                    checked = floatingPillEnabled,
                                    onCheckedChange = viewModel::setFloatingPillEnabled,
                                )

                                SettingsDivider(startIndent = 0.dp)

                                SettingsItemRow(
                                    title = "Model benchmarking",
                                    subtitle = "Measure ASR RTF and LLM tokens/sec latency",
                                    badge = {
                                        SettingsIconBadge(
                                            icon = Icons.Default.Speed,
                                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    },
                                    trailing = {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = "Open benchmarks",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = { currentSubScreen = SettingsSubScreen.BENCHMARK },
                                )

                                SettingsDivider(startIndent = 0.dp)

                                SettingsItemRow(
                                    title = "Legacy & flick feel settings",
                                    subtitle = "Pill flick friction, snap attenuation, and spring physics",
                                    badge = {
                                        SettingsIconBadge(
                                            icon = Icons.Default.Tune,
                                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    },
                                    trailing = {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = "Open legacy settings",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = { currentSubScreen = SettingsSubScreen.LEGACY },
                                )

                                SettingsDivider(startIndent = 0.dp)

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Transcription tier",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        EnginePreference.entries.forEachIndexed { index, pref ->
                                            SegmentedButton(
                                                selected = enginePreference == pref,
                                                onClick = { viewModel.onEnginePreferenceChange(pref) },
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = index,
                                                    count = EnginePreference.entries.size,
                                                ),
                                                label = { Text(if (pref == EnginePreference.LOCAL) "Local (default)" else "Server (legacy)") },
                                            )
                                        }
                                    }

                                    if (enginePreference == EnginePreference.REMOTE) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = serverUrl,
                                            onValueChange = viewModel::onServerUrlChange,
                                            label = { Text("Server base URL") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(16.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                                            when (val status = serverStatus) {
                                                is ServerStatus.Ready -> Text(
                                                    "Connected (contract ${status.contractVersion})",
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
                                                shape = CircleShape,
                                            ) {
                                                Text("Test")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 7. ABOUT SOVEREIGN ---
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "ABOUT SOVEREIGN",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.Info,
                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = "Sovereign v${buildVersion(context)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "100% on-device smart dictation with LLM cleanup",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Text(
                        text = "Sovereign runs entirely locally on your phone hardware. Audio recordings and transcriptions never leave your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

// ----------------------------------------------------------------------------
// Appearance Card
// ----------------------------------------------------------------------------

@Composable
private fun AppearanceCard(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    seedColor: Int,
    onSeedColorChange: (Int) -> Unit,
    paletteStyle: String,
    onPaletteStyleChange: (String) -> Unit,
    useSystemDynamic: Boolean,
    onUseSystemDynamicChange: (Boolean) -> Unit,
) {
    var showCustomColorDialog by remember { mutableStateOf(false) }

    SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsIconBadge(
                    icon = Icons.Default.Palette,
                    badgeColor = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentDescription = "Theme",
                )
                Column {
                    Text(
                        text = "Appearance & theme",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Theme mode, seed color, and M3 palette style",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AppThemeMode.entries.size),
                        label = { Text(mode.label) },
                    )
                }
            }

            SettingsDivider(startIndent = 0.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "M3 seed accent",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Generates full dynamic tonal palette",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val standardPresets = listOf(
                        ThemePresets.SIGNATURE_ORANGE,
                        ThemePresets.PIXEL_BLUE,
                        ThemePresets.ANDROID_GREEN,
                    )
                    standardPresets.forEach { preset ->
                        val isSelected = !useSystemDynamic && (seedColor and 0x00FFFFFF) == (preset.argb and 0x00FFFFFF)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(preset.color)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = CircleShape,
                                )
                                .clickable {
                                    onUseSystemDynamicChange(false)
                                    onSeedColorChange(preset.argb)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = if (preset.color.luminance() > 0.5f) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    // 4th slot: Custom Color
                    val isCustomSelected = !useSystemDynamic && standardPresets.none {
                        (seedColor and 0x00FFFFFF) == (it.argb and 0x00FFFFFF)
                    }
                    val customColor = Color(seedColor)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCustomSelected) customColor else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .border(
                                width = if (isCustomSelected) 2.5.dp else 1.dp,
                                color = if (isCustomSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                shape = CircleShape,
                            )
                            .clickable {
                                showCustomColorDialog = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isCustomSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Custom seed selected",
                                tint = if (customColor.luminance() > 0.5f) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Custom color picker",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsDivider(startIndent = 0.dp)
                SettingsToggleRow(
                    title = "Material You wallpaper colors",
                    subtitle = "Derive colors from Android system wallpaper instead of seed",
                    checked = useSystemDynamic,
                    onCheckedChange = onUseSystemDynamicChange,
                )
            }

            SettingsDivider(startIndent = 0.dp)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "M3 palette style",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemePaletteStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = paletteStyle.equals(style.id, ignoreCase = true),
                            onClick = { onPaletteStyleChange(style.id) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemePaletteStyle.entries.size),
                            label = { Text(style.label) },
                        )
                    }
                }
                Text(
                    text = ThemePaletteStyle.fromId(paletteStyle).description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCustomColorDialog) {
        CustomColorDialog(
            initialColor = seedColor,
            onDismissRequest = { showCustomColorDialog = false },
            onColorSelected = { selectedColor ->
                onUseSystemDynamicChange(false)
                onSeedColorChange(selectedColor)
                showCustomColorDialog = false
            },
        )
    }
}

@Composable
private fun CustomColorDialog(
    initialColor: Int,
    onDismissRequest: () -> Unit,
    onColorSelected: (Int) -> Unit,
) {
    var hexInput by remember {
        mutableStateOf(String.format(Locale.US, "#%06X", initialColor and 0x00FFFFFF))
    }
    val parsedColor = remember(hexInput) {
        val clean = hexInput.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (clean.length == 6) {
            clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        } else if (clean.length == 8) {
            clean.toLongOrNull(16)?.toInt()
        } else {
            null
        }
    }

    val paletteChoices = remember {
        listOf(
            0xFFFF9248.toInt(), // Orange
            0xFF1A73E8.toInt(), // Blue
            0xFF34A853.toInt(), // Green
            0xFFFA5B52.toInt(), // Coral
            0xFFD0BCFF.toInt(), // Lilac
            0xFF26A69A.toInt(), // Mint Teal
            0xFF546E7A.toInt(), // Slate
            0xFFFFB300.toInt(), // Amber
            0xFF8E24AA.toInt(), // Purple
            0xFFE91E63.toInt(), // Pink
            0xFF00BCD4.toInt(), // Cyan
            0xFF3F51B5.toInt(), // Indigo
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Custom Theme Color",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Pick a curated tone or enter a hex code to generate your dynamic Material 3 palette.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Palette grid (2 rows of 6)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    paletteChoices.chunked(6).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowColors.forEach { argb ->
                                val color = Color(argb)
                                val isCurSelected = parsedColor != null && (parsedColor and 0x00FFFFFF) == (argb and 0x00FFFFFF)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isCurSelected) 2.5.dp else 1.dp,
                                            color = if (isCurSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            hexInput = String.format(Locale.US, "#%06X", argb and 0x00FFFFFF)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isCurSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Hex input with real-time preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(parsedColor?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    )

                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            if (input.length <= 9) {
                                hexInput = input
                            }
                        },
                        label = { Text("Hex code") },
                        placeholder = { Text("#RRGGBB") },
                        singleLine = true,
                        isError = parsedColor == null,
                        supportingText = {
                            if (parsedColor == null) {
                                Text("Enter a valid hex code (e.g. #7C4DFF)")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    parsedColor?.let { onColorSelected(it) }
                },
                enabled = parsedColor != null,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        },
    )
}

// ----------------------------------------------------------------------------
// Legacy settings screen (Physics & tuning knobs)
// ----------------------------------------------------------------------------

@Composable
private fun LegacySettingsScreen(
    viewModel: DictationViewModel,
) {
    val pillFlickFriction by viewModel.pillFlickFriction.collectAsStateWithLifecycle()
    val pillSnapAttenuation by viewModel.pillSnapAttenuation.collectAsStateWithLifecycle()
    val pillSnapStiffness by viewModel.pillSnapStiffness.collectAsStateWithLifecycle()
    val pillSnapDamping by viewModel.pillSnapDamping.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Legacy & flick feel",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Fine-tune gesture dynamics and physics constants",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Pill physics & gesture dynamics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                SettingsSliderRow(
                    title = "Flick friction",
                    subtitle = "How quickly the glide stops after a throw",
                    value = pillFlickFriction,
                    valueLabel = String.format(Locale.US, "%.0f", pillFlickFriction),
                    valueRange = AppSettings.MIN_PILL_FLICK_FRICTION..AppSettings.MAX_PILL_FLICK_FRICTION,
                    steps = 10,
                    onValueChange = viewModel::setPillFlickFriction,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsSliderRow(
                    title = "Snap attenuation",
                    subtitle = "Vertical momentum retention during edge snap",
                    value = pillSnapAttenuation,
                    valueLabel = String.format(Locale.US, "%.2f", pillSnapAttenuation),
                    valueRange = AppSettings.MIN_PILL_SNAP_ATTENUATION..AppSettings.MAX_PILL_SNAP_ATTENUATION,
                    steps = 10,
                    onValueChange = viewModel::setPillSnapAttenuation,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsSliderRow(
                    title = "Spring stiffness",
                    subtitle = "Snap acceleration speed to screen edge",
                    value = pillSnapStiffness,
                    valueLabel = String.format(Locale.US, "%.0f", pillSnapStiffness),
                    valueRange = AppSettings.MIN_PILL_SNAP_STIFFNESS..AppSettings.MAX_PILL_SNAP_STIFFNESS,
                    steps = 13,
                    onValueChange = viewModel::setPillSnapStiffness,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsSliderRow(
                    title = "Spring damping",
                    subtitle = "Oscillation damping ratio (1.0 = critical damping)",
                    value = pillSnapDamping,
                    valueLabel = String.format(Locale.US, "%.2f", pillSnapDamping),
                    valueRange = AppSettings.MIN_PILL_SNAP_DAMPING..AppSettings.MAX_PILL_SNAP_DAMPING,
                    steps = 9,
                    onValueChange = viewModel::setPillSnapDamping,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Helper Components
// ----------------------------------------------------------------------------

@Composable
private fun PermissionWarningCard(
    context: Context,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Permissions required",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "The pill needs 'Display over other apps' to float on screen and 'Accessibility' to type into text fields.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!overlayGranted) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            context.startActivity(intent)
                        },
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Grant overlay", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!accessibilityGranted) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Enable accessibility", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun buildVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    "1.12-dl3"
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
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                Icon(
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
