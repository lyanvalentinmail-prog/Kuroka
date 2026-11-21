package app.kuroka.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.Accent
import app.kuroka.api.Look
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The look: which of the two looks the app wears (Immersive, the dark glass one, and Classic, the
 * Material one), the accent colour, the wallpaper's colour instead of it, pure black pages, the
 * language, whether adult anime is shown, and the order the home page's rows come in.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

/** The languages the app can be asked for (the words themselves come from the phone's locale). */
private val LANGUAGES = listOf(
    "system" to "The phone's own",
    "en" to "English",
    "es" to "Español",
    "pt-BR" to "Português (Brasil)",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "ru" to "Русский",
    "ar" to "العربية",
    "ja" to "日本語",
)

/** The home page's rows, in the order they are drawn, and what each is called. */
private val SECTIONS = listOf(
    "continue" to "Go on watching",
    "trending" to "Trending now",
    "seasonal" to "This season",
    "popular" to "Most popular",
    "airing" to "Airing soon",
    "top" to "Highest rated",
)

@Composable
fun DisplayPage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val accent = LocalAccent.current

    SettingsPage(title = "Display", onBack = onBack) {
        SettingGroup("The look")
        ChoiceRow(
            title = "How the app looks",
            subtitle = "Immersive is dark glass over the posters; Classic is Material's own surfaces.",
            options = Look.entries.map { it.label },
            selected = settings.look.label,
            onPick = { label -> Look.entries.firstOrNull { it.label == label }?.let(model::setLook) },
            perRow = 2,
        )

        SettingGroup("The colour")
        SwitchRow(
            title = "The wallpaper's colour",
            subtitle = "Material You: the accent follows the phone's wallpaper (Android 12 and later).",
            on = settings.dynamicColor,
            onToggle = model::setDynamicColor,
        )
        if (!settings.dynamicColor) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    text = "Or one of these",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Accent.entries.forEach { option ->
                        val on = option == settings.accent
                        Box(
                            modifier = Modifier
                                .size(if (on) 40.dp else 34.dp)
                                .clip(CircleShape)
                                .background(Color(option.argb))
                                .clickable { model.setAccent(option) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = option.label,
                                    tint = Color.Black,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = settings.accent.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        SwitchRow(
            title = "Pure black pages",
            subtitle = "For an OLED phone: the pages are black, not dark grey, and the glass is thinner.",
            on = settings.oledBlack,
            onToggle = model::setOledBlack,
        )

        SettingGroup("The words")
        ChoiceRow(
            title = "The app's language",
            subtitle = "The anime's titles come from the catalog whatever this is.",
            options = LANGUAGES.map { it.second },
            selected = LANGUAGES.firstOrNull { it.first == settings.language }?.second ?: LANGUAGES[0].second,
            onPick = { label ->
                LANGUAGES.firstOrNull { it.second == label }?.let { model.setLanguage(it.first) }
            },
            perRow = 2,
        )

        SettingGroup("What the catalog shows")
        SwitchRow(
            title = "Anime for adults",
            subtitle = "Show the anime AniList marks as adult in searches and on pages. Off by default.",
            on = settings.nsfw,
            onToggle = model::setNsfw,
        )

        SettingGroup("The home page's rows")
        SettingNote("The rows come in this order; the ones at the foot can be taken off.")
        val order = settings.homeSections.ifEmpty { SECTIONS.map { it.first } }
        order.forEachIndexed { index, id ->
            val label = SECTIONS.firstOrNull { it.first == id }?.second ?: id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { model.moveSection(id, up = true) },
                    enabled = index > 0,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = "Higher",
                        tint = if (index > 0) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { model.moveSection(id, up = false) },
                    enabled = index < order.size - 1,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Lower",
                        tint = if (index < order.size - 1) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
