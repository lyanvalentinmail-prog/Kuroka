package app.kuroka.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.Audio
import app.kuroka.api.FitMode
import app.kuroka.api.QualityPref
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SettingsViewModel

/*
 * The player's settings: what it plays (a quality, a fit, an audio), what it does by itself (the
 * next episode, the intro, the recap, the resume), how the gestures work, and how the phone behaves
 * around it (the screen awake, the rotation, picture-in-picture, the audio going on in a pocket).
 *
 * Every row writes through [Prefs], so a change is on the next episode the player opens — and, where
 * it can be, on the one that is playing right now.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

@Composable
fun PlayerSettingsPage(
    onBack: () -> Unit,
    model: SettingsViewModel = viewModel(factory = KurokaViewModels),
) {
    val settings by model.settings.collectAsState()
    val player = settings.player

    SettingsPage(title = "Player", onBack = onBack) {
        SettingGroup("The picture")
        ChoiceRow(
            title = "The quality asked for",
            subtitle = "The closest one a server has, under this. A slow network starts lower anyway.",
            options = QualityPref.entries.map { it.label },
            selected = player.quality.label,
            onPick = { label -> QualityPref.entries.firstOrNull { it.label == label }?.let(model::setQuality) },
            perRow = 4,
        )
        ChoiceRow(
            title = "How the picture fills the screen",
            subtitle = "Fit keeps the whole picture; Fill and Zoom crop it; Stretch does not keep its shape.",
            options = FitMode.entries.map { it.label },
            selected = player.fit.label,
            onPick = { label -> FitMode.entries.firstOrNull { it.label == label }?.let(model::setFit) },
            perRow = 4,
        )
        ChoiceRow(
            title = "The audio asked for first",
            subtitle = "An episode with no dub plays subtitled whatever this is.",
            options = listOf("Subtitled", "Dubbed"),
            selected = if (player.defaultAudio == Audio.DUB) "Dubbed" else "Subtitled",
            onPick = { label -> model.setDefaultAudio(if (label == "Dubbed") Audio.DUB else Audio.SUB) },
            perRow = 2,
        )
        ChoiceRow(
            title = "The speed it starts at",
            options = listOf("0.75×", "1×", "1.25×", "1.5×", "2×"),
            selected = speedLabel(player.speed),
            onPick = { label -> model.setSpeed(speedOf(label)) },
            perRow = 5,
        )
        SwitchRow(
            title = "A glow behind the picture",
            subtitle = "The poster, drawn faint behind a letterboxed picture.",
            on = player.ambilight,
            onToggle = model::setAmbilight,
        )

        SettingGroup("What the player does by itself")
        SwitchRow(
            title = "The next episode",
            subtitle = "A card counts down six seconds at an episode's end, and the next one starts.",
            on = player.autoPlayNext,
            onToggle = model::setAutoPlayNext,
        )
        SwitchRow(
            title = "Skip the opening and the ending",
            subtitle = "When a source says where they are, the player jumps over them by itself.",
            on = player.autoSkipIntro,
            onToggle = model::setAutoSkipIntro,
        )
        SwitchRow(
            title = "Show the skip buttons",
            subtitle = "The buttons that jump over them by hand; off means the auto skip alone.",
            on = player.showSkipButton,
            onToggle = model::setShowSkipButton,
        )
        SwitchRow(
            title = "Go on where an episode stopped",
            subtitle = "An episode part way through starts there again, unless it is under half a minute in.",
            on = player.resume,
            onToggle = model::setResume,
        )
        SwitchRow(
            title = "Skip a recap episode",
            subtitle = "A source that marks an episode as a recap has it jumped over.",
            on = player.skipRecap,
            onToggle = model::setSkipRecap,
        )
        SwitchRow(
            title = "Ask the last server again",
            subtitle = "The server a source answered with last is asked for first next time.",
            on = player.rememberServer,
            onToggle = model::setRememberServer,
        )

        SettingGroup("The gestures")
        SwitchRow(
            title = "Gestures on the picture",
            subtitle = "A tap for the controls, a double tap to jump, a drag across to scrub.",
            on = player.gestures,
            onToggle = model::setGestures,
        )
        SwitchRow(
            title = "The volume on the right half",
            subtitle = "A drag down the right half of the picture changes the volume.",
            on = player.volumeGesture,
            onToggle = model::setVolumeGesture,
            enabled = player.gestures,
        )
        SwitchRow(
            title = "The brightness on the left half",
            subtitle = "A drag down the left half changes this app's brightness alone.",
            on = player.brightnessGesture,
            onToggle = model::setBrightnessGesture,
            enabled = player.gestures,
        )
        StepperRow(
            title = "A double tap jumps",
            subtitle = "The seconds a double tap on either half moves by.",
            value = player.doubleTapSeekSeconds,
            min = 5,
            max = 30,
            step = 5,
            onChange = model::setDoubleTapSeek,
        )

        SettingGroup("The phone around the player")
        SwitchRow(
            title = "Keep the screen awake",
            subtitle = "The phone does not lock itself while an episode plays.",
            on = player.keepScreenOn,
            onToggle = model::setKeepScreenOn,
        )
        SwitchRow(
            title = "Turn over for a wide picture",
            subtitle = "The phone asks to rotate when the picture is wider than it is tall.",
            on = player.autoRotate,
            onToggle = model::setAutoRotate,
        )
        SwitchRow(
            title = "Picture in picture",
            subtitle = "Leaving the app while an episode plays keeps a small window of it.",
            on = player.pictureInPicture,
            onToggle = model::setPictureInPicture,
        )
        SwitchRow(
            title = "The audio on in a pocket",
            subtitle = "Keep playing the sound when the app goes to the background (no picture).",
            on = player.backgroundAudio,
            onToggle = model::setBackgroundAudio,
        )
        SwitchRow(
            title = "Ask Wi-Fi for streams",
            subtitle = "A phone on its own connection is told before a stream starts.",
            on = player.dataSaver,
            onToggle = model::setDataSaver,
        )

        SettingNote(
            "The subtitles have their own page: their size, their colour, the box behind them and how " +
                "far off the picture they are. Every setting here is kept for every episode and every source."
        )
    }
}

/** "1.25×" for a speed, and the speed for one. */
private fun speedLabel(speed: Float): String = if (speed == 1f) "1×" else "${speed}×"

private fun speedOf(label: String): Float = label.removeSuffix("×").toFloatOrNull() ?: 1f
