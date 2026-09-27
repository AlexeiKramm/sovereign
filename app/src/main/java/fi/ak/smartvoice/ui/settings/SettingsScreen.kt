package fi.ak.smartvoice.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import fi.ak.smartvoice.theme.ThemePaletteStyle
import fi.ak.smartvoice.theme.ThemePresets
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.engine.EnginePreference
import fi.ak.smartvoice.theme.BadgeBlue
import fi.ak.smartvoice.theme.BadgeGreen
import fi.ak.smartvoice.theme.Motion
import fi.ak.smartvoice.ui.DictationViewModel
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.History
import fi.ak.smartvoice.ui.ServerStatus
import fi.ak.smartvoice.ui.backup.BackupScreen
import fi.ak.smartvoice.ui.benchmark.BenchmarkScreen
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge
import fi.ak.smartvoice.ui.components.SettingsItemRow
import fi.ak.smartvoice.ui.components.SettingsSectionHeader
import fi.ak.smartvoice.ui.components.SettingsSliderRow
import fi.ak.smartvoice.ui.components.SettingsToggleRow
import fi.ak.smartvoice.ui.components.SubScreenHost
import fi.ak.smartvoice.ui.history.HistoryScreen
import fi.ak.smartvoice.ui.models.ModelsScreen
import fi.ak.smartvoice.ui.stats.StatsScreen
import fi.ak.smartvoice.ui.transcribe.TranscribeHubScreen
import java.util.Locale

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
 * Settings, split the way the plan asks: a **Basic** surface of plain-language controls with a
 * collapsed **Advanced** section underneath, then the diagnostics tools, then a deliberately
 * quiet **Legacy** door for the development knobs (pill master switch, transcription mode, server
 * URL, flick feel) that most people never touch. Nothing was removed; only re-homed.
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
            // Render main settings below
        }
    }

    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeSeedColor by viewModel.themeSeedColor.collectAsStateWithLifecycle()
    val themePaletteStyle by viewModel.themePaletteStyle.collectAsStateWithLifecycle()
    val themeUseSystemDynamic by viewModel.themeUseSystemDynamic.collectAsStateWithLifecycle()
    val pauseField by viewModel.pauseField.collectAsStateWithLifecycle()
    val stopField by viewModel.stopField.collectAsStateWithLifecycle()
    val floatingPillOnlyWhenKeyboardOpen by viewModel.floatingPillOnlyWhenKeyboardOpen.collectAsStateWithLifecycle()
    val floatingPillAutoCommit by viewModel.floatingPillAutoCommit.collectAsStateWithLifecycle()
    val floatingPillEdgeAnchoring by viewModel.floatingPillEdgeAnchoring.collectAsStateWithLifecycle()
    val quickDictateOnHold by viewModel.quickDictateOnHold.collectAsStateWithLifecycle()

    // The user's choice: Advanced is always collapsed on arrival, never remembered. Declared after
    // the sub-screen branches so returning from one also starts it collapsed.
    var advancedExpanded by remember { mutableStateOf(false) }

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
                text = "Appearance, dictation, and voice settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- APPEARANCE (Basic) ---
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

        // --- DICTATION (Basic) ---
        SettingsSectionHeader(title = "Dictation")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsSliderRow(
                    title = "Pause sensitivity",
                    subtitle = "How long a pause splits your speech into a new segment",
                    value = pauseSeconds,
                    valueLabel = SettingsControls.formatSeconds(pauseSeconds),
                    valueRange = SettingsControls.PAUSE_MIN_SECONDS..SettingsControls.PAUSE_MAX_SECONDS,
                    steps = SettingsControls.PAUSE_STEPS,
                    onValueChange = viewModel::setPauseSilenceSeconds,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsSliderRow(
                    title = "Auto-stop silence",
                    subtitle = "How long silence turns the mic off automatically",
                    value = stopSeconds,
                    valueLabel = SettingsControls.formatSeconds(stopSeconds),
                    valueRange = SettingsControls.STOP_MIN_SECONDS..SettingsControls.STOP_MAX_SECONDS,
                    steps = SettingsControls.STOP_STEPS,
                    onValueChange = viewModel::setStopAfterSilenceSeconds,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsToggleRow(
                    title = "Show pill only when keyboard is open",
                    subtitle = "Hides the pill when you're not typing anywhere",
                    checked = floatingPillOnlyWhenKeyboardOpen,
                    onCheckedChange = viewModel::setFloatingPillOnlyWhenKeyboardOpen,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsToggleRow(
                    title = "Auto-insert on silence",
                    subtitle = "Automatically inserts text when the mic stops",
                    checked = floatingPillAutoCommit,
                    onCheckedChange = viewModel::setFloatingPillAutoCommit,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsToggleRow(
                    title = "Hold the pill to dictate",
                    subtitle = "Press and hold the pill to dictate and insert automatically",
                    checked = quickDictateOnHold,
                    onCheckedChange = viewModel::setQuickDictateOnHold,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsToggleRow(
                    title = "Snap pill to screen edges",
                    subtitle = "Snaps the resting pill to the nearest edge so it doesn't block content",
                    checked = floatingPillEdgeAnchoring,
                    onCheckedChange = viewModel::setFloatingPillEdgeAnchoring,
                )
            }
        }

        // --- RUN SETUP (kept in Basic, deliberately low-key) ---
        SettingsGroupCard {
            SettingsItemRow(
                title = "Run setup again",
                subtitle = "Walk through permissions and voice models again",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.Refresh,
                        badgeColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // --- ADVANCED (collapsed, visually quieter) ---
        AdvancedSettingsSection(
            viewModel = viewModel,
            expanded = advancedExpanded,
            onToggle = { advancedExpanded = !advancedExpanded },
        )

        // --- DIAGNOSTICS & TOOLS ---
        SettingsSectionHeader(title = "Diagnostics & Tools")
        SettingsGroupCard {
            SettingsItemRow(
                title = "Dictation History",
                subtitle = "Review, copy, and re-insert recent dictations",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.History,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentDescription = "Dictation History",
                    )
                },
                trailing = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open Dictation History",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { currentSubScreen = SettingsSubScreen.HISTORY },
            )
            SettingsDivider(startIndent = 72.dp)
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
                    Icon(
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
                    Icon(
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
            SettingsDivider(startIndent = 72.dp)
            SettingsItemRow(
                title = "Backup & Restore",
                subtitle = "Export or restore dictionary, profiles, and settings",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.Backup,
                        badgeColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                        contentDescription = "Backup and restore",
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
        }

        // --- LEGACY & DEVELOPMENT ---
        SettingsGroupCard {
            SettingsItemRow(
                title = "Legacy & development settings",
                subtitle = "Pill switch, transcription mode, server, and flick feel",
                badge = {
                    SettingsIconBadge(
                        icon = Icons.Default.Build,
                        badgeColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "Legacy settings",
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
        }

        // --- ABOUT / BUILD INFO ---
        Text(
            text = "Smart Voice v${buildVersion(context)} • Local On-Device AI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp),
        )
    }
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
) {
    SettingsGroupCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
                        text = "Appearance",
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

            Text(
                text = "Mode",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsToggleRow(
                    title = "Material You dynamic colors",
                    subtitle = "Derive colors from your system wallpaper instead of a custom seed",
                    checked = useSystemDynamic,
                    onCheckedChange = onUseSystemDynamicChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Accent Color",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val activeHex = remember(seedColor) {
                        String.format(java.util.Locale.US, "#%06X", seedColor and 0x00FFFFFF)
                    }
                    Text(
                        text = if (useSystemDynamic) "System Dynamic" else activeHex,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(ThemePresets.ALL) { preset ->
                        val isSelected = !useSystemDynamic && (seedColor and 0x00FFFFFF) == (preset.argb and 0x00FFFFFF)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(preset.color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
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
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                val hsv = remember(seedColor) {
                    val arr = FloatArray(3)
                    android.graphics.Color.colorToHSV(seedColor, arr)
                    arr
                }
                var currentHue by remember(seedColor) { mutableFloatStateOf(hsv[0]) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "Custom Hue Spectrum (%d°)", currentHue.roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Red,
                                        Color.Yellow,
                                        Color.Green,
                                        Color.Cyan,
                                        Color.Blue,
                                        Color.Magenta,
                                        Color.Red,
                                    )
                                )
                            )
                    )
                    Slider(
                        value = currentHue,
                        onValueChange = { hue ->
                            currentHue = hue
                            val newColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f))
                            onUseSystemDynamicChange(false)
                            onSeedColorChange(newColor)
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "M3 Palette Variant",
                    style = MaterialTheme.typography.labelMedium,
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
}

/**
 * The collapsed Advanced section: model and memory controls, all of which existed before. It opens
 * with a quiet row and a cross-fade/height animation, and starts collapsed every time.
 */
@Composable
private fun AdvancedSettingsSection(
    viewModel: DictationViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val litertProcessor by viewModel.litertProcessor.collectAsStateWithLifecycle()
    val litertGpuSupported by viewModel.litertGpuSupported.collectAsStateWithLifecycle()
    val litertCompatNote by viewModel.litertCompatibilityNote.collectAsStateWithLifecycle()
    val litertSpeculativeDecoding by viewModel.litertSpeculativeDecoding.collectAsStateWithLifecycle()
    val selectedLitertModel by viewModel.selectedLitertModel.collectAsStateWithLifecycle()
    val healingSentencesCount by viewModel.healingSentencesCount.collectAsStateWithLifecycle()
    val idleTimeoutMinutes by viewModel.modelIdleTimeoutMinutes.collectAsStateWithLifecycle()
    val unloadOnScreenOff by viewModel.unloadOnScreenOff.collectAsStateWithLifecycle()
    val keepScreenOnWhileRecording by viewModel.keepScreenOnWhileRecording.collectAsStateWithLifecycle()
    val unloadOnKeyboardClose by viewModel.unloadOnKeyboardClose.collectAsStateWithLifecycle()
    val timingNote by viewModel.timingNote.collectAsStateWithLifecycle()

    SettingsGroupCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconBadge(
                icon = Icons.Default.Tune,
                badgeColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Advanced",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Model and memory controls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse advanced settings" else "Expand advanced settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(Motion.standardMs)) +
                fadeIn(animationSpec = tween(Motion.standardMs)),
            exit = shrinkVertically(animationSpec = tween(Motion.fastMs)) +
                fadeOut(animationSpec = tween(Motion.fastMs)),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsDivider(startIndent = 0.dp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Memory,
                        badgeColor = BadgeGreen,
                        contentDescription = "Hardware acceleration",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AI cleanup processor",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Where the on-device AI cleanup runs",
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
                    Text("Re-test compatibility", style = MaterialTheme.typography.labelMedium)
                }

                SettingsDivider(startIndent = 0.dp)

                val modelSupportsSpeculative = selectedLitertModel.supportsSpeculativeDecoding
                SettingsToggleRow(
                    title = "Speculative decoding",
                    subtitle = if (modelSupportsSpeculative) {
                        "Predicts multiple tokens at once for faster cleanup"
                    } else {
                        "The selected model does not include speculative drafting heads"
                    },
                    checked = litertSpeculativeDecoding && modelSupportsSpeculative,
                    enabled = modelSupportsSpeculative,
                    onCheckedChange = viewModel::setLitertSpeculativeDecoding,
                )

                SettingsDivider(startIndent = 0.dp)

                HealingSentencesStepper(
                    count = healingSentencesCount,
                    onCountChange = viewModel::onHealingSentencesCountChange,
                )

                SettingsDivider(startIndent = 0.dp)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Idle memory timeout",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Unload models automatically after a period of inactivity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IdleTimeoutDropdownSelector(
                        selectedMinutes = idleTimeoutMinutes,
                        onMinutesSelected = viewModel::onModelIdleTimeoutMinutesChange,
                    )
                }

                SettingsDivider(startIndent = 0.dp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.BatteryChargingFull,
                        badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentDescription = "Battery and memory",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Battery & memory",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                SettingsToggleRow(
                    title = "Unload models on screen off",
                    subtitle = "Frees ~1.5 GB RAM while the device is locked",
                    checked = unloadOnScreenOff,
                    onCheckedChange = viewModel::onUnloadOnScreenOffChange,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsToggleRow(
                    title = "Keep screen on while recording",
                    subtitle = "Stops the screen dimming and locking during a dictation",
                    checked = keepScreenOnWhileRecording,
                    onCheckedChange = viewModel::onKeepScreenOnWhileRecordingChange,
                )

                SettingsDivider(startIndent = 0.dp)

                SettingsToggleRow(
                    title = "Unload models on keyboard close",
                    subtitle = "Frees RAM when the keyboard is dismissed (the next tap reloads)",
                    checked = unloadOnKeyboardClose,
                    onCheckedChange = viewModel::onUnloadOnKeyboardCloseChange,
                )

                SettingsDivider(startIndent = 0.dp)

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
                        Text("Reset defaults", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HealingSentencesStepper(
    count: Int,
    onCountChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(
                text = "Healing sentences",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "How many previous sentences the AI uses as context when cleaning up",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onCountChange((count - 1).coerceAtLeast(0)) },
                enabled = count > 0,
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer sentences")
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(28.dp),
            )
            IconButton(
                onClick = { onCountChange((count + 1).coerceAtMost(10)) },
                enabled = count < 10,
            ) {
                Icon(Icons.Default.Add, contentDescription = "More sentences")
            }
        }
    }
}

/**
 * The Legacy sub-screen: the development knobs that used to crowd the main Settings surface.
 * The pill master switch lives here because the pill is now the app's only dictation surface —
 * turning it off is a developer action, not a daily toggle.
 */
@Composable
private fun LegacySettingsScreen(viewModel: DictationViewModel) {
    val context = LocalContext.current
    val floatingPillEnabled by viewModel.floatingPillEnabled.collectAsStateWithLifecycle()
    val pillFlickFriction by viewModel.pillFlickFriction.collectAsStateWithLifecycle()
    val pillSnapAttenuation by viewModel.pillSnapAttenuation.collectAsStateWithLifecycle()
    val pillSnapStiffness by viewModel.pillSnapStiffness.collectAsStateWithLifecycle()
    val pillSnapDamping by viewModel.pillSnapDamping.collectAsStateWithLifecycle()
    val isOverlayPermissionGranted by viewModel.isOverlayPermissionGranted.collectAsStateWithLifecycle()
    val isAccessibilityPermissionGranted by viewModel.isAccessibilityPermissionGranted.collectAsStateWithLifecycle()
    val enginePreference by viewModel.enginePreference.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshFloatingPillPermissions()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Legacy",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Development and diagnostic controls. Most people never need these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSectionHeader(title = "Dictation pill")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsToggleRow(
                    title = "Floating dictation pill",
                    subtitle = "The pill is the app's only dictation surface. Turning it off leaves no way to dictate.",
                    checked = floatingPillEnabled,
                    onCheckedChange = viewModel::setFloatingPillEnabled,
                )

                if (floatingPillEnabled) {
                    if (!isOverlayPermissionGranted || !isAccessibilityPermissionGranted) {
                        SettingsDivider(startIndent = 0.dp)
                        PermissionWarningCard(
                            context = context,
                            overlayGranted = isOverlayPermissionGranted,
                            accessibilityGranted = isAccessibilityPermissionGranted,
                        )
                    }

                    SettingsDivider(startIndent = 0.dp)

                    SettingsSliderRow(
                        title = "Flick friction",
                        subtitle = "How far the pill glides after you flick it. Higher stops sooner.",
                        value = pillFlickFriction,
                        valueLabel = String.format(Locale.US, "%.0f", pillFlickFriction),
                        valueRange = AppSettings.MIN_PILL_FLICK_FRICTION..AppSettings.MAX_PILL_FLICK_FRICTION,
                        steps = 54,
                        onValueChange = viewModel::setPillFlickFriction,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsSliderRow(
                        title = "Snap vertical momentum attenuation",
                        subtitle = "Vertical momentum follow-through during edge snap (0.00 = horizontal only, 1.00 = full carryover).",
                        value = pillSnapAttenuation,
                        valueLabel = String.format(Locale.US, "%.2f", pillSnapAttenuation),
                        valueRange = AppSettings.MIN_PILL_SNAP_ATTENUATION..AppSettings.MAX_PILL_SNAP_ATTENUATION,
                        steps = 19,
                        onValueChange = viewModel::setPillSnapAttenuation,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsSliderRow(
                        title = "Snap spring stiffness",
                        subtitle = "Spring tension during edge snap. Lower accelerates more gently; higher snaps faster.",
                        value = pillSnapStiffness,
                        valueLabel = String.format(Locale.US, "%.0f", pillSnapStiffness),
                        valueRange = AppSettings.MIN_PILL_SNAP_STIFFNESS..AppSettings.MAX_PILL_SNAP_STIFFNESS,
                        steps = 27,
                        onValueChange = viewModel::setPillSnapStiffness,
                    )

                    SettingsDivider(startIndent = 0.dp)

                    SettingsSliderRow(
                        title = "Snap damping ratio",
                        subtitle = "1.00 = critically damped (no overshoot/bounce). < 1.00 bounces, > 1.00 is sluggish.",
                        value = pillSnapDamping,
                        valueLabel = String.format(Locale.US, "%.2f", pillSnapDamping),
                        valueRange = AppSettings.MIN_PILL_SNAP_DAMPING..AppSettings.MAX_PILL_SNAP_DAMPING,
                        steps = 19,
                        onValueChange = viewModel::setPillSnapDamping,
                    )
                }
            }
        }

        SettingsSectionHeader(title = "Transcription")
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(
                        icon = Icons.Default.Mic,
                        badgeColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentDescription = "Transcription mode",
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Transcription mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Local on-device or self-hosted server",
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
                                    EnginePreference.LOCAL -> "On-Device: fully private and offline with zero network calls"
                                    EnginePreference.REMOTE -> "Self-Hosted: transcribes via your private server"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SettingsDivider(startIndent = 0.dp)

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
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Test Connection", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionWarningCard(
    context: Context,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        shape = RoundedCornerShape(8.dp),
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
                        shape = RoundedCornerShape(8.dp),
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
    "dev"
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
