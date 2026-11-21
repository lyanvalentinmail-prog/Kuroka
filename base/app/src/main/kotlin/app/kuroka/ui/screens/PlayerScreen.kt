package app.kuroka.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.Audio
import app.kuroka.api.SubtitleTrack
import app.kuroka.MainActivity
import app.kuroka.PipMode
import app.kuroka.di.Graph
import app.kuroka.host.cast.CastPhase
import app.kuroka.ui.components.AccentButton
import app.kuroka.ui.components.CastSheet
import app.kuroka.ui.components.Notice
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.SectionHeader
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.PlayerPhase
import app.kuroka.ui.viewmodel.PlayerViewModel
import app.kuroka.video.AudioTrackSheet
import app.kuroka.video.NextUpCard
import app.kuroka.video.PlayerChrome
import app.kuroka.video.PlayerSettingsSheet
import app.kuroka.video.PlayerSheet
import app.kuroka.video.PlayerSurface
import app.kuroka.video.ServerSheet
import app.kuroka.video.SkipButtons
import app.kuroka.video.SubtitleSheet
import app.kuroka.video.rememberAwake
import app.kuroka.video.rememberBrightnessReset
import kotlinx.coroutines.delay

/*
 * The player screen: the picture with its chrome over it, and — while the phone is upright — the
 * episode under it (what it is called, the list to jump to another one, the anime's page, the
 * download button).
 *
 * While the episode is on a TV, the picture goes and a card takes its place: the TV's own position,
 * its buffer, and the buttons that speak to it. Nothing else about the screen changes, which is
 * what makes casting a move rather than another screen.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun PlayerScreen(
    animeId: Int,
    number: Double,
    audio: Audio,
    episodeId: String?,
    server: String?,
    source: String?,
    onBack: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    model: PlayerViewModel = viewModel(factory = KurokaViewModels),
) {
    val context = LocalContext.current
    val ui by model.ui.collectAsState()
    val snapshot by model.snapshot.collectAsState()
    val castState by model.castState.collectAsState()
    val settings by Graph.prefs.settings.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    val accent = LocalAccent.current

    // A PiP window is the picture and nothing else: no bars, no sheet, no episode under it.
    val inPip by PipMode.inPip.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }
    var castOpen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    rememberBrightnessReset()
    rememberAwake(on = settings.player.keepScreenOn && !ui.casting)

    // The episode asked for by the route, and the picture's shape for picture-in-picture.
    LaunchedEffect(animeId, number, audio, episodeId, server, source) {
        model.start(animeId, number, audio, episodeId, server, source)
        model.onPictureSize { width, height -> (context as? MainActivity)?.setPictureShape(width, height) }
    }

    // The controls hide themselves after a while, and come back on a tap.
    LaunchedEffect(controlsVisible, snapshot.playing, ui.casting) {
        if (!controlsVisible || !snapshot.playing) return@LaunchedEffect
        delay(3600)
        controlsVisible = false
    }

    // The card at an episode's end counts down to the next one (when the setting is on).
    LaunchedEffect(snapshot.ended, ui.nextNumber, settings.player.autoPlayNext) {
        countdown = 0
        if (!snapshot.ended || ui.nextNumber == null || !settings.player.autoPlayNext) return@LaunchedEffect
        countdown = 6
        while (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
        model.next()
    }

    // The screen is kept awake and the status bar hidden while the player is up.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> model.onBackground()
                Lifecycle.Event.ON_RESUME -> model.onForeground()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    // Rotation: the setting decides whether an upright phone turns itself over for a wide picture.
    LaunchedEffect(snapshot.videoWidth, snapshot.videoHeight, settings.player.autoRotate, fullscreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val wide = snapshot.videoWidth > snapshot.videoHeight
        activity.requestedOrientation = when {
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            !settings.player.autoRotate -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            wide && settings.player.autoRotate -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val player = settings.player

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // The picture: full width, and the whole screen when it is turned over.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) Modifier.fillMaxSize()
                        else Modifier.aspectRatio(16f / 9f).background(Color.Black)
                    )
            ) {
                when {
                    ui.casting -> CastingCard(
                        castState = castState,
                        title = ui.title,
                        episodeLabel = ui.episodeLabel,
                        onPlayPause = { model.playPause() },
                        onScrub = { model.seekTo(it) },
                        onStop = {
                            model.stopCasting()
                            castOpen = true
                        },
                        onOpen = { castOpen = true },
                    )

                    else -> {
                        PlayerSurface(
                            fit = player.fit,
                            subtitles = player.subtitles,
                            gestures = player.gestures,
                            volumeGesture = player.volumeGesture,
                            brightnessGesture = player.brightnessGesture,
                            doubleTapSeconds = player.doubleTapSeekSeconds,
                            ambilight = player.ambilight,
                            poster = ui.anime?.cover,
                            controlsVisible = controlsVisible,
                            onTap = { controlsVisible = !controlsVisible },
                        )
                        PlayerChrome(
                            visible = controlsVisible && !inPip,
                            snapshot = snapshot,
                            title = ui.title,
                            episodeLabel = ui.episodeLabel,
                            server = ui.stream?.server,
                            totalEpisodes = ui.episodes.size.takeIf { it > 0 } ?: (ui.anime?.episodes ?: 1),
                            subtitlesOn = ui.subtitles.any { it == selectedSubtitle(ui.subtitles, snapshot) } ||
                                snapshot.selectedText != null,
                            castState = castState,
                            fullscreen = fullscreen,
                            doubleTapSeconds = player.doubleTapSeekSeconds,
                            onBack = onBack,
                            onPlayPause = { model.playPause() },
                            onSeekBy = { model.seekBy(it) },
                            onScrubTo = { model.seekTo(it) },
                            onNext = { model.next() },
                            onPrevious = { model.previous() },
                            onSheet = { sheet = it },
                            onDownload = { model.download() },
                            onCast = { castOpen = true },
                            onFullscreen = { fullscreen = !fullscreen },
                        )
                        SkipButtons(
                            inIntro = ui.inIntro && !inPip,
                            inOutro = ui.inOutro,
                            showButton = player.showSkipButton,
                            onSkipIntro = { model.skipIntro() },
                            onSkipOutro = { model.skipOutro() },
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                        if (snapshot.ended && !inPip) {
                            NextUpCard(
                                episodeNumber = ui.number.toInt(),
                                hasNext = ui.nextNumber != null,
                                seconds = countdown,
                                onNext = { model.next() },
                                onStop = onBack,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }

                // The states the picture can be in before it draws anything.
                if (!ui.casting) {
                    when (ui.phase) {
                        PlayerPhase.FINDING, PlayerPhase.IDLE -> Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = when (ui.phase) {
                                        PlayerPhase.FINDING -> "Asking the sources for the episode…"
                                        else -> "Opening the player…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }

                        PlayerPhase.ERROR -> Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Text(
                                    text = "The episode did not play",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = ui.error ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.75f),
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (model.hasNextStream()) {
                                        AccentButton(text = "Another stream", onClick = { model.nextStream() })
                                    }
                                    AccentButton(text = "Try again", onClick = { model.refresh() })
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }

            // Under the picture, while the phone is upright: the episode and the list.
            if (!fullscreen && !inPip) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(top = 14.dp)
                ) {
                    UnderPicture(
                        ui = ui,
                        onAnime = { ui.anime?.let { onOpenAnime(it.id) } },
                        onEpisode = { episode ->
                            model.start(
                                animeId = animeId,
                                number = episode.number,
                                audio = ui.audio,
                                episodeId = episode.id,
                                server = ui.stream?.server,
                                source = episode.source.ifBlank { ui.bundle?.source },
                            )
                        },
                        onAudio = { model.setAudio(it) },
                        onDownload = { model.download() },
                        onSettings = onOpenSettings,
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            Notice(text = ui.notice, onDismiss = { model.clearNotice() })
        }
    }

    when (sheet) {
        PlayerSheet.QUALITY -> PlayerSettingsSheet(
            snapshot = snapshot,
            speed = player.speed,
            onSpeed = { model.setSpeed(it) },
            onQuality = { model.selectQuality(it) },
            onDismiss = { sheet = PlayerSheet.NONE },
        )

        PlayerSheet.SUBTITLES -> SubtitleSheet(
            snapshot = snapshot,
            subtitles = ui.subtitles,
            selected = selectedSubtitle(ui.subtitles, snapshot),
            offsetSeconds = player.subtitles.offsetSeconds,
            onOffset = { model.setSubtitleOffset(it) },
            onSelect = { model.selectSubtitle(it) },
            onDismiss = { sheet = PlayerSheet.NONE },
        )

        PlayerSheet.AUDIO -> AudioTrackSheet(
            snapshot = snapshot,
            audio = ui.audio,
            onAudio = { model.setAudio(it) },
            onSelect = { model.selectAudioTrack(it) },
            onDismiss = { sheet = PlayerSheet.NONE },
        )

        PlayerSheet.SERVERS -> ServerSheet(
            servers = ui.servers,
            current = ui.stream?.server,
            onPick = { model.selectServer(it); sheet = PlayerSheet.NONE },
            onDismiss = { sheet = PlayerSheet.NONE },
        )

        PlayerSheet.NONE -> Unit
    }

    CastSheet(
        open = castOpen,
        state = castState,
        onDismiss = { castOpen = false },
        onCast = { model.castTo(it) },
        onStop = { model.stopCasting() },
        onPlayPause = { model.playPause() },
        onSeek = { model.seekTo(it) },
    )
}

/** What is on the TV, drawn in the picture's place while the phone is the remote. */
@Composable
private fun CastingCard(
    castState: app.kuroka.host.cast.CastState,
    title: String,
    episodeLabel: String,
    onPlayPause: () -> Unit,
    onScrub: (Long) -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CastConnected,
                contentDescription = "Casting",
                tint = accent,
                modifier = Modifier.height(42.dp).width(42.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = castState.device?.name ?: "The TV",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (castState.phase) {
                    CastPhase.CONNECTING -> "Finding the TV…"
                    CastPhase.LOADING -> "Loading the episode on it…"
                    CastPhase.ERROR -> castState.error ?: "The TV stopped answering"
                    else -> if (castState.playing) "Playing" else "Paused"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$title · $episodeLabel",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth()) {
                app.kuroka.video.SeekBar(
                    positionMs = castState.positionMs,
                    durationMs = castState.durationMs,
                    onScrubTo = onScrub,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentButton(text = if (castState.playing) "Pause" else "Play", onClick = onPlayPause)
                AccentButton(text = "Take it back", onClick = onStop)
            }
        }
    }
}

/** The episode's own card under the picture, and the list to jump to another one. */
@Composable
private fun UnderPicture(
    ui: app.kuroka.ui.viewmodel.PlayerUi,
    onAnime: () -> Unit,
    onEpisode: (app.kuroka.api.Episode) -> Unit,
    onAudio: (Audio) -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = ui.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onAnime),
                )
                Text(
                    text = listOfNotNull(
                        ui.episodeLabel,
                        ui.episode?.title?.takeIf { it.isNotBlank() && it != ui.episodeLabel },
                        ui.stream?.server,
                        if (ui.offline) "On the phone" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Pill(text = "Download", onClick = onDownload)
            Spacer(Modifier.width(8.dp))
            Pill(text = "Settings", onClick = onSettings)
        }

        if (ui.episodes.size > 1) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(title = "The episodes", modifier = Modifier.weight(1f))
                AudioSwitchSmall(audio = ui.audio, onAudio = onAudio)
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.episodes.size) { index ->
                    val episode = ui.episodes[index]
                    val on = episode.number == ui.number
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (on) accent.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { onEpisode(episode) }
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = episode.numberLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) accent else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

/** The SUB / DUB switch, small, for the row of episode numbers. */
@Composable
private fun AudioSwitchSmall(audio: Audio, onAudio: (Audio) -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp)
    ) {
        listOf(Audio.SUB to "Sub", Audio.DUB to "Dub").forEach { (option, label) ->
            val on = option == audio
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) accent else Color.Transparent)
                    .clickable { onAudio(option) }
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The subtitle the player has on, matched to the episode's own list. */
private fun selectedSubtitle(
    subtitles: List<SubtitleTrack>,
    snapshot: app.kuroka.host.playback.PlayerSnapshot,
): SubtitleTrack? {
    val on = snapshot.selectedText ?: return null
    return subtitles.firstOrNull { it.label == on.label || it.language == on.language }
}
