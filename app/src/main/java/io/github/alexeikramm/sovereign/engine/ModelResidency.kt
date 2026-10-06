package io.github.alexeikramm.sovereign.engine

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
     *
     * [serverUtterance] is the third claimant (D-105/D-106): an in-flight desktop-server utterance
     * is "processing" too, even though it owns neither the phone's pill state nor a phone capture.
     * It is a plain flag so this file stays free of the arbiter type.
     */
    fun canUnload(
        recording: Boolean,
        processing: Boolean,
        serverUtterance: Boolean = false,
    ): Boolean = !recording && !processing && !serverUtterance

    /** The four unload triggers (D-045). Server mode suppresses the two pill-only ones (D-105). */
    enum class Trigger { SCREEN_OFF, KEYBOARD_CLOSE, IDLE, MEMORY_PRESSURE }

    /**
     * Whether [trigger] may release the engines under the current mode.
     *
     * While a desktop server is serving (D-105), **screen-off and keyboard-close are suppressed**: a
     * docked phone's screen is expected to be off, and the phone's IME is not the desktop's input.
     * The idle timeout remains the one unload whose meaning ("no dictation for N minutes") maps onto
     * server use, and memory pressure still applies because an OOM helps nobody.
     */
    fun triggerAllowed(trigger: Trigger, serverMode: Boolean): Boolean = when {
        !serverMode -> true
        else -> trigger != Trigger.SCREEN_OFF && trigger != Trigger.KEYBOARD_CLOSE
    }

    /**
     * Whether the idle window has elapsed since the last capture ended. [timeoutMinutes] `<= 0`
     * means "never": the user's explicit choice to keep the models resident.
     */
    fun idleTimeoutReached(idleMillis: Long, timeoutMinutes: Int): Boolean =
        timeoutMinutes > 0 && idleMillis >= timeoutMinutes.toLong() * MILLIS_PER_MINUTE

    const val MILLIS_PER_MINUTE = 60_000L
}
