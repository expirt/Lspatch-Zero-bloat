package org.lsposed.lspatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

val StatusSuccess = Color(0xFF146C43)
val StatusOnSuccess = Color(0xFFFFFFFF)
val StatusWarning = Color(0xFF8F4E00)
val StatusOnWarning = Color(0xFFFFFFFF)

val ZeroBloatLightColorScheme = lightColorScheme(
    primary = Color(0xFF005446),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0D6E5D),
    onPrimaryContainer = Color(0xFF9BEDD7),
    secondary = Color(0xFF47645C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EADF),
    onSecondaryContainer = Color(0xFF4D6A62),
    tertiary = Color(0xFF773729),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF944E3E),
    onTertiaryContainer = Color(0xFFFFD4CB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7FAF7),
    onBackground = Color(0xFF181C1B),
    surface = Color(0xFFF7FAF7),
    surfaceDim = Color(0xFFD7DBD8),
    surfaceBright = Color(0xFFF7FAF7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F1),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5E9E6),
    surfaceContainerHighest = Color(0xFFE0E3E0),
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDAE5E0),
    onSurfaceVariant = Color(0xFF3E4945),
    outline = Color(0xFF6E7975),
    outlineVariant = Color(0xFFBEC9C4),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEEF2EF),
    inversePrimary = Color(0xFF84D6C1),
)

val ZeroBloatDarkColorScheme = darkColorScheme(
    primary = Color(0xFF84D6C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF0D6E5D),
    onPrimaryContainer = Color(0xFF9BEDD7),
    secondary = Color(0xFFADCDC3),
    onSecondary = Color(0xFF18352E),
    secondaryContainer = Color(0xFF324F47),
    onSecondaryContainer = Color(0xFF9FBFB5),
    tertiary = Color(0xFFFFB4A3),
    onTertiary = Color(0xFF561E12),
    tertiaryContainer = Color(0xFF944E3E),
    onTertiaryContainer = Color(0xFFFFD4CB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E0),
    surface = Color(0xFF101413),
    surfaceDim = Color(0xFF101413),
    surfaceBright = Color(0xFF363A38),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF181C1B),
    surfaceContainer = Color(0xFF1C201F),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF313634),
    onSurface = Color(0xFFE0E3E0),
    surfaceVariant = Color(0xFF3E4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF88938F),
    outlineVariant = Color(0xFF3E4945),
    inverseSurface = Color(0xFFE0E3E0),
    inverseOnSurface = Color(0xFF2D3130),
    inversePrimary = Color(0xFF056B5A),
)

/**
 * Collapses every background role to true black, for OLED panels.
 *
 * Applied on top of whichever dark scheme is in use — including a dynamic one — so the accent
 * colours the user's wallpaper produced survive.
 */
fun ColorScheme.toAmoled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF101010),
        surfaceContainerHigh = Color(0xFF161616),
        surfaceContainerHighest = Color(0xFF1C1C1C),
    )
