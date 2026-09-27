package de.lukasrunge.verweil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.lukasrunge.verweil.R

/*
 * Moss for staying, ochre for moving: the two states of the engine, everywhere in the app.
 * No dynamic colour, so the timeline reads the same on every phone.
 */

private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE3D6),
    onPrimaryContainer = Color(0xFF0E2A21),
    secondary = Color(0xFF8C5A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF7DFAE),
    onSecondaryContainer = Color(0xFF2E1F00),
    tertiary = Color(0xFF3E5F7A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2E4F5),
    onTertiaryContainer = Color(0xFF0B1D2C),
    background = Color(0xFFF4F6F2),
    onBackground = Color(0xFF1A211E),
    surface = Color(0xFFF4F6F2),
    onSurface = Color(0xFF1A211E),
    surfaceVariant = Color(0xFFDEE5DE),
    onSurfaceVariant = Color(0xFF424A45),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF1EC),
    surfaceContainer = Color(0xFFE8ECE6),
    surfaceContainerHigh = Color(0xFFE3E7E0),
    surfaceContainerHighest = Color(0xFFDDE2DB),
    outline = Color(0xFF727A74),
    outlineVariant = Color(0xFFC2C9C2),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FCFB9),
    onPrimary = Color(0xFF053828),
    primaryContainer = Color(0xFF1F4A3C),
    onPrimaryContainer = Color(0xFFBCEBD4),
    secondary = Color(0xFFEDBE62),
    onSecondary = Color(0xFF432C00),
    secondaryContainer = Color(0xFF5E4200),
    onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFFA7C8E4),
    onTertiary = Color(0xFF0E3349),
    tertiaryContainer = Color(0xFF264A62),
    onTertiaryContainer = Color(0xFFD2E4F5),
    background = Color(0xFF101714),
    onBackground = Color(0xFFDDE4DE),
    surface = Color(0xFF101714),
    onSurface = Color(0xFFDDE4DE),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceContainerLowest = Color(0xFF0B110E),
    surfaceContainerLow = Color(0xFF18201C),
    surfaceContainer = Color(0xFF1C2420),
    surfaceContainerHigh = Color(0xFF262E2A),
    surfaceContainerHighest = Color(0xFF313935),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF3F4943),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** The colours of the engine's two states, beyond the Material roles. */
@Immutable
data class StateColors(val staying: Color, val moving: Color)

val LocalStateColors = staticCompositionLocalOf { StateColors(Light.primary, Light.secondary) }

/** Bricolage Grotesque, slightly narrow, for the state line and headings; the system face for everything else. */
private fun bricolage(weight: Int, width: Float, opticalSize: Float) = Font(
    R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

private val Display = FontFamily(bricolage(weight = 600, width = 88f, opticalSize = 72f))
private val Heading = FontFamily(bricolage(weight = 600, width = 92f, opticalSize = 24f))

private val VerweilTypography = Typography().let { base ->
    base.copy(
        displayMedium = TextStyle(fontFamily = Display, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.02).em),
        displaySmall = TextStyle(fontFamily = Display, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.015).em),
        headlineSmall = TextStyle(fontFamily = Heading, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = Heading, fontSize = 22.sp, lineHeight = 28.sp),
        // Material's wide tracking suits all-caps labels, not the sentences Verweil explains itself in.
        bodyLarge = base.bodyLarge.copy(letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.copy(letterSpacing = 0.1.sp),
        bodySmall = base.bodySmall.copy(letterSpacing = 0.2.sp),
    )
}

@Composable
fun VerweilTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStateColors provides StateColors(staying = colors.primary, moving = colors.secondary),
    ) {
        MaterialTheme(colorScheme = colors, typography = VerweilTypography, content = content)
    }
}
