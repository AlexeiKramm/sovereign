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
}
