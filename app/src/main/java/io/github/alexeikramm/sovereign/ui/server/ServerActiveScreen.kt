package io.github.alexeikramm.sovereign.ui.server

import android.app.Activity
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.alexeikramm.sovereign.server.ServerClient
import io.github.alexeikramm.sovereign.server.ServerLifecycle
import io.github.alexeikramm.sovereign.server.ServerSnapshot
import io.github.alexeikramm.sovereign.theme.ColorRoles
import kotlinx.coroutines.delay

/** One breath period; the glyph's glow and the status dot share it, so they breathe together. */
private const val BREATH_PERIOD_MS = 3_400

/** How long the glow takes to grow out of nothing when the server turns on (and to fade when off). */
private const val GLOW_RISE_MS = 900
private const val GLOW_FALL_MS = 300

/** The trough of the breath, as a fraction of its peak: the glow dims but never quite dies mid-cycle. */
private const val GLOW_FLOOR = 0.35f

/** Fraction of the tap box the glyph itself occupies, leaving the rest for the glow to spread. */
private const val GLYPH_FRACTION = 0.34f

/** The glow's own canvas — wider than the tap box so the halo can spread far without being clipped. */
private val GLOW_BOX_DP = 820.dp

/**
 * The Server route's full-bleed surface (server-active-screen CHUNK-1, reworked by CHUNK-5): the ON
 * dashboard while serving and the OFF "tap to start" affordance otherwise. Both are one screen in two
 * moods on the same near-black canvas.
 *
 * The design was settled against a static mock-up (the user's pick: the **bare power glyph** as the
 * centre control, the **SOVEREIGN / Server** header, and a **minimal status pill** — no box, no
 * border, just a green dot before the client name). The old pill waveform, the hint captions, and the
 * radial background glow are gone: the glyph's own glow is the only light on the canvas.
 *
 * **Idle power is a first-class rule here (D-132/D-134).** The only motion is one slow ambient
 * "breath" of the glyph glow and the status dot — and it is **frozen** the moment the screen dims
 * (or when the server is off), so while the screen sits idle **no animation runs and no frames are
 * scheduled**. The glow is read only inside `drawBehind`, so a frame redraws a small layer rather
 * than recomposing the screen. Turning the server on grows the glow **out of nothing** over
 * [GLOW_RISE_MS] before the breath settles, so it never steps into being.
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
    val onSurface = if (isDark) ColorRoles.TextPrimaryDark else ColorRoles.TextPrimaryLight
    val onSurfaceVariant = if (isDark) ColorRoles.TextSecondaryDark else ColorRoles.TextSecondaryLight
    val okColor = if (isDark) ColorRoles.OkDark else ColorRoles.OkLight
    val errorColor = ColorRoles.BadDark

    // The glow state for the whole screen. Inactive (frozen) whenever the server is off or the screen
    // is dimmed, so no animation runs and no frames are scheduled in the idle case (D-132).
    val live = running && !dimmed
    val glow = rememberServerGlow(active = live)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090B))
            // Any touch anywhere wakes the dimmed screen; the glyph's own tap resets too.
            .pointerInput(Unit) { detectTapGestures { onInteract() } },
    ) {
        // Header (mock-up option B): a small-caps SOVEREIGN eyebrow over a bold "Server", with the
        // cog balanced on the same baseline rather than pinned into the corner.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 12.dp, top = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "SOVEREIGN",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp,
                    color = onSurfaceVariant,
                )
                Text(
                    text = "Server",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Server settings",
                    tint = onSurfaceVariant,
                )
            }
        }

        // The glyph is the whole control; no caption (the mock-up has none). The tap target is a large
        // invisible box so a tap anywhere near the glyph starts or stops the server.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(280.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !starting,
                    onClick = {
                        onInteract()
                        if (running) onStop() else onStart()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PowerGlyph(
                glow = glow,
                // Eases between the idle grey and the serving accent, so turning on is not a hard cut.
                color = animateColorAsState(
                    targetValue = if (running) accent else onSurfaceVariant,
                    animationSpec = tween(durationMillis = GLOW_RISE_MS, easing = FastOutSlowInEasing),
                    label = "server-glyph-color",
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }

        StatusPill(
            running = running,
            starting = starting,
            clients = snapshot.clients,
            error = snapshot.error,
            glow = glow,
            accent = accent,
            okColor = okColor,
            errorColor = errorColor,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 46.dp),
        )
    }
}

/**
 * The centre control (chosen design): the bare power glyph, drawn with **round-capped strokes** to
 * match the mock-up, over a soft glow that radiates far from the glyph and fades to nothing well
 * before the edge — no disc, no boundary. [glow] and [color] are read only inside `drawBehind`, so a
 * frame redraws this layer rather than recomposing the screen.
 */
@Composable
private fun PowerGlyph(
    glow: ServerGlow,
    color: State<Color>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredSize(GLOW_BOX_DP)
                .drawBehind {
                    val a = 0.34f * glow.level()
                    if (a <= 0.002f) return@drawBehind
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = glowStops(color.value, a),
                            center = center,
                            radius = size.minDimension * 0.5f,
                        ),
                    )
                },
        )
        Canvas(modifier = Modifier.fillMaxSize(GLYPH_FRACTION)) {
            drawPowerGlyph(color.value)
        }
    }
}

/**
 * A smooth (near-gaussian) falloff for a glow. A handful of stops approximates a continuous curve far
 * better than three, which is what kept the halo ringing on a near-black canvas.
 */
private fun glowStops(color: Color, peak: Float): Array<Pair<Float, Color>> = arrayOf(
    0.00f to color.copy(alpha = 1.00f * peak),
    0.18f to color.copy(alpha = 0.78f * peak),
    0.36f to color.copy(alpha = 0.52f * peak),
    0.54f to color.copy(alpha = 0.30f * peak),
    0.72f to color.copy(alpha = 0.13f * peak),
    0.88f to color.copy(alpha = 0.04f * peak),
    1.00f to Color.Transparent,
)

/** The power mark (SVG `M12 3v9` + an open arc), in the mock-up's proportions, round-capped. */
private fun DrawScope.drawPowerGlyph(color: Color) {
    val s = size.minDimension
    val stroke = s * 0.085f
    val cx = size.width / 2f
    val arcCy = size.height * 0.55f
    val r = s * 0.335f
    drawArc(
        color = color,
        startAngle = 315f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = Offset(cx - r, arcCy - r),
        size = Size(2f * r, 2f * r),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(
        color = color,
        start = Offset(cx, arcCy - r * 1.2f),
        end = Offset(cx, arcCy - r * 0.12f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/**
 * The bottom status: the "minimal pill" — no background, no border, just a dot before the text, the
 * client name in bold. The dot breathes only while the status is live (`starting`, waiting, or a
 * client connected); grey for off, red for an error.
 */
@Composable
private fun StatusPill(
    running: Boolean,
    starting: Boolean,
    clients: List<ServerClient>,
    error: String?,
    glow: ServerGlow,
    accent: Color,
    okColor: Color,
    errorColor: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    modifier: Modifier = Modifier,
) {
    val label = when {
        !error.isNullOrBlank() -> buildAnnotatedString { append(error) }
        starting -> buildAnnotatedString { append("Starting…") }
        running && clients.isEmpty() -> buildAnnotatedString { append("Waiting for a client") }
        running -> buildAnnotatedString {
            val name = if (clients.size == 1) clients.first().name else "${clients.size} clients"
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = onSurface)) { append(name) }
            append(" · Connected")
        }
        else -> buildAnnotatedString { append("Server off") }
    }
    val dotColor = when {
        !error.isNullOrBlank() -> errorColor
        starting -> accent
        running && clients.isEmpty() -> accent
        running -> okColor
        else -> onSurfaceVariant
    }
    val live = error.isNullOrBlank() && (starting || running)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreathingDot(
            color = dotColor,
            live = live,
            glow = glow,
            modifier = Modifier.size(30.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The minimal pill's presence dot. When [live] its colour and glow ease with [glow] (growing out of
 * nothing as the server comes up); otherwise it is a steady grey mark. The container is larger than
 * the dot so the glow has room without clipping.
 */
@Composable
private fun BreathingDot(
    color: Color,
    live: Boolean,
    glow: ServerGlow,
    modifier: Modifier = Modifier,
) {
    val animated = animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = GLOW_FALL_MS + 200, easing = FastOutSlowInEasing),
        label = "server-dot-color",
    )
    Box(
        modifier = modifier.drawBehind {
            val c = animated.value
            val level = if (live) glow.level() else 1f
            val centre = center
            if (live && level > 0.002f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = glowStops(c, 0.42f * level),
                        center = centre,
                        radius = size.minDimension * 0.5f,
                    ),
                )
            }
            drawCircle(
                color = if (live) c.copy(alpha = 0.55f + 0.45f * level) else c,
                radius = size.minDimension * 0.18f,
                center = centre,
            )
        },
    )
}

/**
 * The ambient glow's animation state. [level] is the `0..1` multiplier a glow is drawn with:
 * `appear × pulse`. `appear` grows from nothing when the server turns on (and shrinks when it stops),
 * so the glow **rises** out of the off position; `pulse` is the slow breath, held at its peak while
 * inactive so it schedules nothing. Reading [level] only inside `drawBehind` keeps the frame work off
 * the composition.
 */
private class ServerGlow(
    private val appear: State<Float>,
    private val pulse: State<Float>,
) {
    fun level(): Float = appear.value * (GLOW_FLOOR + (1f - GLOW_FLOOR) * pulse.value)
}

@Composable
private fun rememberServerGlow(active: Boolean): ServerGlow {
    // One-shot: 0 → 1 when the server turns on (so the glow rises), 1 → 0 when it stops. Its value is
    // only ever read inside `drawBehind`, so it drives a redraw, not a recomposition.
    val appear = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) GLOW_RISE_MS else GLOW_FALL_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "server-glow-appear",
    )
    // The continuous breath exists only while active; otherwise it is a constant, so no infinite
    // transition is created and **no frames are scheduled** (D-132).
    val pulse: State<Float> = if (!active) {
        remember { mutableFloatStateOf(1f) }
    } else {
        val transition = rememberInfiniteTransition(label = "server-breath")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = BREATH_PERIOD_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "server-breath",
        )
    }
    return ServerGlow(appear, pulse)
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
