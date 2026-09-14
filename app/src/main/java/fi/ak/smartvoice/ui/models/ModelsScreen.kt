package fi.ak.smartvoice.ui.models

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModelType
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.theme.BadgeBlue
import fi.ak.smartvoice.theme.BadgeGreen
import fi.ak.smartvoice.theme.BadgeViolet
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ModelStatus
import fi.ak.smartvoice.ui.components.SettingsIconBadge

@Composable
fun ModelsScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
) {
    val asrStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val loadProbe by viewModel.loadProbe.collectAsStateWithLifecycle()
    val selectedAsrModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val cleanupLoadNote by viewModel.cleanupLoad.collectAsStateWithLifecycle()
    val cleanupBusy by viewModel.cleanupBusy.collectAsStateWithLifecycle()

    val litertCatalog = viewModel.litertCatalog
    val selectedLitertModel by viewModel.selectedLitertModel.collectAsStateWithLifecycle()
    val litertInstalled by viewModel.litertInstalled.collectAsStateWithLifecycle()
    val litertPulling by viewModel.litertPulling.collectAsStateWithLifecycle()
    val litertProgress by viewModel.litertProgress.collectAsStateWithLifecycle()

    // Confirmation dialog states
    var pendingAsrDownload by remember { mutableStateOf<AsrModelSpec?>(null) }
    var pendingLitertDownload by remember { mutableStateOf<LiteRtLmModelSpec?>(null) }
    var pendingAsrDelete by remember { mutableStateOf<AsrModelSpec?>(null) }
    var pendingLitertDelete by remember { mutableStateOf<LiteRtLmModelSpec?>(null) }

    // Calculate total disk storage used
    val asrInstalledBytes = viewModel.asrCatalog
        .filter { it.isInstalled(it.directory(context.filesDir)) }
        .sumOf { it.totalBytes }
    val litertInstalledBytes = litertCatalog
        .filter { litertInstalled[it.id] == true }
        .sumOf { it.approximateBytes }
    val totalInstalledMb = (asrInstalledBytes + litertInstalledBytes) / 1_000_000
    val storageSummary = if (totalInstalledMb >= 1000) {
        "%.2f GB on disk".format(totalInstalledMb / 1000.0)
    } else {
        "$totalInstalledMb MB on disk"
    }

    val isSelectedAsrInstalled = selectedAsrModel.isInstalled(selectedAsrModel.directory(context.filesDir))
    val isSelectedLitertInstalled = litertInstalled[selectedLitertModel.id] == true

    // Confirmation Dialogs
    pendingAsrDownload?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingAsrDownload = null },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Download Voice Model?") },
            text = {
                Text(
                    "“${model.label}” is not currently downloaded on this device (~${model.totalBytes / 1_000_000} MB).\n\nWould you like to download and activate it now?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        pendingAsrDownload = null
                        viewModel.selectAsrModel(target)
                        viewModel.downloadModel(target)
                    },
                ) {
                    Text("Download & Activate")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingAsrDownload = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingLitertDownload?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingLitertDownload = null },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = BadgeViolet) },
            title = { Text("Download AI Cleanup Model?") },
            text = {
                Text(
                    "“${model.label}” is not currently downloaded on this device (~${model.approximateBytes / 1_000_000} MB).\n\nWould you like to download and activate it now?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        pendingLitertDownload = null
                        viewModel.selectLitertModel(target)
                        viewModel.downloadLitertModel(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BadgeViolet),
                ) {
                    Text("Download & Activate")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingLitertDownload = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingAsrDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingAsrDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Voice Model?") },
            text = {
                Text("Delete “${model.label}” files from device storage? This frees ~${model.totalBytes / 1_000_000} MB.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        pendingAsrDelete = null
                        viewModel.deleteModel(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingAsrDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingLitertDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingLitertDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete AI Model?") },
            text = {
                Text("Delete “${model.label}” from device storage? This frees ~${model.approximateBytes / 1_000_000} MB.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        pendingLitertDelete = null
                        viewModel.deleteLitertModel(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingLitertDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- SCREEN TITLE & REFRESH ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "On-Device Models",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Select active models and manage local storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.refreshLitertInstalled() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh models",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // --- SECTION 1: ACTIVE PIPELINE SELECTION (TOP DROPDOWNS) ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Active Pipeline Selection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Surface(
                            color = if (isSelectedAsrInstalled && isSelectedLitertInstalled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = if (isSelectedAsrInstalled && isSelectedLitertInstalled) "Ready" else "Download Needed",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelectedAsrInstalled && isSelectedLitertInstalled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    // 1. Voice Recognition (ASR) Dropdown
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Default.Mic,
                                badgeColor = BadgeBlue,
                                size = 28.dp,
                                iconSize = 16.dp,
                                contentDescription = "Voice Model",
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Speech Recognition (Voice)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        AsrDropdownSelector(
                            catalog = viewModel.asrCatalog,
                            selectedModel = selectedAsrModel,
                            onModelSelected = { model, isInstalled ->
                                if (isInstalled) {
                                    viewModel.selectAsrModel(model)
                                } else {
                                    pendingAsrDownload = model
                                }
                            },
                        )

                        // Voice Diagnostics / Action row
                        if (isSelectedAsrInstalled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.probeLocalEngine() },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text("Verify Model", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (!loadProbe.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = loadProbe!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Selected model not on device (~${selectedAsrModel.totalBytes / 1_000_000} MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(
                                    onClick = { viewModel.downloadModel(selectedAsrModel) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                ) {
                                    Text("Download", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 1.dp,
                    )

                    // 2. AI Cleanup (LLM) Dropdown
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(
                                icon = Icons.Default.AutoAwesome,
                                badgeColor = BadgeViolet,
                                size = 28.dp,
                                iconSize = 16.dp,
                                contentDescription = "AI Cleanup Model",
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "AI Speech Cleanup (LLM)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        LitertDropdownSelector(
                            catalog = litertCatalog,
                            selectedModel = selectedLitertModel,
                            installedMap = litertInstalled,
                            onModelSelected = { model, isInstalled ->
                                if (isInstalled) {
                                    viewModel.selectLitertModel(model)
                                } else {
                                    pendingLitertDownload = model
                                }
                            },
                        )

                        // LLM Diagnostics / Action row
                        if (isSelectedLitertInstalled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.loadCleanupModel() },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text("Verify Model", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            cleanupBusy?.let { busy ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = BadgeViolet,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = busy,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            cleanupLoadNote?.let { note ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Selected model not on device (~${selectedLitertModel.approximateBytes / 1_000_000} MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(
                                    onClick = { viewModel.downloadLitertModel(selectedLitertModel) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BadgeViolet),
                                ) {
                                    Text("Download", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- SECTION 2: MODEL STORAGE & DOWNLOADS (LIBRARY) ---
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Model Library & Storage",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Manage downloaded files or install new models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = storageSummary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // --- ASR MODELS SECTION HEADER ---
        item {
            Text(
                text = "Speech Recognition Models (${viewModel.asrCatalog.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ASR MODEL ITEMS
        items(viewModel.asrCatalog, key = { "asr_${it.id}" }) { model ->
            val isSelected = model.id == selectedAsrModel.id
            val isInstalled = model.isInstalled(model.directory(context.filesDir))
            val isDownloadingThis = (asrStatus as? ModelStatus.Downloading)?.let {
                model.incomplete(model.directory(context.filesDir)).isNotEmpty() && isSelected
            } ?: false

            AsrModelLibraryItem(
                model = model,
                isSelected = isSelected,
                isInstalled = isInstalled,
                isDownloading = isDownloadingThis,
                downloadStatus = asrStatus as? ModelStatus.Downloading,
                onSelect = { viewModel.selectAsrModel(model) },
                onDownload = {
                    viewModel.selectAsrModel(model)
                    viewModel.downloadModel(model)
                },
                onCancelDownload = { viewModel.cancelModelDownload() },
                onDelete = { pendingAsrDelete = model },
            )
        }

        // --- LLM MODELS SECTION HEADER ---
        item {
            Text(
                text = "AI Cleanup Models (${litertCatalog.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BadgeViolet,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // LLM MODEL ITEMS
        items(litertCatalog, key = { "litert_${it.id}" }) { model ->
            val isSelected = selectedLitertModel.id == model.id
            val isInstalled = litertInstalled[model.id] == true
            val isPulling = litertPulling == model.id

            LitertModelLibraryItem(
                model = model,
                isSelected = isSelected,
                isInstalled = isInstalled,
                isDownloading = isPulling,
                progress = if (isPulling) litertProgress else null,
                onSelect = { viewModel.selectLitertModel(model) },
                onDownload = {
                    viewModel.selectLitertModel(model)
                    viewModel.downloadLitertModel(model)
                },
                onCancelDownload = { viewModel.cancelLitertDownload() },
                onDelete = { pendingLitertDelete = model },
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// DROPDOWN SELECTORS (TOP OF SCREEN)
// -------------------------------------------------------------------------------------------------

@Composable
private fun AsrDropdownSelector(
    catalog: List<AsrModelSpec>,
    selectedModel: AsrModelSpec,
    onModelSelected: (AsrModelSpec, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val context = LocalContext.current

    val isSelectedInstalled = selectedModel.isInstalled(selectedModel.directory(context.filesDir))

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedCard(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    cardWidthDp = with(density) { coordinates.size.width.toDp() }
                },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedModel.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isSelectedInstalled) {
                            val typeLabel = when (selectedModel.type) {
                                AsrModelType.STREAMING_TRANSDUCER -> "Streaming"
                                AsrModelType.OFFLINE_WHISPER -> "Offline Whisper"
                                AsrModelType.OFFLINE_TRANSDUCER -> "Offline Transducer"
                                AsrModelType.OFFLINE_CTC -> "Offline CTC"
                            }
                            "$typeLabel • ${selectedModel.totalBytes / 1_000_000} MB • Active on device"
                        } else {
                            "⚠️ Not downloaded (~${selectedModel.totalBytes / 1_000_000} MB)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelectedInstalled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select ASR model",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(if (cardWidthDp > 0.dp) cardWidthDp else 300.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            catalog.forEach { model ->
                val isInstalled = model.isInstalled(model.directory(context.filesDir))
                val isSelected = model.id == selectedModel.id

                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isInstalled) {
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    },
                                )
                                Text(
                                    text = "${model.description} • ${model.totalBytes / 1_000_000} MB" + if (!isInstalled) " (Not downloaded)" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isInstalled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    },
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else if (!isInstalled) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = "Cloud",
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onModelSelected(model, isInstalled)
                    },
                )
            }
        }
    }
}

@Composable
private fun LitertDropdownSelector(
    catalog: List<LiteRtLmModelSpec>,
    selectedModel: LiteRtLmModelSpec,
    installedMap: Map<String, Boolean>,
    onModelSelected: (LiteRtLmModelSpec, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var cardWidthDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val isSelectedInstalled = installedMap[selectedModel.id] == true

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedCard(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    cardWidthDp = with(density) { coordinates.size.width.toDp() }
                },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedModel.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isSelectedInstalled) {
                            "${selectedModel.targetHardware} • ${selectedModel.approximateBytes / 1_000_000} MB • Active"
                        } else {
                            "⚠️ Not downloaded (~${selectedModel.approximateBytes / 1_000_000} MB)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelectedInstalled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select LiteRT model",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(if (cardWidthDp > 0.dp) cardWidthDp else 300.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            catalog.forEach { model ->
                val isInstalled = installedMap[model.id] == true
                val isSelected = model.id == selectedModel.id

                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isInstalled) {
                                        if (isSelected) BadgeViolet else MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    },
                                )
                                Text(
                                    text = "${model.targetHardware} • ${model.approximateBytes / 1_000_000} MB" + if (!isInstalled) " (Not downloaded)" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isInstalled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    },
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = BadgeViolet,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else if (!isInstalled) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = "Cloud",
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onModelSelected(model, isInstalled)
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// MODEL STORAGE & LIBRARY ITEMS (BOTTOM OF SCREEN)
// -------------------------------------------------------------------------------------------------

@Composable
private fun AsrModelLibraryItem(
    model: AsrModelSpec,
    isSelected: Boolean,
    isInstalled: Boolean,
    isDownloading: Boolean,
    downloadStatus: ModelStatus.Downloading?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val chipLabel = when (model.type) {
        AsrModelType.STREAMING_TRANSDUCER -> "Streaming"
        AsrModelType.OFFLINE_WHISPER -> "Offline Whisper"
        AsrModelType.OFFLINE_TRANSDUCER -> "Offline Transducer"
        AsrModelType.OFFLINE_CTC -> "Offline CTC"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled) {
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = model.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = chipLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "${model.description} • ${model.totalBytes / 1_000_000} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isInstalled) 1.0f else 0.65f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isDownloading && downloadStatus != null) {
                val p = downloadStatus.progress
                Text(
                    text = "Downloading: ${p.fileName} (${p.fileIndex + 1}/${p.totalFiles})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { p.overall },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${(p.overall * 100).toInt()}% completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onCancelDownload,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isSelected) "Active" else "Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else BadgeGreen,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isSelected) {
                            OutlinedButton(
                                onClick = onSelect,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            ) {
                                Text("Select", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete model",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Not downloaded (~${model.totalBytes / 1_000_000} MB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LitertModelLibraryItem(
    model: LiteRtLmModelSpec,
    isSelected: Boolean,
    isInstalled: Boolean,
    isDownloading: Boolean,
    progress: fi.ak.smartvoice.cleanup.LiteRtLmDownloadProgress?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isSelected) BadgeViolet else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled) {
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = model.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = model.targetHardware,
                                style = MaterialTheme.typography.labelSmall,
                                color = BadgeViolet,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "${model.approximateBytes / 1_000_000} MB • ${model.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isInstalled) 1.0f else 0.65f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isDownloading && progress != null) {
                Text(
                    text = "Downloading: ${model.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.percent },
                    color = BadgeViolet,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${progress.downloadedBytes / 1_000_000} / ${progress.totalBytes / 1_000_000} MB (${(progress.percent * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onCancelDownload,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isSelected) "Active" else "Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) BadgeViolet else BadgeGreen,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isSelected) {
                            OutlinedButton(
                                onClick = onSelect,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BadgeViolet),
                            ) {
                                Text("Select", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = BadgeViolet)
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete model",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Not downloaded (~${model.approximateBytes / 1_000_000} MB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BadgeViolet),
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
