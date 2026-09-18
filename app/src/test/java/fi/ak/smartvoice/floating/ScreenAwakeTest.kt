package fi.ak.smartvoice.floating

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-hold predicate (D-050). The two cases the phone could hide are the release edges:
 * a finished recording with the setting still on, and a live recording with the user's setting off.
 */
class ScreenAwakeTest {

    @Test
    fun `holds the screen only while recording with the setting on`() {
        assertTrue(ScreenAwake.shouldHold(recording = true, enabled = true))
    }

    @Test
    fun `does not hold once the recording has stopped`() {
        assertFalse(ScreenAwake.shouldHold(recording = false, enabled = true))
    }

    @Test
    fun `does not hold when the user turned the setting off`() {
        assertFalse(ScreenAwake.shouldHold(recording = true, enabled = false))
    }

    @Test
    fun `does not hold when neither is true`() {
        assertFalse(ScreenAwake.shouldHold(recording = false, enabled = false))
    }
}
