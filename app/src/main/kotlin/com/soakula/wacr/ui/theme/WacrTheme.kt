package com.soakula.wacr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B57), onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2DA), onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4A635B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DD), onSecondaryContainer = Color(0xFF062018)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5BF), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005141), onPrimaryContainer = Color(0xFF9CF2DA),
    secondary = Color(0xFFB1CCC0), onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF334B41), onSecondaryContainer = Color(0xFFCDE8DD)
)
private val AppShapes = Shapes(medium = RoundedCornerShape(16.dp))
private val AppTypography = Typography()

internal fun ThemeChoice.isDark(systemDark: Boolean) = when (this) {
    ThemeChoice.SYSTEM -> systemDark
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}

@Composable
fun WacrTheme(
    appearance: Appearance,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val dark = appearance.theme.isDark(systemDark)
    val context = LocalContext.current
    val colors = if (appearance.dynamicColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
