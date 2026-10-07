package io.github.alexeikramm.sovereign.floating

/**
 * The minimized pill's visual phase, resolved from the session state and the transient flags.
 *
 * Deliberately pure and free of Compose/Android types so the precedence — in particular the
 * CHUNK-7 BUSY/refusal case, which must show **only** while the pill is at rest and must yield to a
 * capture that actually starts — is unit-testable without a device. The overlay calls this with the
 * same arguments the `when` in `FloatingPillView` used to inline.
 */
internal fun minimizedPillPhase(
    uiState: FloatingUiState,
    modelReady: Boolean,
    busy: Boolean,
    quickStatus: String?,
    quickStatusIsError: Boolean,
    isProcessing: Boolean,
    pillBackdrop: Boolean,
): MinimizedPillPhase = when {
    // A backdrop pill is the resting pill, whatever the session is doing.
    pillBackdrop -> if (modelReady) MinimizedPillPhase.READY else MinimizedPillPhase.COLD
    // The transient refusal mark only ever shows at rest: a tap that succeeds leaves
    // RESTING_PILL immediately, so a stale BUSY can never cover a live capture.
    uiState == FloatingUiState.RESTING_PILL ->
        when {
            busy -> MinimizedPillPhase.BUSY
            modelReady -> MinimizedPillPhase.READY
            else -> MinimizedPillPhase.COLD
        }
    uiState == FloatingUiState.QUICK_CAPTURE && quickStatus != null ->
        if (quickStatusIsError) MinimizedPillPhase.ERROR else MinimizedPillPhase.RESULT
    isProcessing -> MinimizedPillPhase.CLEANING
    else -> MinimizedPillPhase.RECORDING
}
