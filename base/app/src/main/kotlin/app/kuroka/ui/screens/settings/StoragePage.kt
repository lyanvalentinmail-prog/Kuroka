package app.kuroka.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.DownloadItem
import app.kuroka.api.DownloadState
import app.kuroka.di.Graph
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.SettingRow
import app.kuroka.ui.components.StatTile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The storage: how much the app takes and what it is (the downloads, the posters, the answers the
 * sources remembered), the room left on the phone, and the buttons that let go of it.
 *
 * Nothing here is the user's own data: the library, the history and the settings live in the
 * database and are not touched by a cache clear.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

@Composable
fun StoragePage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val caches by model.caches.collectAsState()
    val items by Graph.downloads.items.collectAsState()

    val finished = items.count { it.state == DownloadState.COMPLETED }
    val coming = items.count { it.state.isActive }
    val bytes = items.sumOf { it.bytesDone }

    SettingsPage(title = "Storage", onBack = onBack) {
        SettingGroup("What Kuroka takes")
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile(value = caches.downloads, label = "of downloads", modifier = Modifier.weight(1f))
            StatTile(value = caches.posters, label = "of posters", modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile(value = caches.http, label = "remembered by the sources", modifier = Modifier.weight(1f))
            StatTile(value = caches.total, label = "taken altogether", modifier = Modifier.weight(1f))
        }
        SettingRow(
            title = "Let go of the caches",
            subtitle = "The posters and the answers the sources remembered go; the downloads, the " +
                "library and the settings stay.",
            onClick = { model.clearCaches() },
        )

        SettingGroup("The downloads")
        SettingRow(
            title = "$finished on the phone",
            subtitle = if (coming > 0) "$coming more coming down · ${DownloadItem.size(bytes)} written so far"
            else "${DownloadItem.size(bytes)} written altogether",
        )
        SettingRow(
            title = "The room left",
            subtitle = "${DownloadItem.size(Graph.downloads.roomLeft())} free where downloads go",
        )

        SettingGroup("Taking them away")
        SettingRow(
            title = "The finished ones",
            subtitle = "Every download that has come down all the way goes, and the room comes back.",
            onClick = { Graph.downloads.clearFinished() },
        )
        SettingRow(
            title = "All of them",
            subtitle = "The whole queue and every file: nothing is left on the phone.",
            onClick = { items.forEach { Graph.downloads.remove(it.id) } },
        )
        SettingNote(
            "The downloads page has each file on its own, with its size, its pause and its share " +
                "(which hands it to another app as a video file)."
        )
    }
}
