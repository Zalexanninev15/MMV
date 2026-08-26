package io.github.zalexanninev15.magicmusicv.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.zalexanninev15.magicmusicv.AppTheme

/**
 * MD3 theme for the app.
 *
 * Two things were wrong before. The colour scheme was chosen inline inside the screen
 * composable, so nothing else could see which mode had been resolved — and the window
 * decorations kept following the *system* light/dark setting rather than the app's, which is
 * what made Light unusable while the phone was in dark mode: status bar icons ended up light
 * on a light background. Resolution now lives here and in the activity, in one place.
 *
 * Dynamic colour is the default on API 31+, per MD3. The static schemes below are not dead
 * code: some OEM builds return a washed-out or badly paired dynamic scheme, and the toggle
 * gives a way off it without leaving the app.
 */

// Seed #FF5A3C — the orange in the launcher icon, run through the MD3 tonal palette.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF8F4C36),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBD1),
    onSecondaryContainer = Color(0xFF2C1510),
    tertiary = Color(0xFF6C5D2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E1A7),
    onTertiaryContainer = Color(0xFF231B00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231917),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED8),
    onSurfaceVariant = Color(0xFF53433F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1ED),
    surfaceContainer = Color(0xFFFFE9E4),
    surfaceContainerHigh = Color(0xFFFFE2DB),
    surfaceContainerHighest = Color(0xFFF9DCD5),
    outline = Color(0xFF85736E),
    outlineVariant = Color(0xFFD8C2BC),
    inverseSurface = Color(0xFF392E2B),
    inverseOnSurface = Color(0xFFFFEDE8),
    inversePrimary = Color(0xFFFFB5A0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB5A0),
    onPrimary = Color(0xFF561F0E),
    primaryContainer = Color(0xFF723522),
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFE7BDB2),
    onSecondary = Color(0xFF442A22),
    secondaryContainer = Color(0xFF5D4037),
    onSecondaryContainer = Color(0xFFFFDBD1),
    tertiary = Color(0xFFD8C58D),
    onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF534619),
    onTertiaryContainer = Color(0xFFF5E1A7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    // Neutral-dark surfaces rather than near-black: MD3 signals depth through tonal steps,
    // and a pure black base leaves the container levels with nowhere to go.
    background = Color(0xFF1A110F),
    onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF1A110F),
    onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    surfaceContainerLowest = Color(0xFF140C0A),
    surfaceContainerLow = Color(0xFF231917),
    surfaceContainer = Color(0xFF271D1B),
    surfaceContainerHigh = Color(0xFF322825),
    surfaceContainerHighest = Color(0xFF3D3230),
    outline = Color(0xFFA08C87),
    outlineVariant = Color(0xFF53433F),
    inverseSurface = Color(0xFFF1DFDA),
    inverseOnSurface = Color(0xFF392E2B),
    inversePrimary = Color(0xFF8F4C36),
)

/** Resolves the stored preference into a light/dark decision. */
@Composable
fun isDarkTheme(theme: AppTheme): Boolean = when (theme) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.DARK -> true
    AppTheme.LIGHT -> false
}

@Composable
fun MagicMusicTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = scheme) {
        // Explicit background surface. Scaffold paints its own container, but dialogs and
        // anything drawn outside it were inheriting the window background from the XML
        // theme, which follows the system setting rather than this one.
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
