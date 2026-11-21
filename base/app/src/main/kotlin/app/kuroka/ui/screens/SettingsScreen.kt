package app.kuroka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.AccountPhase
import app.kuroka.di.Graph
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The settings' own page: the door to the seven pages under it (the look, the player, the subtitles,
 * the downloads, the sources, the storage, the updates) and the two that are not pages (the account
 * and the about).
 *
 * The rows say what a setting is at, so a user who wants one change does not have to open every page
 * to find it: the look row names the look, the storage row names how much the app takes.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

@Composable
fun SettingsScreen(
    onDisplay: () -> Unit,
    onPlayer: () -> Unit,
    onSubtitles: () -> Unit,
    onDownloads: () -> Unit,
    onSources: () -> Unit,
    onAccount: () -> Unit,
    onStorage: () -> Unit,
    onUpdates: () -> Unit,
    onAbout: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val sources by model.sources.collectAsState()
    val caches by model.caches.collectAsState()
    val phase by Graph.account.phase.collectAsState()
    val user by Graph.account.user.collectAsState()

    val sourcesOn = sources.count { it.enabled }
    val downSources = sources.count { it.down }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = model.version,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingGroup("You")
        SettingsTile(
            icon = Icons.Rounded.AccountCircle,
            title = "Your account",
            subtitle = when (phase) {
                AccountPhase.SIGNED_IN -> user?.name ?: "Signed in to AniList"
                AccountPhase.SIGNING_IN -> "Signing in…"
                AccountPhase.FAILED -> "The sign-in failed; try again"
                AccountPhase.SIGNED_OUT -> "Signed out: your lists are this phone's alone"
            },
            onClick = onAccount,
        )

        SettingGroup("How it looks")
        SettingsTile(
            icon = Icons.Rounded.Palette,
            title = "Display",
            subtitle = "${settings.look.label} look · ${
                if (settings.dynamicColor) "the wallpaper's colour" else settings.accent.label
            }" + if (settings.oledBlack) " · black pages" else "",
            onClick = onDisplay,
        )

        SettingGroup("Watching")
        SettingsTile(
            icon = Icons.Rounded.PlayCircle,
            title = "Player",
            subtitle = listOfNotNull(
                settings.player.quality.label,
                if (settings.player.autoPlayNext) "the next episode by itself" else null,
                if (settings.player.gestures) "gestures" else "no gestures",
                settings.player.fit.label,
            ).joinToString(" · "),
            onClick = onPlayer,
        )
        SettingsTile(
            icon = Icons.Rounded.ClosedCaption,
            title = "Subtitles",
            subtitle = "${settings.player.subtitles.sizePercent}% · ${
                settings.player.subtitles.preferredLanguage.ifBlank { "no language asked for" }
            }",
            onClick = onSubtitles,
        )

        SettingGroup("Episodes on the phone")
        SettingsTile(
            icon = Icons.Rounded.Download,
            title = "Downloads",
            subtitle = listOfNotNull(
                settings.downloads.location.label,
                settings.downloads.quality.label,
                if (settings.downloads.wifiOnly) "Wi-Fi only" else "any network",
                "${settings.downloads.maxConcurrent} at once",
            ).joinToString(" · "),
            onClick = onDownloads,
        )
        SettingsTile(
            icon = Icons.Rounded.Storage,
            title = "Storage and caches",
            subtitle = "${caches.total} taken · ${caches.posters} of posters",
            onClick = onStorage,
        )

        SettingGroup("Where the anime comes from")
        SettingsTile(
            icon = Icons.Rounded.Tv,
            title = "Sources",
            subtitle = "$sourcesOn of ${sources.size} on" +
                (if (downSources > 0) " · $downSources not answering" else "") +
                (if (settings.kitsuneServer.isNotBlank()) " · your own server" else ""),
            onClick = onSources,
        )

        SettingGroup("The app itself")
        SettingsTile(
            icon = Icons.Rounded.SystemUpdate,
            title = "Updates",
            subtitle = if (model.canCheckUpdates) {
                if (settings.updateCheck) "Asking GitHub for a release now and then"
                else "Not asking; a release can be asked for by hand"
            } else "This build does not know its release page",
            onClick = onUpdates,
        )
        SettingsTile(
            icon = Icons.Rounded.Info,
            title = "About Kuroka",
            subtitle = "Version ${model.version} · GPL-3.0 · no ads, no tracking",
            onClick = onAbout,
        )
        Spacer(Modifier.height(26.dp))
    }
}

/** One row of the settings' page: an icon in a circle, a title and what the setting is at. */
@Composable
private fun SettingsTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
