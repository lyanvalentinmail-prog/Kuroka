package app.kuroka.ui.look

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kuroka.api.AppSettings
import app.kuroka.api.Look
import app.kuroka.ui.theme.Inter
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.LocalLookKind
import app.kuroka.ui.theme.LocalOled
import app.kuroka.ui.theme.LocalSettings
import app.kuroka.ui.theme.accentColor

/*
 * The Immersive look: black pages, glass cards, Inter, and a type scale named for what a line of
 * text is for (a large title, a headline, a caption) instead of for a Material role. The tokens —
 * the greys, the glass's fill and edge, the corner radii, the gutters — are read from here by
 * every screen, so the look can be changed in one place.
 *
 * The token set follows Orpheon's Immersive look (GPL-3.0, © kota-zo,
 * base/app/src/main/java/com/music/spotui/ui/immersive/ImmersiveTheme.kt); the colours are
 * Kuroka's, around its own rose.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

/** The Immersive look's colours and sizes, as one value the screens read. */
data class ImmersiveTokens(
    val page: Color,
    val raised: Color,
    val separator: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val glassFill: Color,
    val glassEdge: Color,
    val destructive: Color,
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val gutter: Dp,
    val rowHeight: Dp,
    val bottomChrome: Dp,
)

/** The tokens of the look in force (black page, glass, Inter). */
val LocalImmersive = staticCompositionLocalOf { ImmersiveDefaults }

private val ImmersiveDefaults = ImmersiveTokens(
    page = Color.Black,
    raised = Color(0xFF1C1C1E),
    separator = Color(0x1AFFFFFF),
    text = Color.White,
    textSecondary = Color(0x99FFFFFF),
    textTertiary = Color(0x61FFFFFF),
    accent = Color(0xFFFF4F7B),
    glassFill = Color(0xE02C2C34),
    glassEdge = Color(0x1FFFFFFF),
    destructive = Color(0xFFFF453A),
    cornerSmall = 6.dp,
    cornerMedium = 10.dp,
    cornerLarge = 14.dp,
    gutter = 20.dp,
    rowHeight = 60.dp,
    bottomChrome = 170.dp,
)

/** The Immersive look's type scale: what a line is for, and how big and heavy it is. */
object ImmersiveType {
    val largeTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
    val subhead = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
}

/** The body font of the Immersive look, for a composable that sets its own style. */
val ImmersiveFont: FontFamily get() = Inter

/**
 * Draws everything inside in the Immersive look: a black page, glass surfaces, and the tokens
 * above. Material 3's roles are filled too, so a Material component (a sheet, a slider, a switch)
 * draws in the look without a screen having to know which look it is in.
 */
@Composable
fun ImmersiveTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val accent = accentColor(settings)
    val tokens = ImmersiveDefaults.copy(
        page = if (settings.oledBlack) Color.Black else Color(0xFF0C0C0E),
        raised = if (settings.oledBlack) Color(0xFF1C1C1E) else Color(0xFF222226),
        accent = accent,
    )
    CompositionLocalProvider(
        LocalImmersive provides tokens,
        LocalAccent provides accent,
        LocalOled provides settings.oledBlack,
        LocalSettings provides settings,
        LocalLookKind provides Look.IMMERSIVE,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent,
                onPrimary = Color.Black,
                primaryContainer = tokens.raised,
                onPrimaryContainer = tokens.text,
                secondary = tokens.textSecondary,
                onSecondary = Color.Black,
                secondaryContainer = tokens.raised,
                onSecondaryContainer = tokens.text,
                tertiary = accent,
                onTertiary = Color.Black,
                background = tokens.page,
                onBackground = tokens.text,
                surface = tokens.page,
                onSurface = tokens.text,
                surfaceContainerLowest = tokens.page,
                surfaceContainerLow = tokens.page,
                surfaceContainer = tokens.raised,
                surfaceContainerHigh = tokens.raised,
                surfaceContainerHighest = tokens.raised,
                surfaceVariant = tokens.raised,
                onSurfaceVariant = tokens.textSecondary,
                inverseSurface = Color.White,
                inverseOnSurface = Color.Black,
                outline = tokens.separator,
                outlineVariant = tokens.separator,
                error = tokens.destructive,
                onError = Color.Black,
                scrim = Color.Black,
                surfaceTint = Color.Transparent,
            ),
            typography = androidx.compose.material3.Typography(
                displayLarge = ImmersiveType.largeTitle,
                displayMedium = ImmersiveType.largeTitle,
                displaySmall = ImmersiveType.title,
                headlineLarge = ImmersiveType.title,
                headlineMedium = ImmersiveType.title,
                headlineSmall = ImmersiveType.headline,
                titleLarge = ImmersiveType.headline,
                titleMedium = ImmersiveType.bodyStrong,
                titleSmall = ImmersiveType.subhead,
                bodyLarge = ImmersiveType.body,
                bodyMedium = ImmersiveType.subhead,
                bodySmall = ImmersiveType.caption,
                labelLarge = ImmersiveType.bodyStrong,
                labelMedium = ImmersiveType.subhead,
                labelSmall = ImmersiveType.caption,
            ),
            content = content,
        )
    }
}

/**
 * A glass surface: the look's fill, its hairline edge and its corner. The screens use it for a
 * card, a bar and a sheet; the frost behind it is a gradient of the page, not a real blur (a blur
 * of a scrolling poster grid costs frames a phone cannot spare).
 */
@Composable
fun Modifier.glass(
    shape: RoundedCornerShape = RoundedCornerShape(LocalImmersive.current.cornerMedium),
    fill: Color = LocalImmersive.current.glassFill,
    edge: Color = LocalImmersive.current.glassEdge,
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(fill.copy(alpha = fill.alpha * 0.94f), fill.copy(alpha = fill.alpha * 0.82f))
        )
    )
    .border(1.dp, edge, shape)

/** The look's own accent, for a screen that draws with it (a progress bar, a badge). */
@Composable
fun immersiveAccent(): Color = LocalImmersive.current.accent
