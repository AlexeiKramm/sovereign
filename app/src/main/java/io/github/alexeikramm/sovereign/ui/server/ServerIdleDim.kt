package io.github.alexeikramm.sovereign.ui.server

/**
 * Pure policy for the Server active dashboard's idle dimming (server-active-screen CHUNK-2).
 *
 * The dashboard is the screen a user leaves on while serving, so after [IDLE_MS] with no touch it
 * drops the window's brightness hard; any touch restores it. The *mechanism* is Android
 * (`WindowManager.LayoutParams.screenBrightness`, a per-window override that needs no permission),
 * but the *rule* lives here so it is unit-tested without a device.
 *
 * Dimming is a per-window brightness override, not a black overlay: it lowers the panel's actual
 * luminance, which is what saves AMOLED power even when the user's system brightness is high.
 */
object ServerIdleDim {

    /** How long the screen sits untouched before it dims. */
    const val IDLE_MS = 10_000L

    /** How often the idle wait re-checks the rule. Deliberately coarse; this is not a frame clock. */
    const val POLL_MS = 500L

    /**
     * Window brightness while dimmed: 2% of maximum. Deliberately not `0f`, which means
     * "screen off" (`BRIGHTNESS_OVERRIDE_OFF`) rather than "very dim".
     */
    const val DIM_BRIGHTNESS = 0.02f

    /**
     * Window brightness while undimmed: the system default. Equal to
     * `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE` (`-1f`); pinned here so the rule is
     * JVM-testable.
     */
    const val UNDIMMED_BRIGHTNESS = -1f

    /** Whether the screen should be dimmed: only when enabled, and only after the idle window. */
    fun shouldDim(enabled: Boolean, idleForMs: Long): Boolean = enabled && idleForMs >= IDLE_MS

    /** The window brightness override for the current dim state. */
    fun brightnessFor(dimmed: Boolean): Float =
        if (dimmed) DIM_BRIGHTNESS else UNDIMMED_BRIGHTNESS
}
