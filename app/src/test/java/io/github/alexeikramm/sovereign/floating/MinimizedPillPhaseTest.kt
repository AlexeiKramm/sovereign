package io.github.alexeikramm.sovereign.floating

import io.github.alexeikramm.sovereign.floating.FloatingUiState.QUICK_CAPTURE
import io.github.alexeikramm.sovereign.floating.FloatingUiState.RECORDING
import io.github.alexeikramm.sovereign.floating.FloatingUiState.RESTING_PILL
import io.github.alexeikramm.sovereign.floating.FloatingUiState.REVIEW
import io.github.alexeikramm.sovereign.floating.MinimizedPillPhase.BUSY
import io.github.alexeikramm.sovereign.floating.MinimizedPillPhase.CLEANING
import io.github.alexeikramm.sovereign.floating.MinimizedPillPhase.COLD
import io.github.alexeikramm.sovereign.floating.MinimizedPillPhase.ERROR
import io.github.alexeikramm.sovereign.floating.MinimizedPillPhase.READY
import io.github.alexeikramm.sovereign.floating.MinimizedPillPhase.RESULT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The minimized pill's phase precedence. The case CHUNK-7 adds is the BUSY/refusal mark: it must
 * appear when the pill is tapped while another surface holds the arbiter, and it must **never**
 * cover a capture that actually started.
 */
class MinimizedPillPhaseTest {

    private fun phase(
        uiState: FloatingUiState,
        modelReady: Boolean = true,
        busy: Boolean = false,
        quickStatus: String? = null,
        quickStatusIsError: Boolean = false,
        isProcessing: Boolean = false,
        pillBackdrop: Boolean = false,
    ) = minimizedPillPhase(
        uiState = uiState,
        modelReady = modelReady,
        busy = busy,
        quickStatus = quickStatus,
        quickStatusIsError = quickStatusIsError,
        isProcessing = isProcessing,
        pillBackdrop = pillBackdrop,
    )

    @Test
    fun `a refused tap shows BUSY while resting`() {
        assertEquals(BUSY, phase(uiState = RESTING_PILL, busy = true))
    }

    @Test
    fun `resting without a refusal is READY when warm and COLD when not`() {
        assertEquals(READY, phase(uiState = RESTING_PILL, modelReady = true))
        assertEquals(COLD, phase(uiState = RESTING_PILL, modelReady = false))
    }

    /** BUSY is a resting-only mark: a live capture wins, so a stale flash cannot mask it. */
    @Test
    fun `a live capture overrides a lingering BUSY flag`() {
        assertEquals(MinimizedPillPhase.RECORDING, phase(uiState = RECORDING, busy = true))
        assertEquals(CLEANING, phase(uiState = REVIEW, busy = true, isProcessing = true))
    }

    @Test
    fun `recording is CLEANING while processing and RECORDING otherwise`() {
        assertEquals(MinimizedPillPhase.RECORDING, phase(uiState = RECORDING))
        assertEquals(CLEANING, phase(uiState = RECORDING, isProcessing = true))
    }

    @Test
    fun `a quick-capture result is a check or a cross`() {
        assertEquals(RESULT, phase(uiState = QUICK_CAPTURE, quickStatus = "Inserted"))
        assertEquals(ERROR, phase(uiState = QUICK_CAPTURE, quickStatus = "Nothing heard", quickStatusIsError = true))
    }

    @Test
    fun `a backdrop pill is resting whatever the session is doing`() {
        assertEquals(READY, phase(uiState = RECORDING, modelReady = true, pillBackdrop = true))
        assertEquals(COLD, phase(uiState = RECORDING, modelReady = false, pillBackdrop = true))
        assertEquals(READY, phase(uiState = RESTING_PILL, modelReady = true, busy = true, pillBackdrop = true))
    }
}
