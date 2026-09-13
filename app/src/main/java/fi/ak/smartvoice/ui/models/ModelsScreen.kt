package fi.ak.smartvoice.ui.models

import fi.ak.smartvoice.asr.AsrModelType
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.ui.DictationViewModel
import fi.ak.smartvoice.ui.ModelStatus

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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                    )
                    Text(
                        text = "Speech recognition and LLM cleanup models on this phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.refreshLitertInstalled() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
                }
            }
        }

        // --- SECTION 1: ASR MODELS CATALOG ---
        item {
            Text(
                text = "1. Speech Recognition (ASR) Models",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        items(viewModel.asrCatalog, key = { it.id }) { model ->
            val isSelected = model.id == selectedAsrModel.id
            val isInstalled = model.isInstalled(model.directory(context.filesDir))
            val isDownloadingThis = (asrStatus as? ModelStatus.Downloading)?.let {
                // If downloading, check if it's currently downloading this model
                model.incomplete(model.directory(context.filesDir)).isNotEmpty() && isSelected
            } ?: false

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isInstalled) {
                            viewModel.selectAsrModel(model)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    if (isInstalled) {
                                        viewModel.selectAsrModel(model)
                                    }
                                },
                                enabled = isInstalled,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = model.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        val chipLabel = when (model.type) {
                            AsrModelType.STREAMING_TRANSDUCER -> "Streaming"
                            AsrModelType.OFFLINE_WHISPER -> "Offline Whisper"
                            AsrModelType.OFFLINE_TRANSDUCER -> "Offline Transducer"
                            AsrModelType.OFFLINE_CTC -> "Offline CTC"
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isDownloadingThis) {
                        val status = asrStatus as ModelStatus.Downloading
                        val p = status.progress
                        Text(
                            text = "Downloading: ${p.fileName} (${p.fileIndex + 1}/${p.totalFiles})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { p.overall },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(p.overall * 100).toInt()}% completed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.cancelModelDownload() }) {
                            Text("Cancel")
                        }
                    } else if (isInstalled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isSelected) "Active engine" else "Downloaded and verified",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!isSelected) {
                                    Button(onClick = { viewModel.selectAsrModel(model) }) {
                                        Text("Select")
                                    }
                                } else {
                                    OutlinedButton(onClick = { viewModel.probeLocalEngine() }) {
                                        Text("Test Load")
                                    }
                                }
                                OutlinedButton(onClick = { viewModel.deleteModel(model) }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (isSelected && !loadProbe.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = loadProbe!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Not installed (~${model.totalBytes / 1_000_000} MB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = {
                                viewModel.selectAsrModel(model)
                                viewModel.downloadModel(model)
                            }) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }

        // --- SECTION 2: LLM CLEANUP MODELS ---
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "2. LLM Cleanup Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "On-device LiteRT-LM models with mobile GPU acceleration (and CPU fallback):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Google LiteRT-LM models
        items(litertCatalog, key = { it.id }) { model ->
            val isSelected = selectedLitertModel.id == model.id
            val isInstalled = litertInstalled[model.id] == true
            val isPulling = litertPulling == model.id
            val progress = litertProgress

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectLitertModel(model) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.selectLitertModel(model) },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = model.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${model.approximateBytes / 1_000_000} MB • LiteRT-LM (.litertlm)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(model.targetHardware, style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = model.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isPulling && progress != null) {
                        Text(
                            text = "Downloading: ${model.fileName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress.percent },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
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
                            OutlinedButton(onClick = { viewModel.cancelLitertDownload() }) {
                                Text("Cancel")
                            }
                        }
                    } else if (isInstalled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isSelected) "Active LLM engine" else "Ready on disk",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!isSelected) {
                                    Button(onClick = { viewModel.selectLitertModel(model) }) {
                                        Text("Select")
                                    }
                                } else {
                                    OutlinedButton(onClick = { viewModel.loadCleanupModel() }) {
                                        Text("Test Load")
                                    }
                                }
                                OutlinedButton(onClick = { viewModel.deleteLitertModel(model) }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
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
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Button(onClick = {
                                viewModel.selectLitertModel(model)
                                viewModel.downloadLitertModel(model)
                            }) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }

        cleanupBusy?.let { busy ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = busy, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        cleanupLoadNote?.let { note ->
            item {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
