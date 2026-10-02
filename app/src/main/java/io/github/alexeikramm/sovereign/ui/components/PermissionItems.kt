package io.github.alexeikramm.sovereign.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A permission the dictation pill needs. It is the single model shared by Home's status card and
 * Settings' warning card, so both agree on what is missing and how to ask for it.
 *
 * [Microphone] is requested in-app through the runtime launcher; the other two are granted on an
 * Android settings screen and have no in-app dialog.
 */
enum class PermissionItem(
    val title: String,
    val why: String,
    val icon: ImageVector,
    val actionLabel: String,
) {
    Microphone(
        title = "Microphone permission required",
        why = "Required for speech recognition to hear your voice.",
        icon = Icons.Default.Mic,
        actionLabel = "Allow microphone",
    ),
    Overlay(
        title = "Display over other apps",
        why = "Required for the floating pill to appear over your keyboard.",
        icon = Icons.Default.Layers,
        actionLabel = "Grant overlay",
    ),
    Accessibility(
        title = "Accessibility service disabled",
        why = "Required to insert dictated text directly at your cursor.",
        icon = Icons.Default.Accessibility,
        actionLabel = "Enable accessibility",
    ),
}

/**
 * Opens the system settings screen for a permission granted outside the app. Does nothing for
 * [PermissionItem.Microphone], which the caller must request through an in-app launcher.
 */
fun PermissionItem.openSettings(context: Context) {
    val intent = when (this) {
        PermissionItem.Microphone -> return
        PermissionItem.Overlay -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        PermissionItem.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show() }
}
