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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
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
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.SettingRow
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel
import app.kuroka.video.SubtitlePreview

/*
 * The subtitles: how big they are, what colour, the box behind them and how see-through it is, the
 * edge around the letters, how far off the picture they run, whether a file's own styling wins, and
 * the language picked by itself when a stream offers several.
 *
 * Kuroka draws the subtitles itself, in Compose, over the picture rather than in the player's own
 * view — which is the only way an offset, a size in percent and a background alpha all work on a
 * side-loaded .ass file as well as on a stream's own track.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

/** The colours a user is offered, so no colour picker has to be built. */
private val TEXT_COLOURS = listOf(
    0xFFFFFFFF to "White",
    0xFFFFFF00 to "Yellow",
    0xFF00E5FF to "Cyan",
    0xFF8BFF9A to "Green",
    0xFFFF9AD5 to "Pink",
    0xFFFFB067 to "Orange",
    0xFF000000 to "Black",
)

private val BOX_COLOURS = listOf(
    0xFF000000 to "Black",
    0xFF141414 to "Charcoal",
    0xFF1E293B to "Slate",
    0xFF3B1F2B to "Wine",
    0xFFFFFFFF to "White",
)

/** The languages a stream's own tracks are matched against. */
private val LANGUAGES = listOf(
    "" to "Whatever the stream gives",
    "en" to "English",
    "es" to "Spanish",
    "pt" to "Portuguese",
    "fr" to "French",
    "de" to "German",
    "it" to "Italian",
    "ru" to "Russian",
    "ar" to "Arabic",
)

/** The edges the letters can have. */
private val EDGES = listOf(
    "outline" to "An outline",
    "shadow" to "A shadow",
    "none" to "Nothing",
)

@Composable
fun SubtitleSettingsPage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val style = settings.player.subtitles
    val accent = LocalAccent.current

    SettingsPage(title = "Subtitles", onBack = onBack) {
        // A line drawn as the settings say, over the black of a picture.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        ) {
            SubtitlePreview(
                text = "The subtitles look like this.",
                style = style,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Text(
                text = "A sample line",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
        }

        SettingGroup("How big they are")
        SliderRow(
            title = "The size",
            value = style.sizePercent.toFloat(),
            range = 50f..250f,
            valueLabel = "${style.sizePercent}%",
            onValue = { model.setSubtitleSize(it.toInt()) },
            subtitle = "Of the size the picture's width asks for; a tablet and a phone read the same.",
        )

        SettingGroup("The colour")
        ColourRow(
            title = "The letters",
            colours = TEXT_COLOURS,
            selected = style.textArgb,
            onPick = model::setSubtitleColor,
        )
        ColourRow(
            title = "The box behind them",
            colours = BOX_COLOURS,
            selected = style.backgroundArgb,
            onPick = model::setSubtitleBackground,
        )
        SliderRow(
            title = "How see-through the box is",
            value = style.backgroundAlpha.toFloat(),
            range = 0f..255f,
            valueLabel = if (style.backgroundAlpha == 0) "No box" else "${(style.backgroundAlpha * 100) / 255}%",
            onValue = { model.setSubtitleBackgroundAlpha(it.toInt()) },
        )
        ChoiceRow(
            title = "The edge around the letters",
            options = EDGES.map { it.second },
            selected = EDGES.firstOrNull { it.first == style.edge }?.second ?: EDGES[0].second,
            onPick = { label -> EDGES.firstOrNull { it.second == label }?.let { model.setSubtitleEdge(it.first) } },
            perRow = 3,
        )

        SettingGroup("When they come")
        SliderRow(
            title = "How far after the picture",
            value = style.offsetSeconds.toFloat().coerceAtLeast(0f),
            range = 0f..10f,
            valueLabel = "${String.format("%.1f", style.offsetSeconds.coerceAtLeast(0.0))} s",
            onValue = { model.setSubtitleOffset(it.toDouble()) },
            // The words are held back this long. They cannot be pulled sooner: a stream hands its
            // lines over at their own time, and one the player has not read yet is not there to show.
            subtitle = "Holds the words back, up to ten seconds. A stream gives its lines when it " +
                "gives them, so they cannot be asked for sooner. Kept for every episode.",
        )

        SettingGroup("Which track")
        SwitchRow(
            title = "A file's own styling wins",
            subtitle = "An .ass file carries fonts, colours and positions of its own; on, they are kept " +
                "where the player can draw them.",
            on = style.useEmbeddedStyles,
            onToggle = model::setUseEmbeddedStyles,
        )
        ChoiceRow(
            title = "The language asked for",
            subtitle = "Picked by itself when a stream offers several subtitle tracks.",
            options = LANGUAGES.map { it.second },
            selected = LANGUAGES.firstOrNull { it.first == style.preferredLanguage }?.second ?: LANGUAGES[0].second,
            onPick = { label ->
                LANGUAGES.firstOrNull { it.second == label }?.let { model.setPreferredSubtitleLanguage(it.first) }
            },
            perRow = 2,
        )
        SettingNote(
            "A stream's own tracks and an episode's side-loaded files are both offered in the player's " +
                "subtitle sheet, so a language can be picked per episode too."
        )
    }
}

/** A row of colour swatches, with a tick on the one that is on. */
@Composable
private fun ColourRow(
    title: String,
    colours: List<Pair<Long, String>>,
    selected: Long,
    onPick: (Long) -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth()) {
        SettingRow(title = title)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            colours.forEach { (argb, name) ->
                val on = argb == selected
                Box(
                    modifier = Modifier
                        .size(if (on) 36.dp else 31.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .clickable { onPick(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = name,
                            tint = if (argb == 0xFFFFFFFF.toLong()) Color.Black else Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = colours.firstOrNull { it.first == selected }?.second ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}
