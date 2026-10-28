package com.batraamul.cycling.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class Scene { MOUNTAINS, MONSOON, DUNES, COAST, NEON, AUTUMN, AURORA, VELODROME }

/**
 * One visual theme. All eight share the same screens and components; only these values differ.
 * The artwork is drawn from [scene] + [sky]/[layers] at runtime (vector, no images in the APK).
 */
data class ThemeSpec(
    val id: Int,
    val name: String,
    val tagline: String,
    val dark: Boolean,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val sky: List<Color>,
    val layers: List<Color>,
    val accent: Color,
    val scene: Scene,
)

val AppThemes: List<ThemeSpec> = listOf(
    ThemeSpec(0, "Himalayan Dawn", "Snow peaks at first light", false,
        Color(0xFF3D5A80), Color(0xFFEE6C4D), Color(0xFF98C1D9), Color(0xFFFFF8F2), Color(0xFFFFFFFF), Color(0xFF1F2A3C),
        listOf(Color(0xFFFFD6A5), Color(0xFFFFADAD), Color(0xFFBDB2FF)),
        listOf(Color(0xFF8E9AAF), Color(0xFF5E6B8C), Color(0xFF3D4A66)), Color(0xFFFFF4E0), Scene.MOUNTAINS),
    ThemeSpec(1, "Monsoon Valley", "Green hills after the rain", false,
        Color(0xFF2D6A4F), Color(0xFF52B788), Color(0xFF74C69D), Color(0xFFF2F8F3), Color(0xFFFFFFFF), Color(0xFF173326),
        listOf(Color(0xFF9DB4C0), Color(0xFFC2DFE3), Color(0xFFE0FBFC)),
        listOf(Color(0xFF95D5B2), Color(0xFF52B788), Color(0xFF2D6A4F)), Color(0xFFFFFFFF), Scene.MONSOON),
    ThemeSpec(2, "Thar Dunes", "Golden desert evenings", false,
        Color(0xFFA0522D), Color(0xFFE09F3E), Color(0xFF9E2A2B), Color(0xFFFFF8EC), Color(0xFFFFFFFF), Color(0xFF3B2412),
        listOf(Color(0xFFFFF1C1), Color(0xFFFFD380), Color(0xFFF6A15A)),
        listOf(Color(0xFFF3C177), Color(0xFFE09F3E), Color(0xFFB8732D)), Color(0xFFFFFBEA), Scene.DUNES),
    ThemeSpec(3, "Coastal Breeze", "Sea air on a Konkan road", false,
        Color(0xFF006D77), Color(0xFFE29578), Color(0xFF83C5BE), Color(0xFFF1FAFB), Color(0xFFFFFFFF), Color(0xFF0B2E33),
        listOf(Color(0xFFE6F8FB), Color(0xFFCAF0F8), Color(0xFF90E0EF)),
        listOf(Color(0xFF48CAE4), Color(0xFF0096C7), Color(0xFF023E8A)), Color(0xFFFFF3B0), Scene.COAST),
    ThemeSpec(4, "Midnight Neon", "City lights, empty roads", true,
        Color(0xFFFF4FA3), Color(0xFF00E5FF), Color(0xFFB388FF), Color(0xFF0B0B1A), Color(0xFF16162B), Color(0xFFECE9FF),
        listOf(Color(0xFF0B0B1A), Color(0xFF2A0E4A), Color(0xFF6A1B6E)),
        listOf(Color(0xFF3A1C71), Color(0xFF1F1147), Color(0xFF0E0A24)), Color(0xFFFF4FA3), Scene.NEON),
    ThemeSpec(5, "Autumn Trail", "Falling leaves, quiet lanes", false,
        Color(0xFF9C2C1C), Color(0xFFE07A1F), Color(0xFF6B4226), Color(0xFFFBF4EC), Color(0xFFFFFFFF), Color(0xFF331A10),
        listOf(Color(0xFFFFE5C2), Color(0xFFFFC98B), Color(0xFFF4A261)),
        listOf(Color(0xFFE07A1F), Color(0xFFBC4B26), Color(0xFF7F2A1A)), Color(0xFF5C3A21), Scene.AUTUMN),
    ThemeSpec(6, "Arctic Aurora", "Northern lights over ice", true,
        Color(0xFF4CE0B3), Color(0xFF7AA2FF), Color(0xFFB9F3E4), Color(0xFF07141F), Color(0xFF0F2131), Color(0xFFE3F4F8),
        listOf(Color(0xFF020B14), Color(0xFF0A2238), Color(0xFF123A52)),
        listOf(Color(0xFFDDEFF5), Color(0xFFB6D3DE), Color(0xFF7FA7B8)), Color(0xFF4CE0B3), Scene.AURORA),
    ThemeSpec(7, "Velodrome Sunset", "Banked curves, golden hour", true,
        Color(0xFFFF8C42), Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF1A1020), Color(0xFF261830), Color(0xFFFBEFE6),
        listOf(Color(0xFF2B1A3A), Color(0xFF8E3B5C), Color(0xFFFF8C42)),
        listOf(Color(0xFFB5523B), Color(0xFF6E2E3F), Color(0xFF3A1D2E)), Color(0xFFFFD166), Scene.VELODROME),
)

fun themeById(id: Int): ThemeSpec = AppThemes.getOrElse(id) { AppThemes[0] }

val LocalThemeSpec = staticCompositionLocalOf { AppThemes[0] }

private fun ThemeSpec.colorScheme(): ColorScheme {
    val onPrimary = if (dark) Color(0xFF14101A) else Color.White
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary,
        background = background, onBackground = onSurface, surface = surface, onSurface = onSurface,
        surfaceVariant = surface.copy(alpha = 1f).compositeOverMix(onSurface, 0.06f), onSurfaceVariant = onSurface.copy(alpha = 0.72f),
        primaryContainer = primary.copy(alpha = 0.22f), onPrimaryContainer = onSurface,
        secondaryContainer = secondary.copy(alpha = 0.22f), onSecondaryContainer = onSurface,
        outline = onSurface.copy(alpha = 0.25f), error = Color(0xFFFF6B6B),
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary,
        background = background, onBackground = onSurface, surface = surface, onSurface = onSurface,
        surfaceVariant = background.compositeOverMix(primary, 0.06f), onSurfaceVariant = onSurface.copy(alpha = 0.70f),
        primaryContainer = primary.copy(alpha = 0.14f), onPrimaryContainer = onSurface,
        secondaryContainer = secondary.copy(alpha = 0.16f), onSecondaryContainer = onSurface,
        outline = onSurface.copy(alpha = 0.20f), error = Color(0xFFB3261E),
    )
}

/** Simple colour mix: this colour moved [t] of the way towards [other]. */
fun Color.compositeOverMix(other: Color, t: Float) = Color(
    red + (other.red - red) * t, green + (other.green - green) * t, blue + (other.blue - blue) * t, 1f,
)

private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

/** Tabular (fixed-width) digits so numbers don't jiggle as they change. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)

@Composable
fun CyclingTheme(themeId: Int, content: @Composable () -> Unit) {
    val spec = themeById(themeId)
    CompositionLocalProvider(LocalThemeSpec provides spec) {
        MaterialTheme(colorScheme = spec.colorScheme(), typography = AppTypography, content = content)
    }
}
