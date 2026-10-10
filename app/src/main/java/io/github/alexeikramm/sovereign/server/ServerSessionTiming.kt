package io.github.alexeikramm.sovereign.server

/**
 * Pure timing for a server session's auto-stop (D-136). The deadline lives in the
 * `SystemClock.elapsedRealtime()` domain; the notification converts it to wall-clock only for the
 * countdown chronometer.
 */
object ServerSessionTiming {

    /** How much the notification's `＋ 30 min` action adds. */
    const val EXTEND_MINUTES = 30

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

    /**
     * The deadline after extending by [minutes]. Never moves backwards: a session already past its
     * deadline restarts from [nowRealtimeMs].
     */
    fun extendedDeadline(
        currentDeadlineRealtimeMs: Long,
        nowRealtimeMs: Long,
        minutes: Int = EXTEND_MINUTES,
    ): Long = maxOf(currentDeadlineRealtimeMs, nowRealtimeMs) + minutes.toLong() * MINUTE_MS

    /** Whether the notification offers `＋ 30 min`: only a session that has an auto-stop can extend. */
    fun showsExtendAction(endsAtRealtimeMs: Long?): Boolean = endsAtRealtimeMs != null

    private const val MINUTE_MS = 60_000L
}
