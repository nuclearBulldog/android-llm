package com.slopware.androidllm.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slopware.androidllm.domain.AccessibilityConfig
import com.slopware.androidllm.domain.ThemeMode

val LocalAccessibility = staticCompositionLocalOf { AccessibilityConfig() }

@Composable
fun minTouchSize(): Dp = if (LocalAccessibility.current.largeTouchTargets) 52.dp else 44.dp

@Composable
fun resolveDarkTheme(themeMode: ThemeMode, systemIsDark: Boolean = isSystemInDarkTheme()): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> systemIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5CA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF0A2A54),
    secondary = Color(0xFF3E6373),
    secondaryContainer = Color(0xFFC2E8FA),
    tertiary = Color(0xFF6B5778),
    surface = Color(0xFFF8FAFC),
    surfaceContainerLow = Color(0xFFF3F6FA),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE4E9F2),
    surfaceContainerHighest = Color(0xFFDCE2ED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF06305F),
    primaryContainer = Color(0xFF17457D),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFA6CCE0),
    secondaryContainer = Color(0xFF254B5B),
    tertiary = Color(0xFFD6BEE4),
    surface = Color(0xFF0F1319),
    surfaceContainerLow = Color(0xFF151921),
    surfaceContainer = Color(0xFF1A1F28),
    surfaceContainerHigh = Color(0xFF232934),
    surfaceContainerHighest = Color(0xFF2C3340),
)

private val HighContrastLightColors = lightColorScheme(
    primary = Color(0xFF003884),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E2FF),
    onPrimaryContainer = Color(0xFF00112E),
    secondary = Color(0xFF103F52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8EAF9),
    onSecondaryContainer = Color(0xFF001822),
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E8F0),
    onSurfaceVariant = Color(0xFF14171C),
    surfaceContainerLow = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFECEFF5),
    surfaceContainerHigh = Color(0xFFE0E5EE),
    surfaceContainerHighest = Color(0xFFD3D9E5),
    outline = Color(0xFF1A1E26),
)

private val HighContrastDarkColors = darkColorScheme(
    primary = Color(0xFFD8E7FF),
    onPrimary = Color(0xFF00183B),
    primaryContainer = Color(0xFF00489B),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFC7ECFF),
    onSecondary = Color(0xFF001C27),
    secondaryContainer = Color(0xFF1C4F63),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1B2028),
    onSurfaceVariant = Color(0xFFF2F5FA),
    surfaceContainerLow = Color(0xFF0A0D12),
    surfaceContainer = Color(0xFF11151C),
    surfaceContainerHigh = Color(0xFF191F28),
    surfaceContainerHighest = Color(0xFF232B36),
    outline = Color(0xFFD6DCE8),
)

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accessibility: AccessibilityConfig = AccessibilityConfig(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode)
    val colorScheme = when {
        accessibility.highContrast -> if (darkTheme) HighContrastDarkColors else HighContrastLightColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * accessibility.fontScale.coerceIn(0.85f, 1.5f),
    )

    CompositionLocalProvider(
        LocalAccessibility provides accessibility,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
