package fi.ak.smartvoice.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fi.ak.smartvoice.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicThemeTest {

    @Test
    fun `default theme settings match locked decisions`() {
        assertEquals(0xFFFF9248.toInt(), AppSettings.DEFAULT_THEME_SEED_COLOR)
        assertEquals("VIBRANT", AppSettings.DEFAULT_THEME_PALETTE_STYLE)
        assertEquals(false, AppSettings.DEFAULT_THEME_USE_SYSTEM_DYNAMIC)
    }

    @Test
    fun `ThemePaletteStyle fromId resolves case-insensitively and falls back to VIBRANT`() {
        assertEquals(ThemePaletteStyle.VIBRANT, ThemePaletteStyle.fromId("vibrant"))
        assertEquals(ThemePaletteStyle.VIBRANT, ThemePaletteStyle.fromId("VIBRANT"))
        assertEquals(ThemePaletteStyle.TONAL_SPOT, ThemePaletteStyle.fromId("tonal_spot"))
        assertEquals(ThemePaletteStyle.TONAL_SPOT, ThemePaletteStyle.fromId("TONAL_SPOT"))
        assertEquals(ThemePaletteStyle.FIDELITY, ThemePaletteStyle.fromId("fidelity"))
        assertEquals(ThemePaletteStyle.FIDELITY, ThemePaletteStyle.fromId("FIDELITY"))
        // Unknown or null falls back to VIBRANT
        assertEquals(ThemePaletteStyle.VIBRANT, ThemePaletteStyle.fromId("unknown_style"))
        assertEquals(ThemePaletteStyle.VIBRANT, ThemePaletteStyle.fromId(null))
    }

    @Test
    fun `presets include signature orange and expected palette seeds`() {
        val orange = ThemePresets.ALL.firstOrNull { it.hex == "#FF9248" }
        assertNotNull("Signature orange preset must exist", orange)
        assertEquals("Signature Orange", orange?.name)
        assertTrue("Preset list should contain at least 6 options", ThemePresets.ALL.size >= 6)
    }

    @Test
    fun `createColorScheme with signature orange generates valid dark and light schemes`() {
        val orange = Color(0xFFFF9248)

        // Dark scheme
        val darkScheme = DynamicThemeEngine.createColorScheme(
            seedColor = orange,
            isDark = true,
            paletteStyle = ThemePaletteStyle.VIBRANT,
        )
        assertNotNull(darkScheme.primary)
        assertNotNull(darkScheme.surface)
        assertNotNull(darkScheme.surfaceContainer)
        assertNotNull(darkScheme.outline)
        // In dark mode, background is dark (low luminance) and primary has high luminance for contrast
        assertTrue(darkScheme.surface.luminance() < 0.1f)
        assertTrue(darkScheme.primary.luminance() > darkScheme.surface.luminance())

        // Light scheme
        val lightScheme = DynamicThemeEngine.createColorScheme(
            seedColor = orange,
            isDark = false,
            paletteStyle = ThemePaletteStyle.VIBRANT,
        )
        assertNotNull(lightScheme.primary)
        assertNotNull(lightScheme.surface)
        // In light mode, background has high luminance and primary has lower luminance
        assertTrue(lightScheme.surface.luminance() > 0.8f)
        assertTrue(lightScheme.primary.luminance() < lightScheme.surface.luminance())
    }

    @Test
    fun `different palette styles produce distinct color schemes for same seed`() {
        val orange = Color(0xFFFF9248)

        val vibrantDark = DynamicThemeEngine.createColorScheme(orange, isDark = true, ThemePaletteStyle.VIBRANT)
        val tonalSpotDark = DynamicThemeEngine.createColorScheme(orange, isDark = true, ThemePaletteStyle.TONAL_SPOT)
        val fidelityDark = DynamicThemeEngine.createColorScheme(orange, isDark = true, ThemePaletteStyle.FIDELITY)

        // The primary container or primary roles must vary across algorithms
        val huesOrTokensDiffer = vibrantDark.primary != tonalSpotDark.primary ||
            vibrantDark.primaryContainer != tonalSpotDark.primaryContainer ||
            vibrantDark.secondary != tonalSpotDark.secondary

        assertTrue("Vibrant and Tonal Spot should generate distinct color assignments", huesOrTokensDiffer)
        assertNotEquals(vibrantDark.secondary, tonalSpotDark.secondary)
    }
}
