package fi.ak.smartvoice.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.data.DictionaryRow
import fi.ak.smartvoice.data.DictionaryRows
import fi.ak.smartvoice.theme.BadgeRose
import fi.ak.smartvoice.theme.BadgeViolet
import fi.ak.smartvoice.theme.Radius
import fi.ak.smartvoice.theme.Spacing
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.components.SettingsDivider
import fi.ak.smartvoice.ui.components.SettingsGroupCard
import fi.ak.smartvoice.ui.components.SettingsIconBadge
import fi.ak.smartvoice.ui.components.SettingsItemRow
import fi.ak.smartvoice.ui.components.SubScreenHost

/**
 * The Profiles tab: a list of cleaning profiles (tap a card to make it active, its button
 * opens the detail), and the one shared dictionary every profile uses.
 */
private enum class ProfilesDestination { LIST, DETAIL, DICTIONARY }

@Composable
fun ProfilesScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf(ProfilesDestination.LIST) }
    var detailProfileId by remember { mutableStateOf<String?>(null) }

    // The pill can switch the active profile while this tab is backgrounded, so re-read on resume
    // (same reason Home does). One source of truth: the ViewModel's stores.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshProfiles()
        viewModel.refreshDictionary()
        onPauseOrDispose { }
    }

    val openProfileId = detailProfileId
    when {
        destination == ProfilesDestination.DICTIONARY -> DictionarySubScreen(
            viewModel = viewModel,
            modifier = modifier,
            onNavigateUp = { destination = ProfilesDestination.LIST },
        )

        destination == ProfilesDestination.DETAIL && openProfileId != null -> ProfileDetailScreen(
            viewModel = viewModel,
            profileId = openProfileId,
            modifier = modifier,
            onNavigateUp = {
                destination = ProfilesDestination.LIST
                detailProfileId = null
            },
        )

        else -> ProfilesListScreen(
            viewModel = viewModel,
            modifier = modifier,
            onOpenProfile = { id ->
                detailProfileId = id
                destination = ProfilesDestination.DETAIL
            },
            onOpenDictionary = { destination = ProfilesDestination.DICTIONARY },
        )
    }
}

// ----------------------------------------------------------------------------
// Profile list
// ----------------------------------------------------------------------------

@Composable
private fun ProfilesListScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onOpenProfile: (String) -> Unit,
    onOpenDictionary: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val dictionaryEntries by viewModel.dictionaryEntries.collectAsStateWithLifecycle()

    var showNewProfileDialog by remember { mutableStateOf(false) }

    if (showNewProfileDialog) {
        NewProfileDialog(
            onDismiss = { showNewProfileDialog = false },
            onSave = { name, desc, rules ->
                viewModel.saveCustomProfile(
                    CleaningProfile(
                        id = "custom_" + System.currentTimeMillis(),
                        name = name,
                        description = desc,
                        rules = rules,
                        isBuiltin = false,
                    ),
                )
                showNewProfileDialog = false
            },
        )
    }

    val correctionCount = DictionaryRows.of(dictionaryEntries).size

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Profiles",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Choose how AI polishes your speech, and teach it the words you use.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeading("Cleaning profiles")
                SettingsGroupCard {
                    profiles.forEachIndexed { index, profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == activeProfileId,
                            onSelect = { viewModel.setActiveProfile(profile.id) },
                            onOpenDetail = { onOpenProfile(profile.id) },
                        )
                        SettingsDivider(startIndent = 56.dp)
                    }
                    SettingsItemRow(
                        title = "New profile",
                        subtitle = "Start from the standard rules and adjust them",
                        badge = {
                            SettingsIconBadge(
                                icon = Icons.Default.Add,
                                contentDescription = null,
                            )
                        },
                        onClick = { showNewProfileDialog = true },
                    )
                }
                if (profiles.isEmpty()) {
                    Text(
                        text = "No profiles yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeading("Dictionary")
                SettingsGroupCard {
                    SettingsItemRow(
                        title = "Shared dictionary",
                        subtitle = when (correctionCount) {
                            0 -> "Teach names and words it mishears"
                            1 -> "1 correction · used by every profile"
                            else -> "$correctionCount corrections · used by every profile"
                        },
                        badge = {
                            SettingsIconBadge(
                                icon = Icons.Default.Spellcheck,
                                badgeColor = BadgeRose,
                                contentDescription = null,
                            )
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = onOpenDictionary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: CleaningProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent,
            )
            .clickable(onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isActive) BadgeViolet else Color.Transparent)
                .border(
                    width = if (isActive) 0.dp else 1.5.dp,
                    color = if (isActive) Color.Transparent else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Active profile",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile.description.isNotBlank()) {
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (profile.isBuiltin) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Built-in profile",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(16.dp),
            )
        }

        IconButton(onClick = onOpenDetail) {
            Icon(
                imageVector = if (profile.isBuiltin) Icons.Default.ChevronRight else Icons.Default.Edit,
                contentDescription = if (profile.isBuiltin) "View ${profile.name}" else "Edit ${profile.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Profile detail
// ----------------------------------------------------------------------------

@Composable
private fun ProfileDetailScreen(
    viewModel: DictationViewModel,
    profileId: String,
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == profileId }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    SubScreenHost(onNavigateUp = onNavigateUp, modifier = modifier) {
        if (profile == null) {
            // Deleted from under us; nothing to edit.
            return@SubScreenHost
        }

        var name by remember(profile.id) { mutableStateOf(profile.name) }
        var description by remember(profile.id) { mutableStateOf(profile.description) }
        var rules by remember(profile.id) { mutableStateOf(profile.rules) }

        if (showDeleteConfirm) {
            DeleteProfileDialog(
                name = profile.name,
                onDismiss = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    viewModel.deleteCustomProfile(profile.id)
                    onNavigateUp()
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (profile.isBuiltin) profile.name else "Edit profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (profile.isBuiltin) {
                        "Built-in profiles are ready to use as they are."
                    } else {
                        "Custom profiles are yours to change."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (profile.isBuiltin) {
                BuiltInNote()
            }

            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    DetailField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Name",
                        enabled = !profile.isBuiltin,
                    )
                    DetailField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Short description",
                        enabled = !profile.isBuiltin,
                    )
                    DetailField(
                        value = rules,
                        onValueChange = { rules = it },
                        label = "Cleanup instructions",
                        enabled = !profile.isBuiltin,
                        minLines = 5,
                    )
                }
            }

            if (!profile.isBuiltin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Button(
                        onClick = {
                            viewModel.saveCustomProfile(
                                profile.copy(
                                    name = name.trim(),
                                    description = description.trim(),
                                    rules = rules.trim(),
                                ),
                            )
                            onNavigateUp()
                        },
                        enabled = name.isNotBlank() && rules.isNotBlank(),
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = "Built-in profile — it can't be edited or deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = minLines == 1,
        minLines = minLines,
        maxLines = if (minLines == 1) 1 else 10,
        shape = RoundedCornerShape(Radius.sm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
            focusedBorderColor = BadgeViolet,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeleteProfileDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.md),
        title = { Text("Delete profile?", fontWeight = FontWeight.Bold) },
        text = { Text("\"$name\" will be removed. The shared dictionary is not affected.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ----------------------------------------------------------------------------
// Shared dictionary
// ----------------------------------------------------------------------------

@Composable
private fun DictionarySubScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
) {
    val entries by viewModel.dictionaryEntries.collectAsStateWithLifecycle()
    val rows = DictionaryRows.of(entries)

    var heard by remember { mutableStateOf("") }
    var correct by remember { mutableStateOf("") }

    fun submit() {
        if (correct.isBlank()) return
        viewModel.addDictionaryCorrection(correct, heard)
        heard = ""
        correct = ""
    }

    SubScreenHost(onNavigateUp = onNavigateUp, modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Shared dictionary",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "The same corrections apply to every profile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsGroupCard {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text(
                            text = "Add a correction",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedTextField(
                            value = heard,
                            onValueChange = { heard = it },
                            label = { Text("What did it hear?") },
                            placeholder = { Text("e.g. gee mail") },
                            singleLine = true,
                            shape = RoundedCornerShape(Radius.sm),
                            colors = dictionaryFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = correct,
                            onValueChange = { correct = it },
                            label = { Text("What should it say instead?") },
                            placeholder = { Text("e.g. Gmail") },
                            singleLine = true,
                            shape = RoundedCornerShape(Radius.sm),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            colors = dictionaryFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Leave the first box empty to only fix the spelling of a word.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = { submit() },
                                enabled = correct.isNotBlank(),
                                shape = RoundedCornerShape(Radius.sm),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BadgeRose,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add correction", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeading("Corrections")
                    if (rows.isEmpty()) {
                        Text(
                            text = "Nothing here yet. Add a name or a word it keeps getting wrong.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = Spacing.sm),
                        )
                    } else {
                        SettingsGroupCard {
                            rows.forEachIndexed { index, row ->
                                DictionaryRowItem(
                                    row = row,
                                    onRemove = {
                                        val wrong = row.incorrect
                                        if (wrong != null) {
                                            viewModel.removeDictionaryCorrection(row.correct, wrong)
                                        } else {
                                            viewModel.removeDictionaryEntry(row.correct)
                                        }
                                    },
                                )
                                if (index < rows.lastIndex) SettingsDivider(startIndent = 16.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dictionaryFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedBorderColor = BadgeRose,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
private fun DictionaryRowItem(
    row: DictionaryRow,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (row.incorrect != null) {
                Text(
                    text = row.incorrect,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Always spell",
                    style = MaterialTheme.typography.labelSmall,
                    color = BadgeRose,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
            }
        }

        Text(
            text = "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )

        Text(
            text = row.correct,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove ${row.correct}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
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
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun NewProfileDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, rules: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(CleanupPrompt.Variant.SMART_CLEAN.rules) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "New profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        shape = RoundedCornerShape(Radius.md),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name") },
                    placeholder = { Text("e.g. Email / Formal") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = dictionaryFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short description") },
                    placeholder = { Text("e.g. Formal sentences without contractions") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = dictionaryFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it },
                    label = { Text("Cleanup instructions (prefilled from Standard)") },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = dictionaryFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), description.trim(), rules.trim()) },
                enabled = name.isNotBlank() && rules.isNotBlank(),
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BadgeViolet,
                    contentColor = Color.White,
                ),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
