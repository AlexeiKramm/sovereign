package fi.ak.smartvoice.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Material 3 Dark Color Scheme Tokens
// (Elevation-based tonal mapping, avoiding harsh pure black #000000 per M3 spec)
// ============================================================================
val DarkPrimary = Color(0xFFAEC6FF)
val DarkOnPrimary = Color(0xFF002E6C)
val DarkPrimaryContainer = Color(0xFF004397)
val DarkOnPrimaryContainer = Color(0xFFD8E2FF)

val DarkSecondary = Color(0xFFBEC6DC)
val DarkOnSecondary = Color(0xFF283041)
val DarkSecondaryContainer = Color(0xFF3E4759)
val DarkOnSecondaryContainer = Color(0xFFDAE2F9)

val DarkTertiary = Color(0xFFDDBCE0)
val DarkOnTertiary = Color(0xFF3F2844)
val DarkTertiaryContainer = Color(0xFF573E5B)
val DarkOnTertiaryContainer = Color(0xFFFAD8FC)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF111318)
val DarkOnBackground = Color(0xFFE2E2E9)
val DarkSurface = Color(0xFF111318)
val DarkOnSurface = Color(0xFFE2E2E9)
val DarkSurfaceVariant = Color(0xFF44474F)
val DarkOnSurfaceVariant = Color(0xFFC4C6D0)

val DarkSurfaceContainerLowest = Color(0xFF0C0E13)
val DarkSurfaceContainerLow = Color(0xFF191C20)
val DarkSurfaceContainer = Color(0xFF1D2024)
val DarkSurfaceContainerHigh = Color(0xFF282A2F)
val DarkSurfaceContainerHighest = Color(0xFF33353A)

val DarkOutline = Color(0xFF8E9099)
val DarkOutlineVariant = Color(0xFF44474F)

// ============================================================================
// Material 3 Light Color Scheme Tokens
// ============================================================================
val LightPrimary = Color(0xFF1B60C4)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD8E2FF)
val LightOnPrimaryContainer = Color(0xFF001A41)

val LightSecondary = Color(0xFF565E71)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDAE2F9)
val LightOnSecondaryContainer = Color(0xFF131C2C)

val LightTertiary = Color(0xFF705574)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFAD8FC)
val LightOnTertiaryContainer = Color(0xFF29132E)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFDFBFF)
val LightOnBackground = Color(0xFF1A1C1E)
val LightSurface = Color(0xFFFDFBFF)
val LightOnSurface = Color(0xFF1A1C1E)
val LightSurfaceVariant = Color(0xFFE1E2EC)
val LightOnSurfaceVariant = Color(0xFF44474F)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF5F3FB)
val LightSurfaceContainer = Color(0xFFEFEBF3)
val LightSurfaceContainerHigh = Color(0xFFE9E5ED)
val LightSurfaceContainerHighest = Color(0xFFE3DFE7)

val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC4C6D0)

// ============================================================================
// Brand Accents & Semantic Tonal Badges
// ============================================================================
val PrimaryBlue = LightPrimary
val PrimaryBlueLight = DarkPrimary
val SecondaryTeal = LightSecondary
val TertiaryAmber = LightTertiary

// Semantic Icon Accent Tones (Material tonal harmonies)
val BadgeBlue = Color(0xFF1B60C4)
val BadgeViolet = Color(0xFF7B52AB)
val BadgeGreen = Color(0xFF2E7D32)
val BadgeOrange = Color(0xFFE65100)
val BadgeRose = Color(0xFFC2185B)
val BadgeCyan = Color(0xFF00838F)
val BadgeSlate = Color(0xFF5A6065)
val BadgeIndigo = Color(0xFF3F51B5)

// Compatibility aliases for legacy Settings styling
val SettingsDarkBackground = DarkBackground
val SettingsDarkSurface = DarkSurface
val SettingsDarkCard = DarkSurfaceContainer
val SettingsDarkCardElevated = DarkSurfaceContainerHigh
val SettingsDarkBorder = DarkOutlineVariant
val SettingsDarkDivider = DarkOutlineVariant

val PrimaryIndigo = PrimaryBlue
val PrimaryIndigoLight = PrimaryBlueLight

