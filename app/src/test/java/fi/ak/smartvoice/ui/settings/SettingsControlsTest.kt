package fi.ak.smartvoice.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsControlsTest {

    @Test
    fun `both sliders move in half-second steps`() {
        // 0.5..5.0 at 0.5 s => 10 values => 8 interior stops.
        assertEquals(8, SettingsControls.PAUSE_STEPS)
        // 0..20 at 0.5 s => 41 values => 39 interior stops.
        assertEquals(39, SettingsControls.STOP_STEPS)
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
    }

    @Test
    fun `labels read as seconds and zero reads as off`() {
        assertEquals("Off", SettingsControls.formatSeconds(0f))
        assertEquals("2 s", SettingsControls.formatSeconds(2f))
        assertEquals("2.5 s", SettingsControls.formatSeconds(2.5f))
        assertEquals("0.5 s", SettingsControls.formatSeconds(0.5f))
    }
}
