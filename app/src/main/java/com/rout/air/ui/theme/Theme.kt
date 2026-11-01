package com.rout.air.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * RoutAir selalu gelap. Dynamic color sengaja
 * dimatikan supaya aksen cyan dan latar hitam konsisten di semua perangkat.
 */
private val RoutAirColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = OnAccent,
    primaryContainer = Color(0xFF00363D),
    onPrimaryContainer = Color(0xFF9CF1FF),
    secondary = CueCyan,
    onSecondary = OnAccent,
    secondaryContainer = Color(0xFF0E2A30),
    onSecondaryContainer = Color(0xFFB6ECF5),
    tertiary = WarningAmber,
    onTertiary = Color(0xFF1A1200),
    tertiaryContainer = Color(0xFF3A2A00),
    onTertiaryContainer = Color(0xFFFFE0A6),
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = SurfaceLow,
    surfaceContainerLow = SurfaceBase,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    outline = SurfaceBorder,
    outlineVariant = Divider,
    error = ErrorRed,
    onError = Color(0xFF1A0000),
    errorContainer = Color(0xFF3A1010),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC000000),
)

@Composable
fun RoutAirTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Compose juga dipakai di Service (overlay) yang tidak punya Activity.
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = RoutAirColorScheme,
        typography = Typography,
        content = content,
    )
}
