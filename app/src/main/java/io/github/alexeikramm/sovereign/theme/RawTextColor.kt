package io.github.alexeikramm.sovereign.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.RawTextColorRole

/**
 * The provisional (raw) text colour, from the user's chosen M3 role and opacity. **One definition**,
 * used by both the pill (which renders the text) and the Settings picker (which draws each role's
 * swatch), so the swatch and the rendered text can never disagree about what a role looks like.
 */
fun rawTextColor(role: RawTextColorRole, opacity: Float, scheme: ColorScheme): Color =
    role.resolve(scheme).copy(
        alpha = opacity.coerceIn(
            AppSettings.MIN_STREAMING_PREVIEW_OPACITY,
            AppSettings.MAX_STREAMING_PREVIEW_OPACITY,
        ),
    )

private fun RawTextColorRole.resolve(scheme: ColorScheme): Color = when (this) {
    RawTextColorRole.ON_SURFACE -> scheme.onSurface
    RawTextColorRole.ON_SURFACE_VARIANT -> scheme.onSurfaceVariant
    RawTextColorRole.OUTLINE -> scheme.outline
    RawTextColorRole.OUTLINE_VARIANT -> scheme.outlineVariant
    RawTextColorRole.PRIMARY -> scheme.primary
    RawTextColorRole.SECONDARY -> scheme.secondary
    RawTextColorRole.TERTIARY -> scheme.tertiary
}
