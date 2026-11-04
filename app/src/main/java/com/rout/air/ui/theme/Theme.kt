package com.rout.air.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Palet RoutAir selalu gelap. Aksen dan latar AMOLED dipilih pengguna lewat
 * Setelan → Umum; dynamic color tetap dimatikan agar warna konsisten di semua
 * perangkat.
 */
private fun schemeFor(accent: AccentChoice, amoled: Boolean) =
    darkColorScheme(
        primary = accent.color,
        onPrimary = Color(0xFF001014),
        primaryContainer = lerp(SurfaceRaised, accent.color, 0.22f),
        onPrimaryContainer = lerp(accent.color, Color.White, 0.35f),
        secondary = accent.color,
        onSecondary = Color(0xFF001014),
        secondaryContainer = Color(0xFF0E2A30),
        onSecondaryContainer = Color(0xFFB6ECF5),
        tertiary = WarningAmber,
        onTertiary = Color(0xFF1A1200),
        tertiaryContainer = Color(0xFF3A2A00),
        onTertiaryContainer = Color(0xFFFFE0A6),
        background = if (amoled) Color.Black else SurfaceBase,
        onBackground = TextPrimary,
        surface = if (amoled) Color.Black else SurfaceBase,
        onSurface = TextPrimary,
        surfaceVariant = if (amoled) Color(0xFF0D0D0D) else SurfaceRaised,
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = if (amoled) Color.Black else SurfaceLow,
        surfaceContainerLow = if (amoled) Color.Black else SurfaceBase,
        surfaceContainer = if (amoled) Color(0xFF101010) else SurfaceRaised,
        surfaceContainerHigh = if (amoled) Color(0xFF151515) else SurfaceHigh,
        surfaceContainerHighest = if (amoled) Color(0xFF191919) else SurfaceHigh,
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
    val accent by Appearance.accent.collectAsState()
    val amoled by Appearance.amoled.collectAsState()
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
        colorScheme = schemeFor(accent, amoled),
        typography = Typography,
        content = content,
    )
}
