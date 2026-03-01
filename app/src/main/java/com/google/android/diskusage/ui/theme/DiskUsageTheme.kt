package com.google.android.diskusage.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ─── Light palette ────────────────────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4A40C8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF0B006E),
    secondary = Color(0xFF009097),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF97F2FA),
    onSecondaryContainer = Color(0xFF002B2E),
    tertiary = Color(0xFF8B4BC4),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6D9FF),
    onTertiaryContainer = Color(0xFF310059),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE5E0EC),
    onSurfaceVariant = Color(0xFF48454E),
    outline = Color(0xFF797587),
    outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = Color(0xFFF3EFF4),
    inversePrimary = Color(0xFFC4BEFF),
)

// ─── Dark palette ─────────────────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC4BEFF),
    onPrimary = Color(0xFF1600A2),
    primaryContainer = Color(0xFF3027B0),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = Color(0xFF4CD9E2),
    onSecondary = Color(0xFF00494D),
    secondaryContainer = Color(0xFF006B70),
    onSecondaryContainer = Color(0xFF97F2FA),
    tertiary = Color(0xFFE6B0FF),
    onTertiary = Color(0xFF52168B),
    tertiaryContainer = Color(0xFF6B30A7),
    onTertiaryContainer = Color(0xFFF6D9FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF48454E),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF48454E),
    inverseSurface = Color(0xFFE5E1E6),
    inverseOnSurface = Color(0xFF303033),
    inversePrimary = Color(0xFF4A40C8),
)

// ─── AMOLED pure-black variant of Dark ────────────────────────────────────────
private val AmoledColorScheme = DarkColorScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
)

/**
 * Top-level Compose theme for DiskUsage.
 *
 * @param darkTheme   follow system DayNight by default
 * @param amoled      use AMOLED black background (from ThemeHelper preference)
 * @param dynamicColor use Material You dynamic colors on Android 12+
 */
@Composable
fun DiskUsageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (amoled && darkTheme) dynamic.copy(background = Color.Black, surface = Color.Black) else dynamic
        }
        amoled && darkTheme -> AmoledColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
