package app.kuroka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import app.kuroka.api.Accent
import app.kuroka.api.AppSettings
import app.kuroka.api.Look

/*
 * The Classic look's theme: a Material 3 dark scheme over Kuroka's greys, with the accent the user
 * picked, pure black when they asked for it and the wallpaper's own colour when they asked for
 * that. Transparent bars, no contrast scrim, and a font scale that follows the phone.
 *
 * The colour roles and the way they are filled follow Orpheon (GPL-3.0, © kota-zo,
 * base/app/src/main/java/com/music/spotui/ui/theme/Theme.kt).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

/** The accent in force, for a screen that wants it on its own (a progress bar, a badge). */
val LocalAccent = staticCompositionLocalOf { Color(0xFFF1316F) }

/** True when the pages are pure black (an OLED phone). */
val LocalOled = staticCompositionLocalOf { false }

/** The settings the whole tree was drawn with (a screen may read one value without a view model). */
val LocalSettings = staticCompositionLocalOf { AppSettings() }

/** The colour scheme of the Classic look, over [accent] and [oled]. */
fun classicScheme(accent: Color, oled: Boolean) = darkColorScheme(
    primary = accent,
    onPrimary = if (oled) Color.Black else Color(0xFF121212),
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color(0xFF121212),
    secondaryContainer = GridBackground,
    onSecondaryContainer = AppText,
    tertiary = accent,
    onTertiary = Color.Black,
    background = if (oled) OledBackground else AppBackground,
    onBackground = AppText,
    surface = if (oled) OledBackground else AppBackground,
    onSurface = AppText,
    // The four steps a card, a row, a sheet and a dialog take above the page.
    surfaceContainerLowest = if (oled) OledBackground else AppBackground,
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF3E3E3E),
    surfaceVariant = HighBackground,
    onSurfaceVariant = AppMuted,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = AppFaint,
    outlineVariant = AppOutline,
    error = AppError,
    onError = Color.Black,
    scrim = Color.Black,
    // No tint over a surface: Kuroka's cards are greys, not the accent at 8%.
    surfaceTint = Color.Transparent,
)

/** A light scheme, for the phone that insists on one (Settings, Display, "Follow the phone"). */
fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF121212),
    surface = Color.White,
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF535353),
    outline = Color(0xFFB3B3B3),
    error = Color(0xFFB3261E),
    surfaceTint = Color.Transparent,
)

/** The accent's colour, from the wallpaper when [AppSettings.dynamicColor] asks for it. */
@Composable
fun accentColor(settings: AppSettings): Color {
    val context = LocalContext.current
    if (settings.dynamicColor) {
        val dynamic = runCatching {
            androidx.compose.material3.dynamicDarkColorScheme(context).primary
        }.getOrNull()
        if (dynamic != null) return dynamic
    }
    return Color(settings.accent.argb)
}

/**
 * Draws everything inside in the Classic look. The Immersive look has its own theme
 * (ui/look/ImmersiveTheme.kt); [app.kuroka.ui.look.KurokaTheme] picks between the two.
 */
@Composable
fun ClassicTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val accent = accentColor(settings)
    val oled = settings.oledBlack
    val scheme = classicScheme(accent, oled)

    // The bars are transparent and the content goes behind them: a player fills the whole window.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    // The phone's font size, without the density the phone reports (a large font must not make
    // every card larger too).
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale)

    CompositionLocalProvider(
        LocalAccent provides accent,
        LocalOled provides oled,
        LocalSettings provides settings,
        LocalLookKind provides Look.CLASSIC,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ClassicTypography,
            shapes = KurokaShapes,
            content = content,
        )
    }
}

/** The look in force, for a composable that draws differently in the two. */
val LocalLookKind = staticCompositionLocalOf { Look.CLASSIC }

/** True when the screens are drawn in the Immersive look. */
@Composable
fun isImmersive(): Boolean = LocalLookKind.current == Look.IMMERSIVE

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)
