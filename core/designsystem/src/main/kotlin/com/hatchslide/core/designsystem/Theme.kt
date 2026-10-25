package com.hatchslide.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The HATCHSLIDE palette (spec section 5): sand, coral, sea-glass teal, deep ocean blue, sunset orange. */
object Palette {
    val Sand = Color(0xFFF3D9A4)
    val SandLight = Color(0xFFFFF3DC)
    val Cream = Color(0xFFFFFBF2)
    val Coral = Color(0xFFFF7F6B)
    val CoralDark = Color(0xFFD9584A)
    val SeaGlass = Color(0xFF5CC9B8)
    val SeaGlassDark = Color(0xFF2E9C8D)
    val Ocean = Color(0xFF0E3B5C)
    val OceanMid = Color(0xFF1F6F9C)
    val Sunset = Color(0xFFFF9A3D)
    val SunsetDark = Color(0xFFD9741B)
    val Ink = Color(0xFF2B2A33)
    val InkSoft = Color(0xFF5A5866)
    val Gold = Color(0xFFFFC93C)
    val Shadow = Color(0x33000000)
}

/** Player preferences that change how the UI draws (from Settings). */
@Immutable
data class UiPreferences(
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val leftHanded: Boolean = false,
    val colorBlindTiles: Boolean = false,
    val haptics: Boolean = true,
)

val LocalUiPreferences = staticCompositionLocalOf { UiPreferences() }

/** Interface sounds the components ask for; the app maps them to effects (spec section 7). */
enum class UiSound { TAP, TOGGLE, PAGE, OVERLAY }

val LocalUiSound = staticCompositionLocalOf<(UiSound) -> Unit> { {} }

@Immutable
data class HatchColors(
    val panel: Color,
    val panelBorder: Color,
    val onPanel: Color,
    val onPanelSoft: Color,
    val primaryButton: Color,
    val primaryButtonDark: Color,
    val secondaryButton: Color,
    val secondaryButtonDark: Color,
    val onButton: Color,
    val accent: Color,
    val danger: Color,
)

private val StandardColors = HatchColors(
    panel = Palette.Cream.copy(alpha = 0.86f),
    panelBorder = Color.White.copy(alpha = 0.65f),
    onPanel = Palette.Ink,
    onPanelSoft = Palette.InkSoft,
    primaryButton = Palette.Coral,
    primaryButtonDark = Palette.CoralDark,
    secondaryButton = Palette.SeaGlass,
    secondaryButtonDark = Palette.SeaGlassDark,
    onButton = Color.White,
    accent = Palette.Sunset,
    danger = Palette.CoralDark,
)

private val HighContrastColors = StandardColors.copy(
    panel = Color.White,
    panelBorder = Palette.Ink,
    onPanel = Color.Black,
    onPanelSoft = Palette.Ink,
    primaryButton = Color(0xFFB8321F),
    primaryButtonDark = Color(0xFF7A1F12),
    secondaryButton = Color(0xFF0B6B5E),
    secondaryButtonDark = Color(0xFF053F37),
)

val LocalHatchColors = staticCompositionLocalOf { StandardColors }

private val DisplayFamily = FontFamily(
    Font(R.font.fredoka, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.fredoka, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.fredoka, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val BodyFamily = FontFamily(
    Font(R.font.nunito, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.nunito, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

val HatchTypography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
)

private val HatchShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

private fun colorScheme(high: Boolean): ColorScheme = lightColorScheme(
    primary = if (high) Color(0xFFB8321F) else Palette.Coral,
    onPrimary = Color.White,
    secondary = if (high) Color(0xFF0B6B5E) else Palette.SeaGlass,
    onSecondary = Color.White,
    tertiary = Palette.Sunset,
    background = Palette.SandLight,
    onBackground = Palette.Ink,
    surface = Palette.Cream,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Sand,
    onSurfaceVariant = Palette.InkSoft,
    error = Palette.CoralDark,
)

@Composable
fun HatchslideTheme(preferences: UiPreferences = UiPreferences(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalUiPreferences provides preferences,
        LocalHatchColors provides if (preferences.highContrast) HighContrastColors else StandardColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme(preferences.highContrast),
            typography = HatchTypography,
            shapes = HatchShapes,
            content = content,
        )
    }
}
