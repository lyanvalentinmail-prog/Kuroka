package app.kuroka.ui.look

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kuroka.api.AppSettings
import app.kuroka.api.Look
import app.kuroka.host.KurokaHost
import app.kuroka.ui.theme.ClassicTheme
import java.util.Locale

/*
 * The look the app is drawn in, chosen in Settings, Display: Immersive (black, glass, Inter) or
 * Classic (Material rows over greys). One composable at the root reads the settings and hands the
 * tree to the theme of that look, so no screen ever asks which look it is in — it reads a token
 * (`LocalImmersive.current.accent`, `MaterialTheme.colorScheme.surface`) and the look decides.
 *
 * The structure follows Orpheon's ProvideLook (GPL-3.0, © kota-zo,
 * base/app/src/main/java/com/music/spotui/ui/look/Look.kt).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

/** Draws everything inside in the look the settings ask for. */
@Composable
fun KurokaTheme(content: @Composable () -> Unit) {
    val settings by KurokaHost.settings.collectAsState()
    // The app's own language, applied once here so every screen and every dialog has it.
    ApplyLanguage(settings.language)
    when (settings.look) {
        Look.IMMERSIVE -> ImmersiveTheme(settings, content)
        Look.CLASSIC -> ClassicTheme(settings, content)
    }
}

/** Draws everything inside in [look], whatever the settings say (the look preview uses it). */
@Composable
fun KurokaThemeIn(look: Look, settings: AppSettings, content: @Composable () -> Unit) {
    when (look) {
        Look.IMMERSIVE -> ImmersiveTheme(settings.copy(look = look), content)
        Look.CLASSIC -> ClassicTheme(settings.copy(look = look), content)
    }
}

/** The settings, for a composable that needs one value without a view model. */
@Composable
fun rememberSettings(): AppSettings {
    val settings by KurokaHost.settings.collectAsState()
    return settings
}

/**
 * Sets the app's language ("system", or a tag such as "es" or "pt-BR"). AppCompat keeps it across
 * restarts and puts it on the app's own resources, so a screen needs no `Locale` of its own; the
 * layout direction follows it, as a right-to-left language lays the whole tree out the other way.
 */
@Composable
private fun ApplyLanguage(tag: String) {
    val wanted = if (tag == "system" || tag.isBlank()) null else tag
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { null }
    if (current == wanted) return
    val direction = wanted?.let { rtlOf(it) } ?: LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        // Applied outside the composition: setting a locale recreates the activity, and doing it
        // while the tree is being built would tear down what is being drawn.
        LaunchedEffect(wanted) {
            runCatching {
                AppCompatDelegate.setApplicationLocales(
                    if (wanted == null) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.create(Locale.forLanguageTag(wanted))
                )
            }
        }
    }
}

/** True when a language tag is written right to left. */
private fun rtlOf(tag: String): LayoutDirection {
    val language = Locale.forLanguageTag(tag).language
    return if (language in listOf("ar", "he", "iw", "fa", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr
}
