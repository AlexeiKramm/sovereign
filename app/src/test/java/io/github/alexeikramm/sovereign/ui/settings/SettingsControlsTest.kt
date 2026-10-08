package io.github.alexeikramm.sovereign.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsControlsTest {

    @Test
    fun `both sliders move in half-second steps`() {
        // 0.5..5.0 at 0.5 s => 10 values => 8 interior stops.
        assertEquals(8, SettingsControls.PAUSE_STEPS)
        // 0..300 at 0.5 s => 601 values => 599 interior stops.
        assertEquals(599, SettingsControls.STOP_STEPS)
    }

    @Test
    fun `snap clamps to the range and lands on a step`() {
        assertEquals(
            0.5f,
            SettingsControls.snap(0.1f, SettingsControls.PAUSE_MIN_SECONDS, SettingsControls.PAUSE_MAX_SECONDS),
            0f,
        )
        assertEquals(
            2.5f,
            SettingsControls.snap(2.3f, SettingsControls.PAUSE_MIN_SECONDS, SettingsControls.PAUSE_MAX_SECONDS),
            0f,
        )
        assertEquals(
            5.0f,
            SettingsControls.snap(99f, SettingsControls.PAUSE_MIN_SECONDS, SettingsControls.PAUSE_MAX_SECONDS),
            0f,
        )
        assertEquals(
            0f,
            SettingsControls.snap(-3f, SettingsControls.STOP_MIN_SECONDS, SettingsControls.STOP_MAX_SECONDS),
            0f,
        )
        assertEquals(
            300f,
            SettingsControls.snap(999f, SettingsControls.STOP_MIN_SECONDS, SettingsControls.STOP_MAX_SECONDS),
            0f,
        )
    }

    @Test
    fun `labels read as seconds and zero reads as off`() {
        assertEquals("Off", SettingsControls.formatSeconds(0f))
        assertEquals("2 s", SettingsControls.formatSeconds(2f))
        assertEquals("2.5 s", SettingsControls.formatSeconds(2.5f))
        assertEquals("0.5 s", SettingsControls.formatSeconds(0.5f))
        assertEquals("60 s", SettingsControls.formatSeconds(60f))
    }

    @Test
    fun `auto-stop stepper steps by 1s below 10s and 5s above 10s`() {
        assertEquals(1f, SettingsControls.nextStopSeconds(0f), 0f)
        assertEquals(2f, SettingsControls.nextStopSeconds(1f), 0f)
        assertEquals(10f, SettingsControls.nextStopSeconds(9f), 0f)
        assertEquals(15f, SettingsControls.nextStopSeconds(10f), 0f)
        assertEquals(65f, SettingsControls.nextStopSeconds(60f), 0f)
        assertEquals(300f, SettingsControls.nextStopSeconds(300f), 0f)

        assertEquals(295f, SettingsControls.prevStopSeconds(300f), 0f)
        assertEquals(55f, SettingsControls.prevStopSeconds(60f), 0f)
        assertEquals(10f, SettingsControls.prevStopSeconds(15f), 0f)
        assertEquals(9f, SettingsControls.prevStopSeconds(10f), 0f)
        assertEquals(0f, SettingsControls.prevStopSeconds(1f), 0f)
        assertEquals(0f, SettingsControls.prevStopSeconds(0f), 0f)
    }
}
