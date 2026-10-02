package io.github.alexeikramm.sovereign.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the fresh-install defaults chosen in S17 round 1. These are `const` values, so this is a
 * plain JVM test with no Android context.
 */
class AppSettingsDefaultsTest {

    @Test
    fun `S17 round 1 boundary timing defaults`() {
        assertEquals(1.0f, AppSettings.DEFAULT_PAUSE_SILENCE_S, 0.001f)
        assertEquals(3.0f, AppSettings.DEFAULT_STABLE_SILENCE_S, 0.001f)
        assertEquals(8.0f, AppSettings.DEFAULT_STOP_AFTER_SILENCE_S, 0.001f)
    }
}
