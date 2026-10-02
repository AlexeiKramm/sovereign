package io.github.alexeikramm.sovereign.theme

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
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.AppThemeMode

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
    depthMode: Boolean? = null,
    darkBgTone: Float? = null,
    darkCardTone: Float? = null,
    darkBorderTone: Float? = null,
    lightBgTone: Float? = null,
    lightBorderTone: Float? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = appThemeIsDark(themeMode)
    val context = LocalContext.current

    val resolvedColorScheme = colorScheme ?: run {
        val effectiveUseSystem = useSystemDynamic ?: dynamicColor
        val settings = runCatching { AppSettings(context) }.getOrNull()

        val baseScheme = if (effectiveUseSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (seedColor != null) {
            val style = ThemePaletteStyle.fromId(paletteStyle)
            DynamicThemeEngine.createColorScheme(
                seedColor = androidx.compose.ui.graphics.Color(seedColor or -0x1000000),
                isDark = darkTheme,
                paletteStyle = style,
            )
        } else {
            if (settings != null) {
                // If using settings directly and no override parameters were provided, resolveColorScheme handles it
                if (depthMode == null && darkBgTone == null && darkCardTone == null &&
                    darkBorderTone == null && lightBgTone == null && lightBorderTone == null
                ) {
                    return@run DynamicThemeEngine.resolveColorScheme(context, settings, darkTheme)
                }
                val seed = androidx.compose.ui.graphics.Color(settings.themeSeedColor or -0x1000000)
                val style = ThemePaletteStyle.fromId(settings.themePaletteStyle)
                DynamicThemeEngine.createColorScheme(seedColor = seed, isDark = darkTheme, paletteStyle = style)
            } else {
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }

        val effectiveDepthMode = depthMode ?: settings?.themeDepthMode ?: AppSettings.DEFAULT_THEME_DEPTH_MODE
        if (effectiveDepthMode) {
            baseScheme.elevatedSovereignPop(
                isDark = darkTheme,
                darkBgTone = darkBgTone ?: settings?.themeDarkBgTone ?: AppSettings.DEFAULT_THEME_DARK_BG_TONE,
                darkCardTone = darkCardTone ?: settings?.themeDarkCardTone ?: AppSettings.DEFAULT_THEME_DARK_CARD_TONE,
                darkBorderTone = darkBorderTone ?: settings?.themeDarkBorderTone ?: AppSettings.DEFAULT_THEME_DARK_BORDER_TONE,
                lightBgTone = lightBgTone ?: settings?.themeLightBgTone ?: AppSettings.DEFAULT_THEME_LIGHT_BG_TONE,
                lightBorderTone = lightBorderTone ?: settings?.themeLightBorderTone ?: AppSettings.DEFAULT_THEME_LIGHT_BORDER_TONE,
            )
        } else {
            baseScheme
        }
    }

    MaterialTheme(colorScheme = resolvedColorScheme, typography = Typography, content = content)
}
