package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


private val DarkColorScheme =
  darkColorScheme(
    primary = TerminalGreen,
    onPrimary = Color(0xFF04200E),
    primaryContainer = Color(0xFF003816),
    onPrimaryContainer = Color(0xFF86FBB3),
    secondary = TerminalCyan,
    onSecondary = Color(0xFF002026),
    secondaryContainer = Color(0xFF004452),
    onSecondaryContainer = Color(0xFF80F1FF),
    tertiary = TerminalPurple,
    onTertiary = Color(0xFF28004B),
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    error = TerminalRed
  )

private val LightColorScheme =
  lightColorScheme(
    primary = TerminalGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = TerminalCyanDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = TerminalPurple,
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = TerminalRed
  )

@Composable
fun V52Theme(
  darkTheme: Boolean = true, // Default to sleek dark cyber aesthetic
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

