package app.kuroka.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.DownloadLocation
import app.kuroka.api.QualityPref
import app.kuroka.di.Graph
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The downloads' settings: where a file goes, what quality it takes, how many come down at once,
 * which network they wait for, and how long a finished one stays.
 *
 * "In Kuroka" needs no permission at all and goes with the app; "on the device" writes to
 * Movies/Kuroka through MediaStore, so the files stay when the app is taken off.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

@Composable
fun DownloadSettingsPage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val downloads = settings.downloads

    SettingsPage(title = "Downloads", onBack = onBack) {
        SettingGroup("Where a file goes")
        ChoiceRow(
            title = "The folder",
            subtitle = if (downloads.location == DownloadLocation.APP)
                "Inside Kuroka's own folder: no permission, and it goes when the app does."
            else "In the device's Movies/Kuroka, through MediaStore: kept when the app goes.",
            options = DownloadLocation.entries.map { it.label },
            selected = downloads.location.label,
            onPick = { label ->
                DownloadLocation.entries.firstOrNull { it.label == label }?.let(model::setDownloadLocation)
            },
            perRow = 2,
        )

        SettingGroup("What comes down")
        ChoiceRow(
            title = "The quality",
            subtitle = "The variant of the stream closest to this, under it. A file of an episode an " +
                "HLS stream gives in one piece is the piece itself.",
            options = QualityPref.entries.filter { it != QualityPref.LOWEST }.map { it.label },
            selected = downloads.quality.label,
            onPick = { label -> QualityPref.entries.firstOrNull { it.label == label }?.let(model::setDownloadQuality) },
            perRow = 3,
        )
        SwitchRow(
            title = "The subtitles too",
            subtitle = "An episode's subtitle files come down with it, so a download has them offline.",
            on = downloads.subtitles,
            onToggle = model::setDownloadSubtitles,
        )
        StepperRow(
            title = "At once",
            subtitle = "How many episodes come down at the same time; more is slower on a phone network.",
            value = downloads.maxConcurrent,
            min = 1,
            max = 4,
            onChange = model::setMaxConcurrent,
        )

        SettingGroup("Which network")
        SwitchRow(
            title = "Wi-Fi only",
            subtitle = "A download waits for Wi-Fi; one already going keeps going.",
            on = downloads.wifiOnly,
            onToggle = model::setWifiOnly,
        )
        SwitchRow(
            title = "Ask on a phone network",
            subtitle = "A download asked for on data is offered rather than started.",
            on = downloads.askOnMetered,
            onToggle = model::setAskOnMetered,
            enabled = !downloads.wifiOnly,
        )

        SettingGroup("How long they stay")
        SwitchRow(
            title = "Keep one that has been watched",
            subtitle = "Off: a finished download goes when its episode has been watched through.",
            on = downloads.keepAfterWatching,
            onToggle = model::setKeepAfterWatching,
        )
        StepperRow(
            title = "Days kept",
            subtitle = "0 keeps them forever; otherwise the downloads page offers to take the older ones away.",
            value = downloads.keepDays,
            min = 0,
            max = 90,
            step = if (downloads.keepDays >= 14) 7 else 1,
            onChange = model::setKeepDays,
        )
        SliderRow(
            title = "How much storage they may take",
            value = downloads.budgetMb.toFloat(),
            range = 0f..65536f,
            valueLabel = if (downloads.budgetMb == 0) "No limit" else "${downloads.budgetMb / 1024} GB",
            onValue = { model.setBudget(it.toInt()) },
            steps = 63,
            subtitle = "A download that would go over this waits; the page says how much is left.",
        )
        SettingNote(
            "On the phone now: ${Graph.downloads.usedLabel()} of downloads. " +
                "The downloads page has the queue, the finished files and the room left."
        )
    }
}
