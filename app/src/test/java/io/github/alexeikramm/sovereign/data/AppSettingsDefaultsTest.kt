package io.github.alexeikramm.sovereign.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `streaming preview defaults to on with a muted, semi-transparent look`() {
        assertTrue(AppSettings.DEFAULT_STREAMING_PREVIEW_ENABLED)
        assertEquals(RawTextColorRole.ON_SURFACE_VARIANT, RawTextColorRole.DEFAULT)
        assertTrue(AppSettings.DEFAULT_STREAMING_PREVIEW_OPACITY in 0f..1f)
    }

    @Test
    fun `raw text colour role resolves by id and falls back to the default`() {
        assertEquals(RawTextColorRole.OUTLINE, RawTextColorRole.fromId("outline"))
        assertEquals(RawTextColorRole.PRIMARY, RawTextColorRole.fromId("PRIMARY"))
        assertEquals(RawTextColorRole.DEFAULT, RawTextColorRole.fromId(null))
        assertEquals(RawTextColorRole.DEFAULT, RawTextColorRole.fromId("not-a-role"))
    }

    @Test
    fun `every raw text role has a unique id`() {
        val ids = RawTextColorRole.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it.isNotBlank()) }
    }
}
