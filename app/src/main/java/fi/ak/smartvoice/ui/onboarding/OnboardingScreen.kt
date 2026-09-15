package fi.ak.smartvoice.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.theme.Radius
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ModelStatus

/** The wizard's screens, in order. [DONE] is the closing screen, not a `Step N of 4`. */
enum class OnboardingStep {
    MICROPHONE,
    OVERLAY,
    ACCESSIBILITY,
    MODELS,
    DONE,
}

private val WIZARD_STEPS = listOf(
    OnboardingStep.MICROPHONE,
    OnboardingStep.OVERLAY,
    OnboardingStep.ACCESSIBILITY,
    OnboardingStep.MODELS,
)

/**
 * The first-launch walkthrough: the three permissions the app cannot dictate without, then
 * one tap to download a working model pair, then a closing screen and Home.
 *
 * It is **skippable** — "Not now" goes straight to Home — because Home keeps showing
 * whatever is still missing (its permission cards, and a "set up voice models" card). A wizard
 * that traps someone who denies the overlay permission would be worse than one with a way out.
 *
 * The same screen is the Settings "Run setup again" destination, with [initialStep] pointing at
 * the first permission; finishing returns to Settings rather than to Home.
 */
@Composable
fun OnboardingScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    initialStep: OnboardingStep = OnboardingStep.MICROPHONE,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(initialStep) }
    var selectedPresetId by rememberSaveable { mutableStateOf(OnboardingModelPresets.STREAMING.id) }

    val micGranted by viewModel.isMicPermissionGranted.collectAsStateWithLifecycle()
    val overlayGranted by viewModel.isOverlayPermissionGranted.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.isAccessibilityPermissionGranted.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val download by viewModel.onboardingDownload.collectAsStateWithLifecycle()
    val installedPresetIds by viewModel.installedPresetIds.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshDashboard() }

    // The permission screens are Android's own: a grant is only visible on the way back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshDashboard()
        onPauseOrDispose { }
    }

    val selectedPreset = OnboardingModelPresets.byId(selectedPresetId)

    fun finish() {
        viewModel.cancelOnboardingDownload()
        onFinished()
    }

    fun advance() {
        val index = WIZARD_STEPS.indexOf(step)
        step = if (index < 0 || index == WIZARD_STEPS.lastIndex) {
            OnboardingStep.DONE
        } else {
            WIZARD_STEPS[index + 1]
        }
    }

    fun retreat() {
        val index = WIZARD_STEPS.indexOf(step)
        step = if (index <= 0) OnboardingStep.MICROPHONE else WIZARD_STEPS[index - 1]
    }

    BackHandler {
        if (step == OnboardingStep.DONE || step == OnboardingStep.MICROPHONE) finish() else retreat()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Spacing.screen),
    ) {
        if (step != OnboardingStep.DONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Step ${WIZARD_STEPS.indexOf(step) + 1} of ${WIZARD_STEPS.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { finish() }) {
                    Text("Not now")
                }
            }
            StepProgress(current = WIZARD_STEPS.indexOf(step) + 1, total = WIZARD_STEPS.size)
            Spacer(modifier = Modifier.height(Spacing.xl))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // `key(step)` gives each screen its own node identity. Without it the steps share a
            // button slot, and a tap whose finger-up lands after the transition triggers the next
            // step's button as well (one tap silently granting/opening two things).
            key(step) {
                when (step) {
                    OnboardingStep.MICROPHONE -> PermissionStep(
                        icon = Icons.Default.Mic,
                        title = "Microphone",
                        why = "So Smart Voice can hear your voice.",
                        granted = micGranted,
                        actionLabel = "Allow microphone",
                        onAction = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onContinue = { advance() },
                    )

                    OnboardingStep.OVERLAY -> PermissionStep(
                        icon = Icons.Default.Layers,
                        title = "Display over other apps",
                        why = "So the dictation pill can float over your keyboard.",
                        granted = overlayGranted,
                        actionLabel = "Open display settings",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }.onFailure {
                                Toast.makeText(context, "Could not open display settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onContinue = { advance() },
                    )

                    OnboardingStep.ACCESSIBILITY -> PermissionStep(
                        icon = Icons.Default.Accessibility,
                        title = "Accessibility service",
                        why = "So the pill can type into any text field for you.",
                        granted = accessibilityGranted,
                        actionLabel = "Open accessibility settings",
                        onAction = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                .onFailure {
                                    Toast.makeText(context, "Could not open accessibility settings", Toast.LENGTH_SHORT)
                                        .show()
                                }
                        },
                        onContinue = { advance() },
                    )

                    OnboardingStep.MODELS -> ModelsStep(
                        selectedPreset = selectedPreset,
                        installedPresetIds = installedPresetIds,
                        download = download,
                        onSelect = { selectedPresetId = it.id },
                        onDownload = { viewModel.startOnboardingDownload(selectedPreset) },
                        onCancel = { viewModel.cancelOnboardingDownload() },
                        onContinue = { advance() },
                        onLater = { advance() },
                    )

                    OnboardingStep.DONE -> DoneStep(
                        hasSpeechModel = modelStatus is ModelStatus.Installed,
                        onStart = { finish() },
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Steps
// ----------------------------------------------------------------------------

@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    why: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StepIcon(icon)
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = why,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (granted) {
            Spacer(modifier = Modifier.height(Spacing.lg))
            GrantedBadge()
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))
        Button(
            onClick = if (granted) onContinue else onAction,
            shape = RoundedCornerShape(Radius.pill),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (granted) "Continue" else actionLabel,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ModelsStep(
    selectedPreset: OnboardingModelPreset,
    installedPresetIds: Set<String>,
    download: DictationViewModel.OnboardingDownload,
    onSelect: (OnboardingModelPreset) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onLater: () -> Unit,
) {
    val running = download as? DictationViewModel.OnboardingDownload.Running
    val selectedInstalled = selectedPreset.id in installedPresetIds

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "Voice models",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Smart Voice runs entirely on your phone. Download a model so it can hear " +
                    "you — nothing is sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))

            OnboardingModelPresets.ALL.forEach { preset ->
                PresetCard(
                    preset = preset,
                    selected = preset.id == selectedPreset.id,
                    installed = preset.id in installedPresetIds,
                    enabled = running == null,
                    onSelect = { onSelect(preset) },
                )
            }

            Text(
                text = "You can change or download models later in Settings.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Keyed like the steps: Download must not swap to Cancel/Try again under the same finger.
        val controlState = when {
            running != null -> "running"
            download is DictationViewModel.OnboardingDownload.Failed &&
                download.presetId == selectedPreset.id -> "failed"
            selectedInstalled -> "installed"
            else -> "ready"
        }
        key(controlState) {
            when {
                running != null -> {
                    Text(
                        text = "Downloading ${running.stage.label}… ${(running.overall * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    LinearProgressIndicator(
                        progress = { running.overall },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(Radius.pill),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel")
                    }
                }

                download is DictationViewModel.OnboardingDownload.Failed &&
                    download.presetId == selectedPreset.id -> {
                    Text(
                        text = "Download failed: ${download.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(Radius.pill),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try again", fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                        Text("I'll do this later")
                    }
                }

                selectedInstalled -> Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(Radius.pill),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue", fontWeight = FontWeight.SemiBold)
                }

                else -> {
                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(Radius.pill),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Download (${formatModelSize(selectedPreset.totalBytes)})",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                        Text("I'll do this later")
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneStep(hasSpeechModel: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StepIcon(Icons.Default.CheckCircle)
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = "You're all set",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = if (hasSpeechModel) {
                "Open any text field and tap the pill to start dictating."
            } else {
                "You can download a voice model anytime from Home."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.xxl))
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(Radius.pill),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start using Smart Voice", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ----------------------------------------------------------------------------
// Shared bits
// ----------------------------------------------------------------------------

@Composable
private fun StepProgress(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (index < current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            )
        }
    }
}

@Composable
private fun StepIcon(icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun GrantedBadge() {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = "Granted",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: OnboardingModelPreset,
    selected: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        shape = RoundedCornerShape(Radius.md),
        color = container,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.outline,
                ),
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (installed) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Installed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(
                            text = "Ready",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${preset.asrModel.label} + ${preset.llmModel.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatModelSize(preset.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
