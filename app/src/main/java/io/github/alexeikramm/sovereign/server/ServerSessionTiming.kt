package io.github.alexeikramm.sovereign.server

/**
 * Pure timing for a server session's auto-stop (D-136). The deadline lives in the
 * `SystemClock.elapsedRealtime()` domain; the notification converts it to wall-clock only for the
 * countdown chronometer.
 */
object ServerSessionTiming {

    /**
     * The absolute auto-stop deadline, or null for a manual session (`timeoutMinutes == 0`, which
     * never auto-stops).
     */
    fun deadlineFrom(startedAtRealtimeMs: Long, timeoutMinutes: Int): Long? =
        if (timeoutMinutes > 0) {
            startedAtRealtimeMs + timeoutMinutes.toLong() * MINUTE_MS
        } else {
            null
        }

    private const val MINUTE_MS = 60_000L
}
