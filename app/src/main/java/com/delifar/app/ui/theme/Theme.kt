package com.delifar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ForestPrimary = Color(0xFF183A37)
val ForestContainer = Color(0xFFD6EAE7)
val AmberAccent = Color(0xFFD97706)
val AmberContainer = Color(0xFFFEF3C7)
val TerracottaError = Color(0xFFB91C1C)
val TerracottaContainer = Color(0xFFFEE2E2)
val SlateSurface = Color(0xFFF6F5F1)
val CardWhite = Color(0xFFFFFFFF)
val InkPrimary = Color(0xFF141B1A)
val InkSecondary = Color(0xFF51605E)
val OutlineSubtle = Color(0xFFD4DDD9)

val SuccessGreen = Color(0xFF15803D)
val SuccessContainer = Color(0xFFDCFCE7)
val InfoBlue = Color(0xFF1D4ED8)
val InfoContainer = Color(0xFFDBEAFE)

private val LightColors = lightColorScheme(
    primary = ForestPrimary,
    onPrimary = Color.White,
    primaryContainer = ForestContainer,
    onPrimaryContainer = Color(0xFF0B2220),
    secondary = AmberAccent,
    onSecondary = Color.White,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = InfoBlue,
    onTertiary = Color.White,
    tertiaryContainer = InfoContainer,
    onTertiaryContainer = Color(0xFF1E3A8A),
    background = SlateSurface,
    onBackground = InkPrimary,
    surface = CardWhite,
    onSurface = InkPrimary,
    surfaceVariant = Color(0xFFECEFE9),
    onSurfaceVariant = InkSecondary,
    outline = OutlineSubtle,
    error = TerracottaError,
    onError = Color.White,
    errorContainer = TerracottaContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3C8),
    onPrimary = Color(0xFF0A2421),
    primaryContainer = Color(0xFF1E4B47),
    onPrimaryContainer = Color(0xFFD6EAE7),
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF3E2200),
    secondaryContainer = Color(0xFF5E3905),
    onSecondaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF101514),
    onBackground = Color(0xFFE9F0EE),
    surface = Color(0xFF171E1D),
    onSurface = Color(0xFFE9F0EE),
    surfaceVariant = Color(0xFF232D2B),
    onSurfaceVariant = Color(0xFFA9B8B5),
    outline = Color(0xFF354441),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF5A0B0B),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val DeliFarTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun DeliFarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = DeliFarTypography,
        content = content
    )
}
