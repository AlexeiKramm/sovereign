package fi.ak.smartvoice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.data.CleaningProfile

enum class PermissionItem(val title: String, val why: String, val icon: ImageVector) {
    Microphone(
        title = "Microphone permission required",
        why = "Required for speech recognition to hear your voice.",
        icon = Icons.Default.Mic,
    ),
    Overlay(
        title = "Display over other apps",
        why = "Required for the floating pill to appear over your keyboard.",
        icon = Icons.Default.Layers,
    ),
    Accessibility(
        title = "Accessibility service disabled",
        why = "Required to insert dictated text directly at your cursor.",
        icon = Icons.Default.Accessibility,
    ),
}

fun asrModelTierTitle(modelId: String, fallbackLabel: String = ""): String {
    val tier = AsrModels.getTierTitle(modelId)
    return if (tier == modelId && fallbackLabel.isNotBlank()) fallbackLabel else tier
}

fun asrModelTierSubtitle(modelId: String): String = when (modelId) {
    AsrModels.DEFAULT_MODEL_ID -> "Nemotron 3.5 · Real-time"
    AsrModels.PARAKEET_TDT_V3.id -> "Parakeet TDT v3 · Chunked"
    AsrModels.WHISPER_BASE.id -> "Whisper Base · Chunked"
    else -> "Speech recognition"
}

@Composable
fun StatusConfigurationCard(
    allGreen: Boolean,
    missingPermissions: List<PermissionItem>,
    onGrantPermission: (PermissionItem) -> Unit,
    selectedAsrModel: AsrModelSpec,
    asrModels: List<AsrModelSpec>,
    onSelectAsrModel: (AsrModelSpec) -> Unit,
    onNavigateToModels: () -> Unit,
    cleanupEnabled: Boolean,
    onToggleCleanup: () -> Unit,
    activeProfileId: String,
    profiles: List<CleaningProfile>,
    onSelectProfile: (CleaningProfile) -> Unit,
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    statusSubtitle: String? = null,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    val activeProfileName = activeProfile?.name ?: "Smart Clean"

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val badgeBg = if (allGreen) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                }
                val iconTint = if (allGreen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(badgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (allGreen) Icons.Default.Check else Icons.Default.PriorityHigh,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (allGreen) "All systems green" else "Setup required",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = statusSubtitle ?: if (allGreen) {
                            "Floating pill active · Ready in all apps"
                        } else {
                            "Missing required permissions"
                        },
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Permission alert banners if any missing
            if (missingPermissions.isNotEmpty()) {
                missingPermissions.forEach { item ->
                    PermissionAlertBanner(
                        item = item,
                        onGrant = { onGrantPermission(item) },
                    )
                }
            }

            // Row 1: Voice Model Selector & LLM Cleanup Toggle (2 horizontal cards)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Card 1: Voice Model Selector
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { modelMenuExpanded = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SettingsIconBadge(
                                icon = Icons.Default.Mic,
                                badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                size = 30.dp,
                                iconSize = 16.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "VOICE MODEL",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = asrModelTierTitle(selectedAsrModel.id, selectedAsrModel.label),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select ASR Model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        asrModels.forEach { model ->
                            val isSelected = model.id == selectedAsrModel.id
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = asrModelTierTitle(model.id, model.label),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        Text(
                                            text = asrModelTierSubtitle(model.id),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectAsrModel(model)
                                    modelMenuExpanded = false
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }

                // Card 2: LLM Cleanup Toggle
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleCleanup),
                    shape = RoundedCornerShape(16.dp),
                    color = if (cleanupEnabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        if (cleanupEnabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.AutoAwesome,
                            badgeColor = if (cleanupEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            iconTint = if (cleanupEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            size = 30.dp,
                            iconSize = 16.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LLM CLEANUP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (cleanupEnabled) "ON" else "OFF",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (cleanupEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (cleanupEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Text(
                                text = if (cleanupEnabled) "ON" else "OFF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (cleanupEnabled) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                            )
                        }
                    }
                }
            }

            // Row 2: Profile Selector Bar (Clean horizontal segmented buttons, no dropdown)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Header row: Icon, Category title, Active profile subtitle, and Manage link
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Default.Tune,
                            badgeColor = if (cleanupEnabled) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            iconTint = if (cleanupEnabled) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            size = 30.dp,
                            iconSize = 16.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CLEANUP PROFILE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (cleanupEnabled) {
                                    activeProfile?.description ?: activeProfileName
                                } else {
                                    "Disabled (LLM Cleanup is OFF)"
                                },
                                fontSize = 11.5.sp,
                                fontWeight = if (cleanupEnabled) FontWeight.Normal else FontWeight.Medium,
                                color = if (cleanupEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Surface(
                            modifier = Modifier.clickable(onClick = onNavigateToProfiles),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "All",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Manage Profiles",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    // Segmented horizontal selector
                    val displayProfiles = profiles.take(3)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            displayProfiles.forEach { profile ->
                                val isSelected = profile.id == activeProfileId
                                val segmentLabel = when (profile.id) {
                                    "aggressive_rewrite" -> "Aggressive"
                                    else -> profile.name
                                }
                                val bg = if (isSelected && cleanupEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                }
                                val textColor = when {
                                    !cleanupEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(bg)
                                        .clickable(enabled = cleanupEnabled) {
                                            onSelectProfile(profile)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = segmentLabel,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected && cleanupEnabled) FontWeight.Bold else FontWeight.Medium,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionAlertBanner(
    item: PermissionItem,
    onGrant: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = item.why,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Grant permission",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
