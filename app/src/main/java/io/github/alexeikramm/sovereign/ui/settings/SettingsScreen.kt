package io.github.alexeikramm.sovereign.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexeikramm.sovereign.R
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.AppThemeMode
import io.github.alexeikramm.sovereign.data.LiteRtProcessor
import io.github.alexeikramm.sovereign.data.RawTextColorRole
import io.github.alexeikramm.sovereign.engine.EnginePreference
import io.github.alexeikramm.sovereign.theme.ColorHsvUtils
import io.github.alexeikramm.sovereign.theme.Spacing
import io.github.alexeikramm.sovereign.theme.ThemePaletteStyle
import io.github.alexeikramm.sovereign.theme.ThemePresets
import io.github.alexeikramm.sovereign.theme.rawTextColor
import io.github.alexeikramm.sovereign.ui.DictationViewModel
import io.github.alexeikramm.sovereign.ui.ServerStatus
import io.github.alexeikramm.sovereign.ui.backup.BackupScreen
import io.github.alexeikramm.sovereign.ui.benchmark.BenchmarkScreen
import io.github.alexeikramm.sovereign.ui.benchmark.StreamingBenchmarkScreen
import io.github.alexeikramm.sovereign.ui.cleanuplab.CleanupLabScreen
import io.github.alexeikramm.sovereign.ui.components.PermissionItem
import io.github.alexeikramm.sovereign.ui.components.SettingsDivider
import io.github.alexeikramm.sovereign.ui.components.SettingsGroupCard
import io.github.alexeikramm.sovereign.ui.components.SettingsIconBadge
import io.github.alexeikramm.sovereign.ui.components.SettingsItemRow
import io.github.alexeikramm.sovereign.ui.components.SettingsSliderRow
import io.github.alexeikramm.sovereign.ui.components.SettingsStepperRow
import io.github.alexeikramm.sovereign.ui.components.SettingsToggleRow
import io.github.alexeikramm.sovereign.ui.components.SubScreenHost
import io.github.alexeikramm.sovereign.ui.components.openSettings
import io.github.alexeikramm.sovereign.ui.history.HistoryScreen
import io.github.alexeikramm.sovereign.ui.models.ModelsScreen
import io.github.alexeikramm.sovereign.ui.stats.StatsScreen
import io.github.alexeikramm.sovereign.ui.transcribe.TranscribeHubScreen
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SettingsSubScreen {
    MAIN,
    HISTORY,
    STATS,
    BENCHMARK,
    STREAMING_BENCHMARK,
    CLEANUP_LAB,
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
        SettingsSubScreen.STREAMING_BENCHMARK -> {
            StreamingBenchmarkScreen(
                modifier = modifier,
                onNavigateUp = { currentSubScreen = SettingsSubScreen.MAIN },
            )
            return
        }
        SettingsSubScreen.CLEANUP_LAB -> {
            CleanupLabScreen(
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
    val themeDepthMode by viewModel.themeDepthMode.collectAsStateWithLifecycle()

    val pauseField by viewModel.pauseField.collectAsStateWithLifecycle()
    val stopField by viewModel.stopField.collectAsStateWithLifecycle()

    val floatingPillEnabled by viewModel.floatingPillEnabled.collectAsStateWithLifecycle()
    val floatingPillOnlyWhenKeyboardOpen by viewModel.floatingPillOnlyWhenKeyboardOpen.collectAsStateWithLifecycle()
    val floatingPillAutoCommit by viewModel.floatingPillAutoCommit.collectAsStateWithLifecycle()
    val floatingPillEdgeAnchoring by viewModel.floatingPillEdgeAnchoring.collectAsStateWithLifecycle()
    val quickDictateOnHold by viewModel.quickDictateOnHold.collectAsStateWithLifecycle()
    val hapticFeedbackOnHold by viewModel.hapticFeedbackOnHold.collectAsStateWithLifecycle()
    val keepScreenOnWhileRecording by viewModel.keepScreenOnWhileRecording.collectAsStateWithLifecycle()
    val streamingPreviewEnabled by viewModel.streamingPreviewEnabled.collectAsStateWithLifecycle()

    val cleanupEnabled by viewModel.cleanupEnabled.collectAsStateWithLifecycle()
    val litertProcessor by viewModel.litertProcessor.collectAsStateWithLifecycle()
    val litertGpuSupported by viewModel.litertGpuSupported.collectAsStateWithLifecycle()
    val litertSpeculativeDecoding by viewModel.litertSpeculativeDecoding.collectAsStateWithLifecycle()
    val healingSentencesCount by viewModel.healingSentencesCount.collectAsStateWithLifecycle()
    val maxTurns by viewModel.maxTurns.collectAsStateWithLifecycle()
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

    val overlayGranted by viewModel.isOverlayPermissionGranted.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.isAccessibilityPermissionGranted.collectAsStateWithLifecycle()

    val missingPillPermissions = listOfNotNull(
        if (!overlayGranted) PermissionItem.Overlay else null,
        if (!accessibilityGranted) PermissionItem.Accessibility else null,
    )

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
        if (missingPillPermissions.isNotEmpty()) {
            PermissionWarningCard(
                missingPermissions = missingPillPermissions,
                onGrant = { item -> item.openSettings(context) },
            )
        }

        // --- 1. APPEARANCE & THEME ---
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
                depthMode = themeDepthMode,
                onDepthModeChange = viewModel::setThemeDepthMode,
            )
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
                                text = "Overlay behaviour, hold-to-dictate, and silence detection",
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
                        subtitle = "Automatically inserts cleaned text when you stop speaking (expanded pill only)",
                        checked = floatingPillAutoCommit,
                        onCheckedChange = viewModel::setFloatingPillAutoCommit,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Show streaming text while speaking",
                        subtitle = "Displays live text as you speak (streaming models only; ignored by offline models)",
                        checked = streamingPreviewEnabled,
                        onCheckedChange = viewModel::setStreamingPreviewEnabled,
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

        // --- 3. BATTERY & MEMORY ---
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
                        title = "Unload models on keyboard close",
                        subtitle = "Free RAM when software keyboard is dismissed",
                        checked = unloadOnKeyboardClose,
                        onCheckedChange = viewModel::onUnloadOnKeyboardCloseChange,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Unload models on screen off",
                        subtitle = "Free RAM by unloading models when screen is turned off",
                        checked = unloadOnScreenOff,
                        onCheckedChange = viewModel::onUnloadOnScreenOffChange,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsToggleRow(
                        title = "Keep screen awake while dictating",
                        subtitle = "Prevent display from dimming or locking during dictation",
                        checked = keepScreenOnWhileRecording,
                        onCheckedChange = viewModel::onKeepScreenOnWhileRecordingChange,
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
                }
            }
        }

        // --- 4. LLM CLEANUP ENGINE ---
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
                                text = "Hardware acceleration and context window",
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

                    SettingsDivider(startIndent = 0.dp)

                    SettingsStepperRow(
                        title = "Conversation turn limit",
                        subtitle = "Rebuild the cleanup conversation every N segments; the model drifts " +
                            "once its context fills. Off keeps one conversation until the cache is full.",
                        valueLabel = if (maxTurns == 0) "off" else "$maxTurns",
                        onMinusClick = { if (maxTurns > 0) viewModel.onMaxTurnsChange(maxTurns - 1) },
                        onPlusClick = { if (maxTurns < 8) viewModel.onMaxTurnsChange(maxTurns + 1) },
                        minusEnabled = maxTurns > 0,
                        plusEnabled = maxTurns < 8,
                    )
                }
            }
        }

        // --- 5. DATA & MAINTENANCE ---
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

                    SettingsToggleRow(
                        title = "Save dictation history",
                        subtitle = "Keep recent transcripts stored securely on-device",
                        checked = historyEnabled,
                        onCheckedChange = viewModel::setHistoryEnabled,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsItemRow(
                        title = "Dictation history",
                        subtitle = if (dictationHistory.records.isEmpty()) {
                            "No transcripts stored"
                        } else {
                            "${dictationHistory.records.size} items stored on-device"
                        },
                        badge = {
                            SettingsIconBadge(
                                icon = Icons.Default.History,
                                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Open dictation history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { currentSubScreen = SettingsSubScreen.HISTORY },
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsItemRow(
                        title = "Backup & restore",
                        subtitle = "Export or restore your vocabulary, profiles, and settings",
                        badge = {
                            SettingsIconBadge(
                                icon = Icons.Default.Backup,
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
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    SettingsToggleRow(
                                        title = "Material You wallpaper colors",
                                        subtitle = "Derive colors from Android system wallpaper (legacy; scheduled for removal)",
                                        checked = themeUseSystemDynamic,
                                        onCheckedChange = viewModel::setThemeUseSystemDynamic,
                                    )

                                    SettingsDivider(startIndent = 0.dp)
                                }

                                SettingsToggleRow(
                                    title = "Hold the pill to dictate",
                                    subtitle = "Press and hold the pill to speak, then release to immediately insert text",
                                    checked = quickDictateOnHold,
                                    onCheckedChange = viewModel::setQuickDictateOnHold,
                                )

                                SettingsDivider(startIndent = 0.dp)

                                SettingsToggleRow(
                                    title = "Speculative decoding (MTP)",
                                    subtitle = "Predicts multiple tokens at once for faster cleanup pass",
                                    checked = litertSpeculativeDecoding,
                                    onCheckedChange = viewModel::setLitertSpeculativeDecoding,
                                )

                                SettingsDivider(startIndent = 0.dp)

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
                                    title = "Streaming benchmark",
                                    subtitle = "Real-time commit lag, dead air and cadence across the model matrix",
                                    badge = {
                                        SettingsIconBadge(
                                            icon = Icons.Default.Mic,
                                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    },
                                    trailing = {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = "Open streaming benchmark",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = { currentSubScreen = SettingsSubScreen.STREAMING_BENCHMARK },
                                )

                                SettingsDivider(startIndent = 0.dp)

                                SettingsItemRow(
                                    title = "Cleanup lab",
                                    subtitle = "Tune the LLM cleanup per model and profile; quality vs context position",
                                    badge = {
                                        SettingsIconBadge(
                                            icon = Icons.Default.Science,
                                            badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    },
                                    trailing = {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = "Open cleanup lab",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = { currentSubScreen = SettingsSubScreen.CLEANUP_LAB },
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

        // --- 6. ABOUT SOVEREIGN ---
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
                        SovereignLogoBadge()
                        Text(
                            text = "Sovereign v${buildVersion(context)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Text(
                        text = "Smart dictation, completely on-device. No cloud, no subscriptions, entirely yours.",
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
private fun SovereignLogoBadge(size: Dp = 40.dp) {
    Image(
        painter = painterResource(R.drawable.ic_sovereign_logo),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

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
    depthMode: Boolean,
    onDepthModeChange: (Boolean) -> Unit,
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

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
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

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(ThemePresets.ALL) { preset ->
                        val isSelected = !useSystemDynamic && (seedColor and 0x00FFFFFF) == (preset.argb and 0x00FFFFFF)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
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
                                    contentDescription = "${preset.name} selected",
                                    tint = if (preset.color.luminance() > 0.5f) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    item {
                        // Custom Color slot
                        val isCustomSelected = !useSystemDynamic && ThemePresets.ALL.none {
                            (seedColor and 0x00FFFFFF) == (it.argb and 0x00FFFFFF)
                        }
                        val customColor = Color(seedColor)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
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
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Custom color picker",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            SettingsDivider(startIndent = 0.dp)

            SettingsToggleRow(
                title = "Elevated card contrast",
                subtitle = "Perceptual depth separation on AMOLED displays",
                checked = depthMode,
                onCheckedChange = onDepthModeChange,
            )

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
    val initialHsv = remember(initialColor) {
        ColorHsvUtils.colorToHsv(Color(initialColor))
    }
    var hue by remember { mutableFloatStateOf(initialHsv.first) }
    var saturation by remember { mutableFloatStateOf(initialHsv.second) }
    var value by remember { mutableFloatStateOf(initialHsv.third) }

    var hexInput by remember {
        mutableStateOf(String.format(Locale.US, "#%06X", initialColor and 0x00FFFFFF))
    }

    val currentColor = remember(hue, saturation, value) {
        ColorHsvUtils.hsvToColor(hue, saturation, value)
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

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Custom Seed Color",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Touch and drag on the color wheel to select hue and saturation, adjust brightness, or enter a hex code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 1. Interactive Color Wheel
                ColorWheelPicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onColorChanged = { newHue, newSat ->
                        hue = newHue
                        saturation = newSat
                        val c = ColorHsvUtils.hsvToColor(newHue, newSat, value)
                        hexInput = String.format(Locale.US, "#%06X", c.toArgb() and 0x00FFFFFF)
                    },
                    modifier = Modifier.size(210.dp),
                )

                // 2. Brightness / Value Slider
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Brightness",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${(value * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = value,
                        onValueChange = { newVal ->
                            value = newVal
                            val c = ColorHsvUtils.hsvToColor(hue, saturation, newVal)
                            hexInput = String.format(Locale.US, "#%06X", c.toArgb() and 0x00FFFFFF)
                        },
                        valueRange = 0.15f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 3. Hex code input with real-time preview swatch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    )

                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            if (input.length <= 9) {
                                hexInput = input
                                val clean = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
                                val parsed = if (clean.length == 6) {
                                    clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                                } else if (clean.length == 8) {
                                    clean.toLongOrNull(16)?.toInt()
                                } else null
                                if (parsed != null) {
                                    val (newH, newS, newV) = ColorHsvUtils.colorToHsv(Color(parsed))
                                    hue = newH
                                    saturation = newS
                                    value = newV
                                }
                            }
                        },
                        label = { Text("Hex code") },
                        placeholder = { Text("#RRGGBB") },
                        singleLine = true,
                        isError = parsedColor == null,
                        supportingText = {
                            if (parsedColor == null) {
                                Text("Enter a valid hex code (e.g. #00B4D8)")
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
                    onColorSelected(currentColor.toArgb())
                },
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

@Composable
private fun ColorWheelPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onColorChanged: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sweepColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val radius = minOf(centerX, centerY)
                    val dx = offset.x - centerX
                    val dy = offset.y - centerY
                    val dist = sqrt(dx * dx + dy * dy)
                    val sat = (dist / radius).coerceIn(0f, 1f)
                    val angleRad = atan2(dy, dx)
                    val angleDeg = (Math.toDegrees(angleRad.toDouble()).toFloat() + 360f) % 360f
                    onColorChanged(angleDeg, sat)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val radius = minOf(centerX, centerY)
                    val dx = change.position.x - centerX
                    val dy = change.position.y - centerY
                    val dist = sqrt(dx * dx + dy * dy)
                    val sat = (dist / radius).coerceIn(0f, 1f)
                    val angleRad = atan2(dy, dx)
                    val angleDeg = (Math.toDegrees(angleRad.toDouble()).toFloat() + 360f) % 360f
                    onColorChanged(angleDeg, sat)
                }
            }
    ) {
        val centerOffset = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f

        // 1. Hue sweep gradient
        drawCircle(
            brush = Brush.sweepGradient(sweepColors, center = centerOffset),
            radius = radius,
            center = centerOffset,
        )

        // 2. Saturation radial gradient (white in center fading to transparent at edge)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = centerOffset,
                radius = radius,
            ),
            radius = radius,
            center = centerOffset,
        )

        // 3. Brightness overlay if value < 1f
        if (value < 1f) {
            drawCircle(
                color = Color.Black.copy(alpha = (1f - value).coerceIn(0f, 1f)),
                radius = radius,
                center = centerOffset,
            )
        }

        // 4. Subtle wheel outer rim
        drawCircle(
            color = Color.Black.copy(alpha = 0.25f),
            radius = radius,
            center = centerOffset,
            style = Stroke(width = 2.dp.toPx()),
        )

        // 5. Thumb / handle at current (hue, saturation)
        val angleRad = Math.toRadians(hue.toDouble())
        val handleDist = saturation * radius
        val handleX = centerOffset.x + (handleDist * cos(angleRad)).toFloat()
        val handleY = centerOffset.y + (handleDist * sin(angleRad)).toFloat()
        val handleCenter = Offset(handleX, handleY)

        val currentColor = ColorHsvUtils.hsvToColor(hue, saturation, value)

        // Outer dark drop shadow
        drawCircle(
            color = Color.Black.copy(alpha = 0.5f),
            radius = 14.dp.toPx(),
            center = handleCenter,
            style = Stroke(width = 2.dp.toPx()),
        )
        // White border ring
        drawCircle(
            color = Color.White,
            radius = 12.dp.toPx(),
            center = handleCenter,
            style = Stroke(width = 3.dp.toPx()),
        )
        // Current color fill inside thumb
        drawCircle(
            color = currentColor,
            radius = 9.dp.toPx(),
            center = handleCenter,
        )
    }
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
    val rawTextColorRole by viewModel.streamingPreviewColorRole.collectAsStateWithLifecycle()
    val rawTextOpacity by viewModel.streamingPreviewOpacity.collectAsStateWithLifecycle()

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

        // Streaming raw-text appearance. Lives here because it is a "make the provisional text
        // recede" tuning knob rather than a headline setting (raw-preview). Read live by the pill.
        SettingsGroupCard(shape = RoundedCornerShape(26.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Streaming text colour",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "The live text shown while you speak, before it is cleaned. Lower opacity and a " +
                        "fainter role make it recede behind the settled text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RawTextColorRoleSelector(
                    selected = rawTextColorRole,
                    opacity = rawTextOpacity,
                    onSelect = viewModel::setStreamingPreviewColorRole,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsSliderRow(
                    title = "Opacity",
                    subtitle = "How strongly the streaming text shows",
                    value = rawTextOpacity,
                    valueLabel = "${(rawTextOpacity * 100).roundToInt()}%",
                    valueRange = AppSettings.MIN_STREAMING_PREVIEW_OPACITY..AppSettings.MAX_STREAMING_PREVIEW_OPACITY,
                    steps = 8,
                    onValueChange = viewModel::setStreamingPreviewOpacity,
                )
            }
        }
    }
}

/**
 * The raw text colour role picker: a chip per [RawTextColorRole], each drawn in the colour it will
 * actually produce (the live scheme value at the current opacity), so the choice is what-you-see.
 */
@Composable
private fun RawTextColorRoleSelector(
    selected: RawTextColorRole,
    opacity: Float,
    onSelect: (RawTextColorRole) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Colour role",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RawTextColorRole.entries.forEach { role ->
                val roleColor = rawTextColor(role, opacity, MaterialTheme.colorScheme)
                FilterChip(
                    selected = role == selected,
                    onClick = { onSelect(role) },
                    label = { Text(role.label) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(roleColor),
                        )
                    },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------------
// Helper Components
// ----------------------------------------------------------------------------

@Composable
private fun PermissionWarningCard(
    missingPermissions: List<PermissionItem>,
    onGrant: (PermissionItem) -> Unit,
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
                missingPermissions.forEach { item ->
                    Button(
                        onClick = { onGrant(item) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = item.actionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
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
        0 to "Never",
        1 to "1 minute",
        2 to "2 minutes (Recommended)",
        5 to "5 minutes",
        10 to "10 minutes",
        15 to "15 minutes",
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
                    val currentTitle = timeoutOptions.firstOrNull { it.first == selectedMinutes }?.second
                        ?: if (selectedMinutes == 0) "Never" else "$selectedMinutes minutes"
                    Text(
                        currentTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
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
            timeoutOptions.forEach { (minutes, title) ->
                val isSelected = minutes == selectedMinutes
                DropdownMenuItem(
                    text = {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
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
