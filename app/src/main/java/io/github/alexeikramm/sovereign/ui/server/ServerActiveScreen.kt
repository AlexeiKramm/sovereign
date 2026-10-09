package io.github.alexeikramm.sovereign.ui.server

import android.app.Activity
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.alexeikramm.sovereign.floating.WaterSurfaceWave
import io.github.alexeikramm.sovereign.server.ServerClient
import io.github.alexeikramm.sovereign.server.ServerLifecycle
import io.github.alexeikramm.sovereign.server.ServerSnapshot
import io.github.alexeikramm.sovereign.theme.ColorRoles
import io.github.alexeikramm.sovereign.ui.components.PresenceDot
import kotlinx.coroutines.delay

/**
 * The Server route's full-bleed surface (server-active-screen CHUNK-1): the ON dashboard while
 * serving and the OFF "tap to start" affordance otherwise. Both are one screen in two moods — the
 * same near-black canvas, the same pill waveform as the tap target — so the transition between
 * states is a change of mood, not a change of place.
 *
 * This is deliberately a *static* prototype: the waveform is drawn with a constant level and there
 * is no live audio or transcript. Wiring the mic level and the delivered text is a follow-up, by
 * decision (`docs/work/server-active-screen/PLAN.md`, Out of scope).
 */
@Composable
internal fun ServerHubBody(
    snapshot: ServerSnapshot,
    keepScreenAwake: Boolean,
    dimWhenIdle: Boolean,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = snapshot.lifecycle == ServerLifecycle.RUNNING
    val starting = snapshot.lifecycle == ServerLifecycle.STARTING

    val view = LocalView.current

    // Idle dimming (CHUNK-2): after a spell with no touch, drop the window brightness hard; any
    // touch restores it. The rule is pure (ServerIdleDim); the override is per-window and needs no
    // permission.
    var dimmed by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val onInteract: () -> Unit = {
        dimmed = false
        interactionTick++
    }
    LaunchedEffect(interactionTick, dimWhenIdle) {
        dimmed = false
        if (!dimWhenIdle) return@LaunchedEffect
        val startMs = SystemClock.elapsedRealtime()
        while (true) {
            delay(ServerIdleDim.POLL_MS)
            if (ServerIdleDim.shouldDim(dimWhenIdle, SystemClock.elapsedRealtime() - startMs)) {
                dimmed = true
                break
            }
        }
    }
    LaunchedEffect(dimmed) { applyScreenBrightness(view, dimmed) }
    DisposableEffect(Unit) { onDispose { applyScreenBrightness(view, false) } }

    // Immersive: hide the system bars so the clean AMOLED canvas is unbroken, and they cannot burn
    // in. Transient bars come back on a swipe; fully restored when the route is left.
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // The keep-awake reason for this screen (setting, default on). Only while actually serving: the
    // server itself survives screen-off (D-105), so this is a performance/jitter choice.
    DisposableEffect(keepScreenAwake, running) {
        view.keepScreenOn = keepScreenAwake && running
        onDispose { view.keepScreenOn = false }
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary
    val waveformColor = if (isDark) ColorRoles.WaveformDark else ColorRoles.WaveformLight
    val onSurface = if (isDark) ColorRoles.TextPrimaryDark else ColorRoles.TextPrimaryLight
    val onSurfaceVariant = if (isDark) ColorRoles.TextSecondaryDark else ColorRoles.TextSecondaryLight

    val okColor = if (isDark) ColorRoles.OkDark else ColorRoles.OkLight
    val errorColor = ColorRoles.BadDark

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090B))
            // Any touch anywhere wakes the dimmed screen; the wave's own tap resets too.
            .pointerInput(Unit) { detectTapGestures { onInteract() } }
            .drawBehind {
                // A whisper of the app's radial accent glow. Kept very low: a large static bright
                // patch is exactly the AMOLED burn-in this screen exists to avoid.
                drawRect(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = 0.10f),
                            0.5f to accent.copy(alpha = 0.03f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.30f),
                        radius = size.maxDimension * 0.72f,
                    ),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                running = running,
                starting = starting,
                clients = snapshot.clients,
                error = snapshot.error,
                onSurfaceVariant = onSurfaceVariant,
                okColor = okColor,
                errorColor = errorColor,
                accent = accent,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Server settings",
                    tint = onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = !starting,
                        onClick = {
                            onInteract()
                            if (running) onStop() else onStart()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                WaterSurfaceWave(
                    audioLevel = remember { { 0f } },
                    color = waveformColor.copy(alpha = if (running) 1f else 0.5f),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = when {
                    starting -> "Starting…"
                    running -> "Tap to stop"
                    else -> "Tap to start serving"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                textAlign = TextAlign.Center,
            )

            if (!running && !starting) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Dictate on your PC using this phone's mic and models.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    running: Boolean,
    starting: Boolean,
    clients: List<ServerClient>,
    error: String?,
    onSurfaceVariant: Color,
    okColor: Color,
    errorColor: Color,
    accent: Color,
) {
    val label: String
    val dotColor: Color
    when {
        !error.isNullOrBlank() -> {
            label = error
            dotColor = errorColor
        }
        starting -> {
            label = "Starting…"
            dotColor = accent
        }
        running && clients.isEmpty() -> {
            label = "Ready · waiting for a client"
            dotColor = accent
        }
        running -> {
            label = if (clients.size == 1) {
                "Serving · ${clients.first().name}"
            } else {
                "Serving · ${clients.size} clients"
            }
            dotColor = okColor
        }
        else -> {
            label = "Server off"
            dotColor = onSurfaceVariant
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PresenceDot(color = dotColor)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/**
 * Applies the dim override to the host window. A per-window `screenBrightness` override, so it
 * lowers the panel's real luminance regardless of the user's system brightness, and needs no
 * permission. A no-op when the view is not attached to an Activity.
 */
private fun applyScreenBrightness(view: View, dimmed: Boolean) {
    val window = (view.context as? Activity)?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = ServerIdleDim.brightnessFor(dimmed)
    }
}
