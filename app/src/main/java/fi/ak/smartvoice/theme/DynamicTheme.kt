package fi.ak.smartvoice.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import fi.ak.smartvoice.data.AppSettings

/**
 * Supported Material 3 palette generation styles.
 */
enum class ThemePaletteStyle(
    val id: String,
    val label: String,
    val description: String,
    val style: PaletteStyle,
) {
    VIBRANT("VIBRANT", "Vibrant", "Rich, vivid accents with high chroma", PaletteStyle.Vibrant),
    TONAL_SPOT("TONAL_SPOT", "Tonal Spot", "Default Material 3 pastel tones", PaletteStyle.TonalSpot),
    FIDELITY("FIDELITY", "Fidelity", "Faithful to exact seed color and tone", PaletteStyle.Fidelity);

    companion object {
        fun fromId(id: String?): ThemePaletteStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VIBRANT
    }
}

/**
 * Curated preset seed colors for quick one-tap selection.
 */
object ThemePresets {
    data class Preset(val name: String, val color: Color, val hex: String)

    val SIGNATURE_ORANGE = Preset("Signature Orange", Color(0xFFFF9248), "#FF9248")
    val PIXEL_BLUE = Preset("Pixel Blue", Color(0xFF1A73E8), "#1A73E8")
    val ANDROID_GREEN = Preset("Android Green", Color(0xFF34A853), "#34A853")
    val CORAL = Preset("Coral", Color(0xFFFA5B52), "#FA5B52")
    val LILAC = Preset("Lilac", Color(0xFFD0BCFF), "#D0BCFF")
    val MINT_TEAL = Preset("Mint Teal", Color(0xFF26A69A), "#26A69A")
    val SLATE_GRAY = Preset("Slate", Color(0xFF546E7A), "#546E7A")

    val ALL: List<Preset> = listOf(
        SIGNATURE_ORANGE,
        PIXEL_BLUE,
        ANDROID_GREEN,
        CORAL,
        LILAC,
        MINT_TEAL,
        SLATE_GRAY,
    )
}

/**
 * Generates Material Design 3 dynamic color schemes from user-selected seed colors.
 */
object DynamicThemeEngine {

    /**
     * Generates a complete Material 3 [ColorScheme] for [seedColor] and [paletteStyle].
     */
    fun createColorScheme(
        seedColor: Color,
        isDark: Boolean,
        paletteStyle: ThemePaletteStyle = ThemePaletteStyle.VIBRANT,
        contrastLevel: Double = 0.0,
    ): ColorScheme {
        return dynamicColorScheme(
            seedColor = seedColor,
            isDark = isDark,
            isAmoled = false,
            style = paletteStyle.style,
            contrastLevel = contrastLevel,
        )
    }

    /**
     * Resolves the active [ColorScheme] from [settings], taking into account system dynamic
     * wallpaper support (API 31+), custom seed color, and chosen palette style.
     */
    fun resolveColorScheme(
        context: Context,
        settings: AppSettings,
        isDark: Boolean,
    ): ColorScheme {
        if (settings.themeUseSystemDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        val seed = Color(settings.themeSeedColor)
        val style = ThemePaletteStyle.fromId(settings.themePaletteStyle)
        return createColorScheme(seedColor = seed, isDark = isDark, paletteStyle = style)
    }
}
