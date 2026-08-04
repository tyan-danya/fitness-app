package com.dtyan.fitdiary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Фирменная палитра всегда своя (dynamic color отключён сознательно):
 * акценты разделов — часть характера приложения.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF16A34A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = Color(0xFFEA8A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFEDD5),
    onSecondaryContainer = Color(0xFF7C2D12),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E8FF),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = Color(0xFFF6FAF7),
    onBackground = Color(0xFF17201A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17201A),
    surfaceVariant = Color(0xFFE6EFE8),
    onSurfaceVariant = Color(0xFF48544B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFDFA),
    surfaceContainer = Color(0xFFF0F6F1),
    surfaceContainerHigh = Color(0xFFEAF2EC),
    surfaceContainerHighest = Color(0xFFE4EDE6),
    outline = Color(0xFF78857B),
    outlineVariant = Color(0xFFCADACD),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF1C3A28),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF3D2400),
    secondaryContainer = Color(0xFF3D2E14),
    onSecondaryContainer = Color(0xFFFDE68A),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF2A1065),
    tertiaryContainer = Color(0xFF2E2547),
    onTertiaryContainer = Color(0xFFE9D5FF),
    background = Color(0xFF0E1410),
    onBackground = Color(0xFFDEE7E0),
    surface = Color(0xFF131A15),
    onSurface = Color(0xFFDEE7E0),
    surfaceVariant = Color(0xFF232D26),
    onSurfaceVariant = Color(0xFFA8B5AB),
    surfaceContainerLowest = Color(0xFF0B100C),
    surfaceContainerLow = Color(0xFF151C17),
    surfaceContainer = Color(0xFF1A231C),
    surfaceContainerHigh = Color(0xFF202B23),
    surfaceContainerHighest = Color(0xFF26322A),
    outline = Color(0xFF7E8B81),
    outlineVariant = Color(0xFF37423A),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

/** Крупные дружелюбные скругления. */
private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FitDiaryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val accents = if (darkTheme) DarkFitAccents else LightFitAccents
    CompositionLocalProvider(LocalFitAccents provides accents) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FitTypography,
            shapes = FitShapes,
            content = content,
        )
    }
}
