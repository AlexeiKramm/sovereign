package io.github.alexeikramm.sovereign.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Palette intent
// ----------------------------------------------------------------------------
// Light theme: warm, creamy paper. Off-white backgrounds and toasted-beige
// surfaces with a deep lilac accent — "calm notebook", not "clinical grey".
// Dark theme: near-black with a violet cast, and the plan's lilac (#D0BCFF)
// as the accent.
//
// Every token below is a Material 3 role, so screens read them through
// `MaterialTheme.colorScheme` and only the values here change.
// ============================================================================

// ----------------------------------------------------------------------------
// Dark scheme — violet-tinted near-black with a lilac accent
// ----------------------------------------------------------------------------
val DarkPrimary = Color(0xFFD0BCFF)
val DarkOnPrimary = Color(0xFF3A2A5C)
val DarkPrimaryContainer = Color(0xFF53407A)
val DarkOnPrimaryContainer = Color(0xFFEADDFF)

val DarkSecondary = Color(0xFFCFC0D6)
val DarkOnSecondary = Color(0xFF362D3C)
val DarkSecondaryContainer = Color(0xFF4D4353)
val DarkOnSecondaryContainer = Color(0xFFEBDEF2)

val DarkTertiary = Color(0xFFE8C48E)
val DarkOnTertiary = Color(0xFF422C0D)
val DarkTertiaryContainer = Color(0xFF5C421F)
val DarkOnTertiaryContainer = Color(0xFFFFDDB0)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF17141C)
val DarkOnBackground = Color(0xFFEAE0E7)
val DarkSurface = Color(0xFF17141C)
val DarkOnSurface = Color(0xFFEAE0E7)
val DarkSurfaceVariant = Color(0xFF4A4453)
val DarkOnSurfaceVariant = Color(0xFFCCC2CE)

val DarkSurfaceContainerLowest = Color(0xFF120F16)
val DarkSurfaceContainerLow = Color(0xFF1F1B25)
val DarkSurfaceContainer = Color(0xFF231F2A)
val DarkSurfaceContainerHigh = Color(0xFF2E2937)
val DarkSurfaceContainerHighest = Color(0xFF393442)

val DarkOutline = Color(0xFF958E9E)
val DarkOutlineVariant = Color(0xFF4A4453)

// ----------------------------------------------------------------------------
// Light scheme — warm cream paper with a deep lilac accent
// ----------------------------------------------------------------------------
val LightPrimary = Color(0xFF6D4FA8)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFEADDFF)
val LightOnPrimaryContainer = Color(0xFF25114B)

val LightSecondary = Color(0xFF6B5D6E)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFF1E3F3)
val LightOnSecondaryContainer = Color(0xFF251A29)

val LightTertiary = Color(0xFF7A5C3E)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFBE0C7)
val LightOnTertiaryContainer = Color(0xFF2A1804)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFAF6EF)
val LightOnBackground = Color(0xFF2B2622)
val LightSurface = Color(0xFFFCF8F2)
val LightOnSurface = Color(0xFF2B2622)
val LightSurfaceVariant = Color(0xFFEAE1D4)
val LightOnSurfaceVariant = Color(0xFF4E453C)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F1E8)
val LightSurfaceContainer = Color(0xFFF2EBDD)
val LightSurfaceContainerHigh = Color(0xFFECE4D5)
val LightSurfaceContainerHighest = Color(0xFFE6DDCD)

val LightOutline = Color(0xFF857A6B)
val LightOutlineVariant = Color(0xFFD8CCB9)

// ============================================================================
// Semantic accent tones — used for icon badges and per-section colour coding
// (e.g. each stat tile on Home gets its own tint). Chosen as mid-tones so they
// read as solid tiles on both the cream light surface and the violet dark one.
// ============================================================================
val BadgeViolet = Color(0xFF7B52AB)
val BadgeBlue = Color(0xFF4A6FA5)
val BadgeGreen = Color(0xFF3F7D5A)
val BadgeCyan = Color(0xFF3A7C86)
val BadgeOrange = Color(0xFFB26A33)
val BadgeRose = Color(0xFFA85476)
