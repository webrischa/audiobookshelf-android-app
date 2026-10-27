package com.audiobookshelf.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AbsDarkColors = darkColorScheme(
  primary = Color(0xFF1AD691),
  onPrimary = Color(0xFF06251B),
  primaryContainer = Color(0xFF14432F),
  onPrimaryContainer = Color(0xFFB9F5DA),
  secondary = Color(0xFF8AB4F8),
  background = Color(0xFF111311),
  onBackground = Color(0xFFE8EAE8),
  surface = Color(0xFF1A1D1A),
  onSurface = Color(0xFFE8EAE8),
  surfaceVariant = Color(0xFF262A26),
  onSurfaceVariant = Color(0xFFB6BDB6),
  outline = Color(0xFF3A403A),
  error = Color(0xFFFF6B6B)
)

@Composable
fun AbsTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = AbsDarkColors, content = content)
}
