package fi.ak.smartvoice.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    data class Preset(val name: String, val color: Color, val hex: String) {
        val argb: Int get() = hex.removePrefix("#").let { ("FF$it").toLong(16).toInt() }
    }

    val SOVEREIGN_CYAN = Preset("Sovereign Cyan", Color(0xFF00B4D8), "#00B4D8")
    val SKY_CORE = Preset("Sky Core", Color(0xFF38BDF8), "#38BDF8")
    val TITANIUM_SLATE = Preset("Titanium Slate", Color(0xFF64748B), "#64748B")
    val CYBER_TEAL = Preset("Cyber Teal", Color(0xFF0D9488), "#0D9488")
    val TACTICAL_AMBER = Preset("Tactical Amber", Color(0xFFF59E0B), "#F59E0B")
    val SIGNATURE_ORANGE = Preset("Signature Orange", Color(0xFFFF9248), "#FF9248")
    val PIXEL_BLUE = Preset("Pixel Blue", Color(0xFF1A73E8), "#1A73E8")
    val ANDROID_GREEN = Preset("Android Green", Color(0xFF34A853), "#34A853")
    val CORAL = Preset("Coral", Color(0xFFFA5B52), "#FA5B52")
    val LILAC = Preset("Lilac", Color(0xFFD0BCFF), "#D0BCFF")
    val MINT_TEAL = Preset("Mint Teal", Color(0xFF26A69A), "#26A69A")

    val ALL: List<Preset> = listOf(
        SOVEREIGN_CYAN,
        SKY_CORE,
        TITANIUM_SLATE,
        CYBER_TEAL,
        TACTICAL_AMBER,
        SIGNATURE_ORANGE,
        PIXEL_BLUE,
        ANDROID_GREEN,
        CORAL,
        LILAC,
        MINT_TEAL,
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
        val baseScheme = if (settings.themeUseSystemDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            val seed = Color(settings.themeSeedColor or -0x1000000)
            val style = ThemePaletteStyle.fromId(settings.themePaletteStyle)
            createColorScheme(seedColor = seed, isDark = isDark, paletteStyle = style)
        }

        return if (settings.themeDepthMode) {
            baseScheme.elevatedSovereignPop(
                isDark = isDark,
                darkBgTone = settings.themeDarkBgTone,
                darkCardTone = settings.themeDarkCardTone,
                darkBorderTone = settings.themeDarkBorderTone,
                lightBgTone = settings.themeLightBgTone,
                lightBorderTone = settings.themeLightBorderTone,
            )
        } else {
            baseScheme
        }
    }
}

/**
 * Pure Kotlin CIE L*a*b* conversion utilities to enable deterministic perceptual color
 * manipulations on any host (JVM unit tests, Linux, Android) without Android framework dependencies.
 */
internal object PerceptualColorUtils {
    fun colorToLAB(color: Int, outLab: DoubleArray) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        var sr = r / 255.0
        sr = if (sr < 0.04045) sr / 12.92 else Math.pow((sr + 0.055) / 1.055, 2.4)
        var sg = g / 255.0
        sg = if (sg < 0.04045) sg / 12.92 else Math.pow((sg + 0.055) / 1.055, 2.4)
        var sb = b / 255.0
        sb = if (sb < 0.04045) sb / 12.92 else Math.pow((sb + 0.055) / 1.055, 2.4)

        var x = (sr * 0.4124 + sg * 0.3576 + sb * 0.1805) * 100.0 / 95.047
        var y = (sr * 0.2126 + sg * 0.7152 + sb * 0.0722) * 100.0 / 100.0
        var z = (sr * 0.0193 + sg * 0.1192 + sb * 0.9505) * 100.0 / 108.883

        x = if (x > 0.008856) Math.cbrt(x) else (7.787 * x) + (16.0 / 116.0)
        y = if (y > 0.008856) Math.cbrt(y) else (7.787 * y) + (16.0 / 116.0)
        z = if (z > 0.008856) Math.cbrt(z) else (7.787 * z) + (16.0 / 116.0)

        outLab[0] = (116.0 * y) - 16.0
        outLab[1] = 500.0 * (x - y)
        outLab[2] = 200.0 * (y - z)
    }

    fun labToColor(l: Double, a: Double, b: Double): Int {
        val yVal = (l + 16.0) / 116.0
        val xVal = a / 500.0 + yVal
        val zVal = yVal - b / 200.0

        val x3 = Math.pow(xVal, 3.0)
        val y3 = Math.pow(yVal, 3.0)
        val z3 = Math.pow(zVal, 3.0)

        val x = 95.047 * if (x3 > 0.008856) x3 else (xVal - 16.0 / 116.0) / 7.787
        val y = 100.0 * if (y3 > 0.008856) y3 else (yVal - 16.0 / 116.0) / 7.787
        val z = 108.883 * if (z3 > 0.008856) z3 else (zVal - 16.0 / 116.0) / 7.787

        var r = (x * 3.2406 + y * -1.5372 + z * -0.4986) / 100.0
        var g = (x * -0.9689 + y * 1.8758 + z * 0.0415) / 100.0
        var bOut = (x * 0.0557 + y * -0.2040 + z * 1.0570) / 100.0

        r = if (r > 0.0031308) 1.055 * Math.pow(r, 1.0 / 2.4) - 0.055 else 12.92 * r
        g = if (g > 0.0031308) 1.055 * Math.pow(g, 1.0 / 2.4) - 0.055 else 12.92 * g
        bOut = if (bOut > 0.0031308) 1.055 * Math.pow(bOut, 1.0 / 2.4) - 0.055 else 12.92 * bOut

        val ir = Math.round(Math.min(255.0, Math.max(0.0, r * 255.0))).toInt()
        val ig = Math.round(Math.min(255.0, Math.max(0.0, g * 255.0))).toInt()
        val ib = Math.round(Math.min(255.0, Math.max(0.0, bOut * 255.0))).toInt()

        return (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
    }
}

/**
 * Applies a relative dynamic tonal modifier on the Material 3 ColorScheme to guarantee
 * distinct card elevation, deep stealth obsidian dark backgrounds, and glare-free light canvas.
 */
fun ColorScheme.elevatedSovereignPop(
    isDark: Boolean,
    darkBgTone: Float = 8.0f,
    darkCardTone: Float = 16.0f,
    darkBorderTone: Float = 25.0f,
    lightBgTone: Float = 91.5f,
    lightBorderTone: Float = 82.0f,
): ColorScheme {
    val surfArgb = surface.toArgb()
    val lab = DoubleArray(3)
    PerceptualColorUtils.colorToLAB(surfArgb, lab)

    return if (isDark) {
        val bgArgb = PerceptualColorUtils.labToColor(darkBgTone.toDouble(), lab[1], lab[2])
        val cardArgb = PerceptualColorUtils.labToColor(darkCardTone.toDouble(), lab[1], lab[2])
        val tileArgb = PerceptualColorUtils.labToColor((darkCardTone + 5.0).coerceAtMost(32.0), lab[1], lab[2])
        val borderArgb = PerceptualColorUtils.labToColor(darkBorderTone.toDouble(), lab[1], lab[2])
        val subtitleArgb = PerceptualColorUtils.labToColor(74.0, lab[1], lab[2])

        copy(
            background = Color(bgArgb),
            surface = Color(bgArgb),
            surfaceContainer = Color(cardArgb),
            surfaceContainerLow = Color(cardArgb),
            surfaceContainerHigh = Color(tileArgb),
            surfaceContainerHighest = Color(tileArgb),
            outline = Color(borderArgb),
            outlineVariant = Color(borderArgb),
            onSurface = Color(0xFFF8FAFC),
            onSurfaceVariant = Color(subtitleArgb),
        )
    } else {
        val bgArgb = PerceptualColorUtils.labToColor(lightBgTone.toDouble(), lab[1], lab[2])
        val borderArgb = PerceptualColorUtils.labToColor(lightBorderTone.toDouble(), lab[1], lab[2])
        val tileArgb = PerceptualColorUtils.labToColor(96.5, lab[1], lab[2])
        val subtitleArgb = PerceptualColorUtils.labToColor(38.0, lab[1], lab[2])

        copy(
            background = Color(bgArgb),
            surface = Color(bgArgb),
            surfaceContainer = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainerHigh = Color(tileArgb),
            surfaceContainerHighest = Color(tileArgb),
            outline = Color(borderArgb),
            outlineVariant = Color(borderArgb),
            onSurface = Color(0xFF0F172A),
            onSurfaceVariant = Color(subtitleArgb),
        )
    }
}

