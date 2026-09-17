package fi.ak.smartvoice.engine

/**
 * Residency policy for the pill's two on-device engines (D-045).
 *
 * The service owns the engines and the dispatchers; this file owns only the *decisions* about them,
 * so the branches that matter — is the pill fully warm, and (later) may a model be unloaded — are
 * checkable in a JVM test instead of on a phone with a `dumpsys meminfo` in the other hand.
 */
object ModelResidency {

    /**
     * Whether everything the **current configuration** needs is resident.
     *
     * ASR is always needed; the cleanup LLM is only needed while cleanup is enabled. So a user who
     * has turned cleanup off is fully warm with just ASR loaded, and the resting pill is allowed to
     * breathe — "warm" must describe this configuration, not an absolute that can never be met.
     */
    fun isFullyWarm(
        asrLoaded: Boolean,
        cleanupEnabled: Boolean,
        cleanupLoaded: Boolean,
    ): Boolean = asrLoaded && (!cleanupEnabled || cleanupLoaded)

    /**
     * Whether an unload trigger may actually release the engines.
     *
     * This is the whole "never yank a model out from under a running utterance" rule, and every
     * trigger — screen off, idle timeout, keyboard close, memory pressure — consults it. A capture
     * is in flight while the recorder is running **or** the utterance is still being finalised
     * (cleanup `drain()` and punctuation run after the recorder stops), so both count.
     */
    fun canUnload(recording: Boolean, processing: Boolean): Boolean = !recording && !processing

    /**
     * Whether the idle window has elapsed since the last capture ended. [timeoutMinutes] `<= 0`
     * means "never": the user's explicit choice to keep the models resident.
     */
    fun idleTimeoutReached(idleMillis: Long, timeoutMinutes: Int): Boolean =
        timeoutMinutes > 0 && idleMillis >= timeoutMinutes.toLong() * MILLIS_PER_MINUTE

    const val MILLIS_PER_MINUTE = 60_000L
}
