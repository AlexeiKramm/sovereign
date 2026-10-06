package io.github.alexeikramm.sovereign.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts and stops the server foreground service from the UI (Chunk 2a). Kept apart from
 * [ServerRuntime] so the observable state stays an Android-free, JVM-testable unit.
 *
 * [start] is a no-op if the service is already up: the service's `onStartCommand` is idempotent, and
 * re-posting `STARTING` over a live `RUNNING` would only flicker the dashboard.
 */
object ServerController {

    /**
     * Asks the service to serve, with [timeoutMinutes] as the auto-stop (0 = never). Must be called
     * while an Activity is visible: the service holds the `microphone` foreground type for its whole
     * life and Android 14+ will not grant a while-in-use type to a background app.
     */
    fun start(context: Context, timeoutMinutes: Int) {
        if (ServerRuntime.shared.snapshot().lifecycle == ServerLifecycle.RUNNING) return
        ServerRuntime.shared.onStarting()
        val intent = Intent(context, SovereignServerService::class.java)
            .putExtra(SovereignServerService.EXTRA_TIMEOUT_MINUTES, timeoutMinutes)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Stops the service; its `onDestroy` tears down the socket, mDNS and the notification. */
    fun stop(context: Context) {
        context.stopService(Intent(context, SovereignServerService::class.java))
    }
}
