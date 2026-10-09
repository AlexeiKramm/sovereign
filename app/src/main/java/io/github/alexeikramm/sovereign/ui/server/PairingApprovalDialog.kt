package io.github.alexeikramm.sovereign.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeikramm.sovereign.server.PairingRequest

/**
 * The mutual-accept approval (Chunk 2b, PROTOCOL.md §4): names the requesting desktop and shows the
 * phone's own server fingerprint so the user can compare it with the one the desktop is pinning —
 * the man-in-the-middle defence for the first, not-yet-pinned exchange.
 *
 * Dismissing (back / outside tap) counts as **deny**: an unanswered request must never approve.
 */
@Composable
fun PairingApprovalDialog(
    request: PairingRequest,
    onDecision: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text("Pairing request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "\"${request.name}\" wants to pair with this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Check that this fingerprint matches the one shown on the client, then " +
                        "approve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = request.serverSpki,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text("Deny") }
        },
    )
}
