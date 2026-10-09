package io.github.alexeikramm.sovereign.ui.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdleDimTest {

    @Test
    fun disabledNeverDims() {
        assertFalse(ServerIdleDim.shouldDim(enabled = false, idleForMs = 0))
        assertFalse(ServerIdleDim.shouldDim(enabled = false, idleForMs = Long.MAX_VALUE))
    }

    @Test
    fun dimsOnlyAtOrAfterTheIdleWindow() {
        assertFalse(ServerIdleDim.shouldDim(enabled = true, idleForMs = ServerIdleDim.IDLE_MS - 1))
        assertTrue(ServerIdleDim.shouldDim(enabled = true, idleForMs = ServerIdleDim.IDLE_MS))
        assertTrue(ServerIdleDim.shouldDim(enabled = true, idleForMs = ServerIdleDim.IDLE_MS + 5_000))
    }

    @Test
    fun negativeIdleTimeDoesNotDim() {
        assertFalse(ServerIdleDim.shouldDim(enabled = true, idleForMs = -1))
    }

    @Test
    fun brightnessMapsToTheWindowOverride() {
        assertEquals(ServerIdleDim.DIM_BRIGHTNESS, ServerIdleDim.brightnessFor(dimmed = true), 0f)
        assertEquals(
            ServerIdleDim.UNDIMMED_BRIGHTNESS,
            ServerIdleDim.brightnessFor(dimmed = false),
            0f,
        )
        // Undimmed must be the system default (-1), never an absolute brightness.
        assertEquals(-1f, ServerIdleDim.UNDIMMED_BRIGHTNESS, 0f)
        // Dim is a visible, low, non-zero value: 0f would mean "screen off".
        assertTrue(ServerIdleDim.DIM_BRIGHTNESS > 0f && ServerIdleDim.DIM_BRIGHTNESS <= 0.1f)
    }
}
