package io.github.alexeikramm.sovereign.server

/**
 * The exact auto-stop countdown label (D-136), shared by the Home status row and the ongoing
 * notification so the two cannot disagree. Pure Kotlin with no Android dependency, so it is
 * unit-tested on the JVM.
 */
object ServerCountdown {

    /** Shown when the session has no auto-stop (manual): it runs until the user stops it. */
    const val UNTIL_STOPPED = "until stopped"

    /**
     * Formats the time left until auto-stop as `1 h 42 m left`. A null or non-positive [remainingMs]
     * means the session has no auto-stop. Rounds up, so any partial minute still reads as a minute
     * rather than flipping to zero while the server is still armed.
     */
    fun left(remainingMs: Long?): String {
        if (remainingMs == null || remainingMs <= 0L) return UNTIL_STOPPED
        return "${duration(remainingMs)} left"
    }

    private fun duration(remainingMs: Long): String {
        val totalMinutes = (remainingMs + MINUTE_MS - 1L) / MINUTE_MS
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "$hours h $minutes m"
            hours > 0L -> "$hours h"
            else -> "$minutes m"
        }
    }

    private const val MINUTE_MS = 60_000L
}
