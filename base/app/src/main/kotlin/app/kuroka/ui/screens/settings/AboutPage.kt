package app.kuroka.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.R
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.SettingRow
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The about page: what Kuroka is, the version, the license, the two apps its shape comes from, the
 * libraries it is built on, and the promise the whole thing rests on — no ads, no tracking, no
 * account of its own.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

@Composable
fun AboutPage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val accent = LocalAccent.current
    val context = LocalContext.current

    SettingsPage(title = "About", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Kuroka",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Version ${model.version} · ${settings.look.label} look",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Anime with no ads in it, no account of its own and nothing sent anywhere " +
                    "except the catalog and the sources an episode comes from.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SettingGroup("The license")
        SettingRow(
            title = "GPL-3.0",
            subtitle = "Kuroka is free software: the source is public, and a copy of it has to travel " +
                "with any build. The full text is in the repository's LICENSE.",
        )
        SettingRow(
            title = "The source code",
            subtitle = "github.com/lyanvalentinmail-prog/Kuroka",
            onClick = { open(context, "https://github.com/lyanvalentinmail-prog/Kuroka") },
        )
        SettingRow(
            title = "The libraries it is built on",
            subtitle = "Media3, OkHttp, Compose and the rest, each with its own license, are listed in " +
                "THIRD_PARTY_NOTICES.md.",
            onClick = {
                open(context, "https://github.com/lyanvalentinmail-prog/Kuroka/blob/main/THIRD_PARTY_NOTICES.md")
            },
        )

        SettingGroup("Where the shape comes from")
        SettingRow(
            title = "Orpheon, by kota-zo (GPL-3.0)",
            subtitle = "The look of a player, the cast protocol, the source order and the release " +
                "workflow are worked from it. Kuroka is GPL-3.0 partly for that reason.",
            onClick = { open(context, "https://github.com/kota-zo/Orpheon") },
        )
        SettingRow(
            title = "Kitsune, by Dovakiin0 (MIT)",
            subtitle = "The player's own drawing — the gestures, the subtitle styling and the episode " +
                "source handling — follows it, as its MIT license allows.",
            onClick = { open(context, "https://github.com/Dovakiin0/Kitsune") },
        )

        SettingGroup("Where the anime comes from")
        SettingRow(
            title = "AniList",
            subtitle = "The catalog: the titles, the posters, the genres, the airing times and the " +
                "account's lists. Kuroka hosts none of it.",
            onClick = { open(context, "https://anilist.co") },
        )
        SettingRow(
            title = "The episode sources",
            subtitle = "The streams come from public third-party services, listed in Settings, Sources. " +
                "Kuroka has no relation with them and hosts no video.",
        )

        SettingGroup("What the app does not do")
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "No ads, and no ad library.",
                "No analytics, no crash reports, no third-party tracker.",
                "No account of its own: a sign-in is AniList's, and its token stays in the phone's " +
                    "encrypted storage.",
                "No image-loading library: the posters are fetched and cached by the app itself.",
            ).forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accent)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingGroup("A word about the streams")
        SettingNote(
            "Kuroka plays what public sources give it. Whether an anime may be watched that way in " +
                "your country is not the app's to decide, and nothing here is a substitute for the " +
                "services that license it."
        )
    }
}

/** A page in the browser. */
private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
