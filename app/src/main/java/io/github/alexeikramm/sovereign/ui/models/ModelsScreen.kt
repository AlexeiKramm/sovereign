package io.github.alexeikramm.sovereign.ui.models

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModelType
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModelSpec
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
import io.github.alexeikramm.sovereign.theme.BadgeBlue
import io.github.alexeikramm.sovereign.theme.BadgeGreen
import io.github.alexeikramm.sovereign.theme.BadgeOrange
import io.github.alexeikramm.sovereign.theme.BadgeViolet
import io.github.alexeikramm.sovereign.ui.DictationViewModel
import io.github.alexeikramm.sovereign.ui.ModelStatus
import java.util.Locale

/**
 * Tab 2: Models Screen matching SPEC.md Section 3.2:
 * - Header with "Models" title, subtitle, and [⚡ Benchmark] pill trigger.
 * - Storage Summary Card: Segmented bar showing Speech ASR vs LLM Cleanup disk usage.
 * - TRANSCRIPTION MODELS: 3 practical tiers (Fast & Streaming, Lightweight, Highest Accuracy)
 *   with standardized Latency · Quality · RAM attribute pills, active indicators, and [ⓘ] specs.
 * - LLM CLEANUP MODELS: LiteRT-LM tiers (Good Polish / Gemma 4 E2B) with hardware attribution.
 * - Model Technical Specifications dialog for detailed technical architectures and trade-offs.
 */
@Composable
fun ModelsScreen(
    viewModel: DictationViewModel,
    modifier: Modifier = Modifier,
    onOpenBenchmark: () -> Unit = {},
    onOpenStreamingBenchmark: () -> Unit = {},
) {
    val context = LocalContext.current
    val loadProbe by viewModel.loadProbe.collectAsStateWithLifecycle()
    val asrStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val selectedAsrModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()

    val cleanupLoadNote by viewModel.cleanupLoad.collectAsStateWithLifecycle()
    val cleanupBusy by viewModel.cleanupBusy.collectAsStateWithLifecycle()

    val litertCatalog = viewModel.litertCatalog
    val selectedLitertModel by viewModel.selectedLitertModel.collectAsStateWithLifecycle()
    val litertInstalled by viewModel.litertInstalled.collectAsStateWithLifecycle()
    val litertDownloads by viewModel.litertDownloads.collectAsStateWithLifecycle()
    val asrDownloads by viewModel.asrDownloads.collectAsStateWithLifecycle()

    // Confirmation dialog states
    var pendingAsrDownload by remember { mutableStateOf<AsrModelSpec?>(null) }
    var pendingLitertDownload by remember { mutableStateOf<LiteRtLmModelSpec?>(null) }
    var pendingAsrDelete by remember { mutableStateOf<AsrModelSpec?>(null) }
    var pendingLitertDelete by remember { mutableStateOf<LiteRtLmModelSpec?>(null) }

    // Technical specifications modal state
    var selectedInfoKey by remember { mutableStateOf<String?>(null) }

    // Notifications permission handling for background downloads
    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    var notifyDenied by remember { mutableStateOf(false) }
    var pendingDownloadAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted
        notifyDenied = !granted
        pendingDownloadAfterPermission?.invoke()
        pendingDownloadAfterPermission = null
    }

    val withNotificationPermission: (() -> Unit) -> Unit = { enqueue ->
        if (notificationsAllowed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            enqueue()
        } else {
            pendingDownloadAfterPermission = enqueue
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val startAsrDownload: (AsrModelSpec, Boolean) -> Unit = { target, allowMetered ->
        viewModel.selectAsrModel(target)
        withNotificationPermission {
            viewModel.downloadModel(target, allowMetered = allowMetered)
        }
    }

    val startLitertDownload: (LiteRtLmModelSpec, Boolean) -> Unit = { target, allowMetered ->
        viewModel.selectLitertModel(target)
        withNotificationPermission {
            viewModel.downloadLitertModel(target, allowMetered = allowMetered)
        }
    }

    // Storage computations
    val asrInstalledBytes = viewModel.asrCatalog
        .filter { it.isInstalled(it.directory(context.filesDir)) }
        .sumOf { it.totalBytes }
    val litertInstalledBytes = litertCatalog
        .filter { litertInstalled[it.id] == true }
        .sumOf { it.approximateBytes }
    val totalInstalledBytes = asrInstalledBytes + litertInstalledBytes
    val totalInstalledMb = totalInstalledBytes / 1_000_000
    val asrInstalledMb = asrInstalledBytes / 1_000_000
    val litertInstalledMb = litertInstalledBytes / 1_000_000

    val storageSummaryText = if (totalInstalledMb >= 1000) {
        String.format(Locale.US, "%.2f GB used", totalInstalledMb / 1000.0)
    } else {
        "$totalInstalledMb MB used"
    }

    val litertSummaryText = if (litertInstalledMb >= 1000) {
        String.format(Locale.US, "%.1f GB", litertInstalledMb / 1000.0)
    } else {
        "$litertInstalledMb MB"
    }

    val fractionAsr = if (totalInstalledBytes > 0L) asrInstalledBytes.toFloat() / totalInstalledBytes else 0f
    val fractionLlm = if (totalInstalledBytes > 0L) litertInstalledBytes.toFloat() / totalInstalledBytes else 0f

    // Confirmation Dialogs
    pendingAsrDownload?.let { model ->
        var allowMobileData by remember(model) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingAsrDownload = null },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Download voice model?") },
            text = {
                Column {
                    Text(
                        "“${model.label}” is not currently downloaded on this device (~${model.totalBytes / 1_000_000} MB).\n\nWould you like to download and activate it now?",
                    )
                    if (notifyDenied) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Notifications are off, so progress will not be visible if you leave the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowMobileData, onCheckedChange = { allowMobileData = it })
                        Text("Also allow mobile data", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Downloads prefer Wi-Fi; ticking this starts immediately on cellular.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        val metered = allowMobileData
                        pendingAsrDownload = null
                        startAsrDownload(target, metered)
                    },
                ) {
                    Text("Download & activate")
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
        var allowMobileData by remember(model) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingLitertDownload = null },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Download AI cleanup model?") },
            text = {
                Column {
                    Text(
                        "“${model.label}” is not currently downloaded on this device (~${model.approximateBytes / 1_000_000} MB).\n\nWould you like to download and activate it now?",
                    )
                    if (notifyDenied) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Notifications are off, so progress will not be visible if you leave the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowMobileData, onCheckedChange = { allowMobileData = it })
                        Text("Also allow mobile data", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Downloads prefer Wi-Fi; ticking this starts immediately on cellular.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        val metered = allowMobileData
                        pendingLitertDownload = null
                        startLitertDownload(target, metered)
                    },
                ) {
                    Text("Download & activate")
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
            title = { Text("Delete voice model?") },
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
            title = { Text("Delete AI model?") },
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

    // Model Technical Specifications Dialog
    if (selectedInfoKey != null) {
        val spec = getModelTechnicalSpec(selectedInfoKey!!)
        if (spec != null) {
            AlertDialog(
                onDismissRequest = { selectedInfoKey = null },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "🧠", fontSize = 20.sp)
                        Column {
                            Text(
                                text = spec.title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = spec.subtitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = spec.overview,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "KEY ADVANTAGES",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            spec.benefits.forEach { benefit ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = "✓",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = benefit,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "TRADE-OFFS & CONSIDERATIONS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = BadgeOrange,
                            )
                            spec.tradeoffs.forEach { tradeoff ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = "•",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BadgeOrange,
                                    )
                                    Text(
                                        text = tradeoff,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { selectedInfoKey = null },
                        shape = CircleShape,
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                },
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // --- SCREEN HEADER WITH TITLE & BENCHMARK TRIGGER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Models",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = (-0.5).sp,
                )
                Text(
                    text = "On-device ML models for speech & cleanup",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onOpenBenchmark,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "⚡", fontSize = 12.sp)
                        Text(
                            text = "Benchmark",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Surface(
                    onClick = onOpenStreamingBenchmark,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "🎙", fontSize = 12.sp)
                        Text(
                            text = "Streaming",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        // --- STORAGE SUMMARY CARD ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "On-device storage",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = storageSummaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Segmented Progress Bar (8.dp height)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    if (totalInstalledBytes > 0L) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (fractionAsr > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(fractionAsr.coerceAtLeast(0.02f))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                            if (fractionLlm > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(fractionLlm.coerceAtLeast(0.02f))
                                        .background(BadgeViolet),
                                )
                            }
                        }
                    }
                }

                // Legend Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            text = "Speech ASR ($asrInstalledMb MB)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(BadgeViolet),
                        )
                        Text(
                            text = "LLM cleanup ($litertSummaryText)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // --- SECTION 1: TRANSCRIPTION MODELS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TRANSCRIPTION MODELS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Tap to activate",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        viewModel.asrCatalog.forEach { model ->
            val isSelected = model.id == selectedAsrModel.id
            val isInstalled = model.isInstalled(model.directory(context.filesDir))
            val downloadState = asrDownloads[model.id]

            AsrTierCard(
                model = model,
                isSelected = isSelected,
                isInstalled = isInstalled,
                downloadStatus = downloadState,
                notificationsAllowed = notificationsAllowed,
                loadProbe = if (isSelected) loadProbe else null,
                onSelect = { viewModel.selectAsrModel(model) },
                onDownload = { pendingAsrDownload = model },
                onCancelDownload = { viewModel.cancelModelDownload() },
                onAllowMobileData = { viewModel.allowDownloadOverMobileData() },
                onDelete = { pendingAsrDelete = model },
                onVerify = { viewModel.probeLocalEngine() },
                onOpenInfo = { key -> selectedInfoKey = key },
            )
        }

        // --- SECTION 2: LLM CLEANUP MODELS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LLM CLEANUP MODELS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Tap to activate",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        litertCatalog.forEach { model ->
            val isSelected = selectedLitertModel.id == model.id
            val isInstalled = litertInstalled[model.id] == true

            LitertTierCard(
                model = model,
                isSelected = isSelected,
                isInstalled = isInstalled,
                downloadStatus = litertDownloads[model.id],
                notificationsAllowed = notificationsAllowed,
                cleanupBusy = if (isSelected) cleanupBusy else null,
                cleanupLoadNote = if (isSelected) cleanupLoadNote else null,
                onSelect = { viewModel.selectLitertModel(model) },
                onDownload = { pendingLitertDownload = model },
                onCancelDownload = { viewModel.cancelModelDownload() },
                onAllowMobileData = { viewModel.allowDownloadOverMobileData() },
                onDelete = { pendingLitertDelete = model },
                onVerify = { viewModel.loadCleanupModel() },
                onOpenInfo = { key -> selectedInfoKey = key },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// -------------------------------------------------------------------------------------------------
// ASR TIER CARD COMPONENT
// -------------------------------------------------------------------------------------------------

@Composable
private fun AsrTierCard(
    model: AsrModelSpec,
    isSelected: Boolean,
    isInstalled: Boolean,
    downloadStatus: ModelStatus.Downloading?,
    notificationsAllowed: Boolean,
    loadProbe: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onAllowMobileData: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
    onOpenInfo: (String) -> Unit,
) {
    // Model metadata resolution
    val (tierTitle, badgeText, badgeBg, badgeTextColor, description, latencyVal, qualityVal, ramVal, infoKey) = when (model.id) {
        AsrModels.DEFAULT_MODEL_ID -> ModelMetadata(
            title = "Lowest Latency",
            badge = "Recommended",
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            badgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
            description = "Transcribes speech in real time as you speak. Lowest latency · Good quality · High RAM requirement.",
            latency = "Real-time",
            quality = "Good",
            ram = "High (${model.totalBytes / 1_000_000} MB)",
            infoKey = "nemotron",
        )
        AsrModels.NEMOTRON_320MS.id -> ModelMetadata(
            title = "Ultra-Low Latency",
            badge = "Experimental",
            badgeBg = MaterialTheme.colorScheme.tertiaryContainer,
            badgeColor = MaterialTheme.colorScheme.onTertiaryContainer,
            description = "Transcribes speech in real time using 320 ms audio frames. Starts showing text sooner, but can be less accurate than the default streaming model. High RAM requirement.",
            latency = "Real-time",
            quality = "Good",
            ram = "High (${model.totalBytes / 1_000_000} MB)",
            infoKey = "nemotron-320",
        )
        AsrModels.WHISPER_BASE.id -> ModelMetadata(
            title = "Lightweight",
            badge = null,
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            badgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
            description = "Transcribes speech in audio chunks after you pause. High latency · Medium quality · Lowest RAM requirement.",
            latency = "Chunked",
            quality = "Standard",
            ram = "Low (${model.totalBytes / 1_000_000} MB)",
            infoKey = "whisper",
        )
        AsrModels.PARAKEET_TDT_V3.id -> ModelMetadata(
            title = "Highest Accuracy",
            badge = null,
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            badgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
            description = "Transcribes speech in audio chunks after you pause. Medium latency · Best quality · High RAM requirement.",
            latency = "Chunked",
            quality = "Highest",
            ram = "Medium (${model.totalBytes / 1_000_000} MB)",
            infoKey = "parakeet",
        )
        else -> ModelMetadata(
            title = model.label,
            badge = null,
            badgeBg = MaterialTheme.colorScheme.primaryContainer,
            badgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
            description = model.description,
            latency = if (model.type == AsrModelType.STREAMING_TRANSDUCER) "Real-time" else "Chunked",
            quality = "Standard",
            ram = "${model.totalBytes / 1_000_000} MB",
            infoKey = model.id,
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = downloadStatus == null) {
                if (isInstalled) {
                    onSelect()
                } else {
                    onDownload()
                }
            },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row: Title, Badges, and Info [ⓘ] button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tierTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (!badgeText.isNullOrEmpty()) {
                        Surface(
                            color = badgeBg,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeTextColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (isSelected && isInstalled) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = "Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isInstalled && downloadStatus == null) {
                        Surface(
                            onClick = onDelete,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { onOpenInfo(infoKey) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "ⓘ",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Subtext Description
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Action / Download / Diagnostics Area
            if (downloadStatus != null) {
                DownloadProgressBlock(
                    downloadStatus = downloadStatus,
                    notificationsAllowed = notificationsAllowed,
                    tintColor = MaterialTheme.colorScheme.primary,
                    onCancelDownload = onCancelDownload,
                    onAllowMobileData = onAllowMobileData,
                )
            } else if (!isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Not downloaded (~${model.totalBytes / 1_000_000} MB)",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onDownload,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else if (isSelected && !loadProbe.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = loadProbe,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// LLM TIER CARD COMPONENT
// -------------------------------------------------------------------------------------------------

@Composable
private fun LitertTierCard(
    model: LiteRtLmModelSpec,
    isSelected: Boolean,
    isInstalled: Boolean,
    downloadStatus: ModelStatus.Downloading?,
    notificationsAllowed: Boolean,
    cleanupBusy: String?,
    cleanupLoadNote: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onAllowMobileData: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
    onOpenInfo: (String) -> Unit,
) {
    val isGemma4 = model.id == LiteRtLmModels.GEMMA_4_E2B.id
    val title = if (isGemma4) "Good Polish" else model.label
    val badgeText = if (isGemma4) "Recommended" else null
    val description = if (isGemma4) {
        "High precision punctuation, capitalization, and filler removal. Fast GPU generation · High RAM requirement."
    } else {
        model.note
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = downloadStatus == null) {
                if (isInstalled) {
                    onSelect()
                } else {
                    onDownload()
                }
            },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row: Title, Badges, and Info [ⓘ] button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (!badgeText.isNullOrEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (isSelected && isInstalled) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = "Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isInstalled && downloadStatus == null) {
                        Surface(
                            onClick = onDelete,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { onOpenInfo(if (isGemma4) "gemma" else model.id) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "ⓘ",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Subtext Description
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Action / Download / Diagnostics Area
            if (downloadStatus != null) {
                DownloadProgressBlock(
                    downloadStatus = downloadStatus,
                    notificationsAllowed = notificationsAllowed,
                    tintColor = MaterialTheme.colorScheme.primary,
                    onCancelDownload = onCancelDownload,
                    onAllowMobileData = onAllowMobileData,
                )
            } else if (!isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Not downloaded (~${model.approximateBytes / 1_000_000} MB)",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onDownload,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else if (isSelected) {
                cleanupBusy?.let { busy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = busy,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                cleanupLoadNote?.let { note ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = note,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// SHARED HELPER COMPONENTS
// -------------------------------------------------------------------------------------------------


@Composable
private fun DownloadProgressBlock(
    downloadStatus: ModelStatus.Downloading,
    notificationsAllowed: Boolean,
    tintColor: Color,
    onCancelDownload: () -> Unit,
    onAllowMobileData: () -> Unit,
) {
    val progress = downloadStatus.progress
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = when {
                downloadStatus.waitingForWifi -> "Waiting for Wi-Fi"
                downloadStatus.queued -> "Queued — another download is running"
                progress != null -> "Downloading: ${progress.fileName} (${progress.fileIndex + 1}/${progress.totalFiles})"
                else -> "Starting…"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = { progress?.overall ?: 0f },
            color = tintColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
        )
        if (!notificationsAllowed) {
            Text(
                text = "Notifications are off — progress will not be visible while you are away.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (progress != null) "${(progress.overall * 100).toInt()}% completed" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (downloadStatus.waitingForWifi) {
                    OutlinedButton(
                        onClick = onAllowMobileData,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Use mobile data", fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = onCancelDownload,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Cancel", fontSize = 11.sp)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// MODEL TECHNICAL SPECIFICATIONS
// -------------------------------------------------------------------------------------------------

private data class ModelMetadata(
    val title: String,
    val badge: String? = null,
    val badgeBg: Color,
    val badgeColor: Color,
    val description: String,
    val latency: String,
    val quality: String,
    val ram: String,
    val infoKey: String,
)

private data class ModelTechnicalSpec(
    val title: String,
    val subtitle: String,
    val overview: String,
    val benefits: List<String>,
    val tradeoffs: List<String>,
)

private fun getModelTechnicalSpec(key: String): ModelTechnicalSpec? = when (key) {
    "nemotron" -> ModelTechnicalSpec(
        title = "Lowest Latency",
        subtitle = "Nvidia Nemotron-3.5-ASR-Streaming-0.6B (CPU)",
        overview = "Uses Nvidia's Nemotron-3.5-ASR-Streaming-0.6B (560ms) model under the hood, run on CPU using the sherpa-onnx backend. The model supports 40 different languages.",
        benefits = listOf(
            "Streaming ASR: transcribes speech in real time as you speak, drastically reducing end-to-end latency.",
            "Immediate LLM handoff: by the time you stop speaking, raw transcription is already complete and sent for cleanup instantly.",
            "Broad multilingual recognition across 40 languages.",
        ),
        tradeoffs = listOf(
            "Relatively large model footprint requiring high RAM.",
            "Slower initial load time: larger model weights take slightly longer to initialize into memory.",
            "Lower precision on noisy audio or dense domain jargon compared to the Highest Accuracy model.",
        ),
    )
    "nemotron-320" -> ModelTechnicalSpec(
        title = "Ultra-Low Latency",
        subtitle = "Nvidia Nemotron-3.5-ASR-Streaming-0.6B 320 ms (CPU)",
        overview = "The same Nvidia Nemotron-3.5 streaming model as the default, using shorter 320 ms audio frames so partial transcription can update more frequently. The default 560 ms frames are processed in larger batches and often finish long dictations sooner.",
        benefits = listOf(
            "Finer-grained streaming: text can update more often while you speak.",
            "Same architecture, engine and 40-language coverage as the default streaming model.",
        ),
        tradeoffs = listOf(
            "Slightly lower recognition accuracy per frame than the default streaming model.",
            "More frequent decode steps; on longer audio the default 560 ms model can still finish sooner.",
            "High RAM requirement comparable to the default streaming model.",
        ),
    )
    "whisper" -> ModelTechnicalSpec(
        title = "Lightweight",
        subtitle = "OpenAI Whisper Base (CPU)",
        overview = "Uses an optimized OpenAI Whisper Base model run on CPU via the sherpa-onnx backend. Operates in non-streaming chunked mode, decoding audio after natural pauses.",
        benefits = listOf(
            "Quickest load time: initializes the fastest into memory due to its compact model size.",
            "Lowest RAM requirement: ideal for older devices or keeping memory footprint minimal.",
            "Solid baseline punctuation and accuracy for everyday English.",
        ),
        tradeoffs = listOf(
            "Highest perceived latency out of the available transcription models.",
            "Chunked processing: speech is only decoded after you pause, adding a slight post-speech processing delay.",
        ),
    )
    "parakeet" -> ModelTechnicalSpec(
        title = "Highest Accuracy",
        subtitle = "Nvidia Parakeet TDT v3 (CPU)",
        overview = "Uses Nvidia Parakeet TDT v3 run on CPU via sherpa-onnx. Decodes audio chunks with state-of-the-art acoustic accuracy.",
        benefits = listOf(
            "Highest transcription precision: excels at complex vocabulary, technical terms, and proper nouns.",
            "Strong noise resilience in challenging acoustic environments.",
            "Fast transducer decoding within each captured audio chunk.",
        ),
        tradeoffs = listOf(
            "Chunked processing: transcription starts after you pause, resulting in higher perceived latency than streaming.",
            "Slower initial load time: larger model weights take slightly longer to initialize into memory.",
            "High RAM requirement comparable to the streaming model.",
        ),
    )
    "gemma" -> ModelTechnicalSpec(
        title = "Good Polish",
        subtitle = "Google Gemma 4 E2B (OpenCL GPU)",
        overview = "Uses Google's Gemma 4 E2B (2 billion parameters) executed on-device via Google LiteRT-LM, hardware-accelerated on your phone's GPU using OpenCL.",
        benefits = listOf(
            "Natural grammar restoration: fixes punctuation, strips filler words (um, uh), and repairs hesitations without changing your meaning.",
            "Hardware-accelerated token generation using OpenCL GPU.",
            "Strictly 100% on-device: zero audio or text data ever leaves your phone.",
        ),
        tradeoffs = listOf(
            "High RAM footprint while resident in memory.",
            "Requires modern GPU hardware for fast token generation.",
        ),
    )
    else -> null
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
