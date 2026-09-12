package fi.ak.smartvoice.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fi.ak.smartvoice.data.DictionaryStore
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.data.ProfileStore

@Composable
fun ProfilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val profileStore = remember { ProfileStore(context) }
    val dictionaryStore = remember { DictionaryStore(context) }

    var profiles by remember { mutableStateOf<List<CleaningProfile>>(profileStore.allProfiles()) }
    var activeProfileId by remember { mutableStateOf(profileStore.activeProfile().id) }
    var dictionaryEntries by remember { mutableStateOf(dictionaryStore.getEntries()) }

    var showNewProfileDialog by remember { mutableStateOf(false) }
    var newWordInput by remember { mutableStateOf("") }
    var newAliasesInput by remember { mutableStateOf("") }

    fun refresh() {
        profiles = profileStore.allProfiles()
        activeProfileId = profileStore.activeProfile().id
        dictionaryEntries = dictionaryStore.getEntries()
    }

    if (showNewProfileDialog) {
        NewProfileDialog(
            onDismiss = { showNewProfileDialog = false },
            onSave = { name, desc, rules ->
                val id = "custom_" + System.currentTimeMillis()
                profileStore.saveCustomProfile(CleaningProfile(id = id, name = name, description = desc, rules = rules, isBuiltin = false))
                showNewProfileDialog = false
                refresh()
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- SECTION: CLEANING PROFILES ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cleaning Profiles",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Select or customize the LLM cleanup persona and formatting rules.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { showNewProfileDialog = true },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New")
                }
            }
        }

        items(profiles, key = { it.id }) { profile ->
            val isSelected = profile.id == activeProfileId
            Card(
                onClick = {
                    profileStore.setActiveProfile(profile.id)
                    refresh()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!profile.isBuiltin) {
                            IconButton(
                                onClick = {
                                    profileStore.deleteCustomProfile(profile.id)
                                    refresh()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Profile",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Active LLM Prompt Rules:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = profile.rules,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- SECTION: PERSONAL DICTIONARY & SPELLING CORRECTIONS ---
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Column {
                Text(
                    text = "Personal Dictionary & Spelling Rules",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Add target words alongside common speech-recognition misspellings. The cleanup model will automatically replace recognized errors with your specified term.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = newWordInput,
                        onValueChange = { newWordInput = it },
                        label = { Text("Target Word / Term (e.g. Parakeet)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newAliasesInput,
                        onValueChange = { newAliasesInput = it },
                        label = { Text("Common Misspellings / Variants (optional, comma-separated)") },
                        placeholder = { Text("e.g. parakit, parakeete") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newWordInput.isNotBlank()) {
                                val aliases = newAliasesInput.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                dictionaryStore.addEntry(newWordInput.trim(), aliases)
                                newWordInput = ""
                                newAliasesInput = ""
                                refresh()
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = {
                                if (newWordInput.isNotBlank()) {
                                    val aliases = newAliasesInput.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                    dictionaryStore.addEntry(newWordInput.trim(), aliases)
                                    newWordInput = ""
                                    newAliasesInput = ""
                                    refresh()
                                }
                            },
                            enabled = newWordInput.isNotBlank(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add to Dictionary")
                        }
                    }
                }
            }
        }

        item {
            if (dictionaryEntries.isEmpty()) {
                Text(
                    text = "No words added yet. Add project names, technical terms, or misspellings above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dictionaryEntries.forEach { entry ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.word,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (entry.aliases.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Replaces misspellings: ${entry.aliases.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        dictionaryStore.removeEntry(entry.word)
                                        refresh()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove ${entry.word}",
                                        tint = MaterialTheme.colorScheme.error,
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
private fun NewProfileDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, rules: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(CleanupPrompt.Variant.STANDARD.rules) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Cleaning Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    placeholder = { Text("e.g. Email / Formal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short Description") },
                    placeholder = { Text("e.g. Formal sentences without contractions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it },
                    label = { Text("LLM Prompt Rules (prefilled with Standard template)") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), description.trim(), rules.trim()) },
                enabled = name.isNotBlank() && rules.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
