package fi.ak.smartvoice.ui.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.data.UsageStatsData
import fi.ak.smartvoice.theme.BadgeCyan
import fi.ak.smartvoice.theme.BadgeOrange
import fi.ak.smartvoice.theme.BadgeViolet
import fi.ak.smartvoice.theme.Radius
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ModelStatus
import kotlin.math.roundToInt

/**
 * The dashboard: permission health (only while something is missing), voice-model health,
 * all-time stats, and a quick profile switcher.
 */
@Composable
fun HomeScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onSetUpModels: () -> Unit = {},
) {
    val context = LocalContext.current

    val micGranted by viewModel.isMicPermissionGranted.collectAsStateWithLifecycle()
    val overlayGranted by viewModel.isOverlayPermissionGranted.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.isAccessibilityPermissionGranted.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val stats by viewModel.usageStats.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshDashboard() }

    // Re-read on every resume: dictation happens in the pill and the IME, whose
    // writes this screen has no other way of hearing about.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshDashboard()
        onPauseOrDispose { }
    }

    val missingPermissions = listOfNotNull(
        if (!micGranted) PermissionItem.Microphone else null,
        if (!overlayGranted) PermissionItem.Overlay else null,
        if (!accessibilityGranted) PermissionItem.Accessibility else null,
    )

    // A downloaded model stuck in "failed/cancelled" reads the same as none at all: the app
    // cannot dictate until one is installed, so the card stays until it is.
    val needsModel = modelStatus !is ModelStatus.Installed && modelStatus !is ModelStatus.Downloading

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        HomeHeader()

        if (missingPermissions.isNotEmpty()) {
            PermissionHealthSection(
                missing = missingPermissions,
                onGrant = { item ->
                    when (item) {
                        PermissionItem.Microphone ->
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

                        PermissionItem.Overlay -> runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(context, "Could not open display settings", Toast.LENGTH_SHORT).show()
                        }

                        PermissionItem.Accessibility -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }.onFailure {
                            Toast.makeText(context, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        if (needsModel) {
            ModelSetupSection(onSetUpModels = onSetUpModels)
        }

        StatsSection(stats = stats.toStatTiles())

        ProfileSwitcherSection(
            profiles = profiles,
            activeProfileId = activeProfileId,
            onSelect = { viewModel.setActiveProfile(it) },
        )
    }
}

@Composable
private fun HomeHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Smart Voice",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Private, on-device dictation",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------------
// Permission health
// ----------------------------------------------------------------------------

private enum class PermissionItem(val title: String, val why: String, val icon: ImageVector) {
    Microphone(
        title = "Microphone",
        why = "So Smart Voice can hear your voice.",
        icon = Icons.Default.Mic,
    ),
    Overlay(
        title = "Display over other apps",
        why = "So the pill can float over your keyboard.",
        icon = Icons.Default.Layers,
    ),
    Accessibility(
        title = "Accessibility service",
        why = "So the pill can type into any text field for you.",
        icon = Icons.Default.Accessibility,
    ),
}

@Composable
private fun PermissionHealthSection(
    missing: List<PermissionItem>,
    onGrant: (PermissionItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = "Finish setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = "Smart Voice needs ${missing.size} more permission${if (missing.size == 1) "" else "s"} before it can dictate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        missing.forEach { item ->
            PermissionCard(item = item, onGrant = { onGrant(item) })
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem, onGrant: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.sm),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Button(
                onClick = onGrant,
                shape = RoundedCornerShape(Radius.pill),
            ) {
                Text("Grant")
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Voice-model health
// ----------------------------------------------------------------------------

/**
 * Shown while no speech model is installed. The wizard offers to download one at first launch,
 * but it is skippable — this is the safety net that keeps "no model" from being a dead end that
 * only Settings knows about.
 */
@Composable
private fun ModelSetupSection(onSetUpModels: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Set up voice models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Smart Voice runs on your phone, so it needs a model downloaded before it " +
                    "can dictate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Button(
                onClick = onSetUpModels,
                shape = RoundedCornerShape(Radius.pill),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Set up models", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Stats
// ----------------------------------------------------------------------------

private data class StatTile(
    val value: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
)

private fun UsageStatsData.toStatTiles(): List<StatTile> {
    val words = if (totalDeliveredWords > 0) totalDeliveredWords else totalCleanedWords
    return listOf(
        StatTile(
            value = formatCount(words),
            label = "Words dictated",
            icon = Icons.Default.Mic,
            accent = BadgeViolet,
        ),
        StatTile(
            value = formatTypingTime(typingMinutesSaved),
            label = "Time saved",
            icon = Icons.Default.Speed,
            accent = BadgeCyan,
        ),
        StatTile(
            value = formatCount(totalWordsChanged + totalWordsRemoved),
            label = "Mistakes fixed",
            icon = Icons.Default.Check,
            accent = BadgeOrange,
        ),
    )
}

private fun formatCount(value: Int): String =
    if (value >= 10_000) "${(value / 1000.0).roundToInt()}k" else value.toString()

/** Seconds under a minute, whole minutes under an hour, then hours. */
private fun formatTypingTime(minutes: Double): String {
    val totalSeconds = (minutes * 60).roundToInt()
    return when {
        totalSeconds < 60 -> "${totalSeconds}s"
        minutes < 60 -> "${minutes.roundToInt()} min"
        else -> {
            val hours = minutes / 60
            // Pinned to US formatting: the UI is English-only, so a locale-dependent
            // decimal comma ("1,6h") would read as a bug next to English labels.
            if (hours < 10) String.format(java.util.Locale.US, "%.1fh", hours) else "${hours.roundToInt()}h"
        }
    }
}

@Composable
private fun StatsSection(stats: List<StatTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionHeading("Your dictation")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            stats.forEach { tile ->
                StatTileCard(tile = tile, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTileCard(tile: StatTile, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        color = tile.accent.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = tile.accent.copy(alpha = 0.28f),
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = tile.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = tile.value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Quick profile switcher
// ----------------------------------------------------------------------------

@Composable
private fun ProfileSwitcherSection(
    profiles: List<CleaningProfile>,
    activeProfileId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionHeading("Profile")

        if (profiles.isEmpty()) {
            Text(
                text = "No profiles yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileChip(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onClick = { onSelect(profile.id) },
                )
            }
        }
    }
}

@Composable
private fun ProfileChip(
    profile: CleaningProfile,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val container = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Column(
        modifier = Modifier
            .width(180.dp)
            .background(container, RoundedCornerShape(Radius.md))
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Active profile",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else if (profile.isBuiltin) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Built-in profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = profile.description.ifBlank { "Custom formatting profile" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

// ----------------------------------------------------------------------------
// Shared bits
// ----------------------------------------------------------------------------

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}
