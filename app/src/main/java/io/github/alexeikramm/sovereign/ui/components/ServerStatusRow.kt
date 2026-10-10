package io.github.alexeikramm.sovereign.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeikramm.sovereign.server.ServerCountdown
import io.github.alexeikramm.sovereign.server.ServerLifecycle
import io.github.alexeikramm.sovereign.server.ServerSnapshot
import kotlinx.coroutines.delay

/**
 * The Home tab's only server control (D-136): one row inside the status card. Tapping the row opens
 * the Server route; the trailing [Switch] toggles the server. The value line shows the live state —
 * paired/connected counts and the exact auto-stop countdown.
 */
@Composable
fun ServerStatusRow(
    snapshot: ServerSnapshot,
    pairedCount: Int,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = snapshot.lifecycle != ServerLifecycle.STOPPED
    val busy = snapshot.lifecycle == ServerLifecycle.STARTING
    val failed = !snapshot.error.isNullOrBlank()

    // The countdown is minute-grained, so a coarse tick keeps it live without per-frame work; it is
    // only armed while a session actually has a deadline.
    var nowRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(snapshot.endsAtRealtimeMs) {
        if (snapshot.endsAtRealtimeMs == null) return@LaunchedEffect
        while (true) {
            nowRealtimeMs = SystemClock.elapsedRealtime()
            delay(COUNTDOWN_TICK_MS)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = if (running) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (running) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconBadge(
                icon = Icons.Default.Computer,
                // Inactive = the Voice-Model tint; active = the filled LLM-CLEANUP-on look (D-136).
                badgeColor = if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                iconTint = if (running) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                size = 30.dp,
                iconSize = 16.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SOVEREIGN SERVER",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = serverRowValue(snapshot, pairedCount, nowRealtimeMs),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ServerToggleChip(on = running, enabled = !busy, onToggle = onToggle)
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open Server",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The ON/OFF chip (layout A, D-136): the LLM-CLEANUP card's chip style, but this one *is* the
 * control. The row itself opens the Server screen, so the chip consumes its own tap; the padding
 * keeps it a comfortable target without growing the row.
 */
@Composable
private fun ServerToggleChip(on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (on) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        border = if (on) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        modifier = Modifier.clickable(enabled = enabled) { onToggle(!on) },
    ) {
        Text(
            text = if (on) "ON" else "OFF",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (on) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * The row's detail line (D-136). Pure, so it is pinned by `ServerStatusRowTest`:
 * - off → `Off · dictate on your PC`;
 * - serving → `Serving · <N> paired · <countdown>`, with `· <N> client` inserted **only** when ≥1
 *   client is connected (D-131 makes 0 the normal case);
 * - a manual session's countdown reads `until stopped`.
 */
internal fun serverRowValue(snapshot: ServerSnapshot, pairedCount: Int, nowRealtimeMs: Long): String {
    if (!snapshot.error.isNullOrBlank()) return snapshot.error.orEmpty()
    return when (snapshot.lifecycle) {
        ServerLifecycle.STOPPED -> "Off · dictate on your PC"
        ServerLifecycle.STARTING -> "Starting…"
        ServerLifecycle.RUNNING -> buildList {
            add("Serving")
            val connected = snapshot.clients.size
            if (connected > 0) {
                add("$connected ${if (connected == 1) "client" else "clients"}")
            }
            add("$pairedCount paired")
            add(ServerCountdown.left(snapshot.endsAtRealtimeMs?.let { it - nowRealtimeMs }))
        }.joinToString(" · ")
    }
}

/** Minutes are the display grain, so 30 s is plenty, and only while a countdown is armed. */
private const val COUNTDOWN_TICK_MS = 30_000L
