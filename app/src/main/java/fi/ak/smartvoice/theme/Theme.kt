package fi.ak.smartvoice.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

/**
 * Whether the app should render dark, resolving [AppThemeMode.SYSTEM] against the platform.
 * Public so the Activity can match the system bar icons to the *app's* theme rather than the
 * system's — otherwise an app theme opposite the system leaves the clock unreadable.
 */
@Composable
fun appThemeIsDark(themeMode: AppThemeMode): Boolean = when (themeMode) {
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun SovereignTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    /**
     * Off by default: the app has its own warm-cream/lilac identity, and
     * Material You would override every token on Android 12+. Flip this to true
     * only if we deliberately want wallpaper-driven colours.
     */
    dynamicColor: Boolean = false,
    colorScheme: ColorScheme? = null,
    seedColor: Int? = null,
    paletteStyle: String? = null,
    useSystemDynamic: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = appThemeIsDark(themeMode)
    val context = LocalContext.current

    val resolvedColorScheme = colorScheme ?: run {
        val effectiveUseSystem = useSystemDynamic ?: dynamicColor
        if (effectiveUseSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (seedColor != null) {
            val style = ThemePaletteStyle.fromId(paletteStyle)
            DynamicThemeEngine.createColorScheme(
                seedColor = androidx.compose.ui.graphics.Color(seedColor or -0x1000000),
                isDark = darkTheme,
                paletteStyle = style,
            )
        } else {
            val settings = runCatching { AppSettings(context) }.getOrNull()
            if (settings != null) {
                DynamicThemeEngine.resolveColorScheme(context, settings, darkTheme)
            } else {
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
    }

    MaterialTheme(colorScheme = resolvedColorScheme, typography = Typography, content = content)
}

@Deprecated("Use SovereignTheme instead", ReplaceWith("SovereignTheme(themeMode, dynamicColor, colorScheme, seedColor, paletteStyle, useSystemDynamic, content)"))
@Composable
fun SmartVoiceTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    colorScheme: ColorScheme? = null,
    seedColor: Int? = null,
    paletteStyle: String? = null,
    useSystemDynamic: Boolean? = null,
    content: @Composable () -> Unit,
) = SovereignTheme(themeMode, dynamicColor, colorScheme, seedColor, paletteStyle, useSystemDynamic, content)
