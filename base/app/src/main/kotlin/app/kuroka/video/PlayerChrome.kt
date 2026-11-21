package app.kuroka.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kuroka.api.Audio
import app.kuroka.api.SubtitleTrack
import app.kuroka.host.cast.CastState
import app.kuroka.host.playback.PlayerSnapshot
import app.kuroka.host.playback.TrackInfo
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.clockOf
import app.kuroka.ui.theme.LocalAccent

/*
 * The player's own chrome: the bars over the picture, the seek bar, the skip-intro and skip-outro
 * buttons, the card that counts down to the next episode, and the sheets the settings row opens
 * (quality and speed, subtitles, audio track, server).
 *
 * The shape follows Orpheon's video controls (GPL-3.0, © kota-zo): a bar that fades in over the
 * picture, a seek bar with a thumb that grows under the finger, and the settings reached from a row
 * of words rather than a menu of menus.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** Which sheet the player's settings row has opened. */
enum class PlayerSheet { NONE, QUALITY, SUBTITLES, AUDIO, SERVERS }

/** The bars over the picture: what is playing on top, the transport in the middle, the seek bar below. */
@Composable
fun PlayerChrome(
    visible: Boolean,
    snapshot: PlayerSnapshot,
    title: String,
    episodeLabel: String,
    server: String?,
    totalEpisodes: Int,
    subtitlesOn: Boolean,
    castState: CastState,
    fullscreen: Boolean = false,
    doubleTapSeconds: Int = 10,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onScrubTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSheet: (PlayerSheet) -> Unit,
    onDownload: () -> Unit,
    onCast: () -> Unit,
    onFullscreen: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.82f),
                            Color.Black.copy(alpha = 0.04f),
                            Color.Black.copy(alpha = 0.16f),
                            Color.Black.copy(alpha = 0.88f),
                        )
                    )
                )
        ) {
            // The top bar: back, the title, and the two ways out of the episode.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeIcon(Icons.Rounded.ArrowBack, "Back", onClick = onBack)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 10.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(episodeLabel, server?.takeIf { it.isNotBlank() })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ChromeIcon(Icons.Rounded.Download, "Take this episode down", onClick = onDownload)
                ChromeIcon(
                    imageVector = if (castState.casting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                    contentDescription = if (castState.casting) "Casting now" else "Cast",
                    onClick = onCast,
                    tint = if (castState.casting) accent else Color.White,
                )
            }

            // The middle: the transport, with a jump of ten seconds either side of it.
            Row(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ChromeIcon(
                    imageVector = Icons.Rounded.Replay10,
                    contentDescription = "$doubleTapSeconds seconds back",
                    onClick = { onSeekBy(-doubleTapSeconds * 1000L) },
                    size = 32,
                )
                if (totalEpisodes > 1) {
                    ChromeIcon(Icons.Rounded.SkipPrevious, "The episode before", onClick = onPrevious, size = 30)
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    if (snapshot.buffering && !snapshot.ended) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp),
                        )
                    } else {
                        Icon(
                            imageVector = if (snapshot.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (snapshot.playing) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                if (totalEpisodes > 1) {
                    ChromeIcon(Icons.Rounded.SkipNext, "The next episode", onClick = onNext, size = 30)
                }
                ChromeIcon(
                    imageVector = Icons.Rounded.Forward10,
                    contentDescription = "$doubleTapSeconds seconds on",
                    onClick = { onSeekBy(doubleTapSeconds * 1000L) },
                    size = 32,
                )
            }

            // The bottom bar: the seek bar, then the row of words that opens the sheets.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                SeekBar(
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    bufferedMs = snapshot.bufferedMs,
                    onScrubTo = onScrubTo,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChromeIcon(
                        imageVector = if (subtitlesOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                        contentDescription = "Subtitles",
                        onClick = { onSheet(PlayerSheet.SUBTITLES) },
                        tint = if (subtitlesOn) accent else Color.White,
                        size = 22,
                    )
                    ChromeText(
                        text = if (snapshot.speed != 1f) "${snapshot.speed}×" else "Speed",
                        onClick = { onSheet(PlayerSheet.QUALITY) },
                    )
                    ChromeText(
                        text = snapshot.selectedQuality?.qualityLabel?.substringBefore(" · ") ?: "Quality",
                        onClick = { onSheet(PlayerSheet.QUALITY) },
                    )
                    if (snapshot.hasSeveralAudios) {
                        ChromeText(
                            text = snapshot.selectedAudio?.language?.uppercase() ?: "Audio",
                            onClick = { onSheet(PlayerSheet.AUDIO) },
                        )
                    }
                    ChromeText(text = "Server", onClick = { onSheet(PlayerSheet.SERVERS) })
                    Spacer(Modifier.weight(1f))
                    if (onFullscreen != null) {
                        ChromeIcon(
                            imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            contentDescription = if (fullscreen) "Smaller again" else "Full screen",
                            onClick = onFullscreen,
                            size = 22,
                        )
                    }
                    ChromeIcon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "The player's settings",
                        onClick = { onSheet(PlayerSheet.QUALITY) },
                        size = 22,
                    )
                }
            }
        }
    }
}

/** The seek bar: a thumb that grows while it is dragged, and the buffered part behind it. */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long = 0L,
    onScrubTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    var dragging by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f
    val value = (if (dragging) scrub else fraction).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                scrub = it
            },
            onValueChangeFinished = {
                if (durationMs > 0L) onScrubTo((scrub * durationMs).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.26f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (dragging && durationMs > 0L) {
            Text(
                text = "${clockOf((scrub * durationMs).toLong())} / ${clockOf(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
    }
}

/** "Skip the opening" / "Skip the ending", shown while the episode is inside one of them. */
@Composable
fun SkipButtons(
    inIntro: Boolean,
    inOutro: Boolean,
    showButton: Boolean,
    onSkipIntro: () -> Unit,
    onSkipOutro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!showButton || (!inIntro && !inOutro)) return
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier.padding(end = 16.dp, bottom = 96.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (inIntro) SkipPill(text = "Skip the opening", onClick = onSkipIntro)
            if (inOutro) SkipPill(text = "Skip the ending", onClick = onSkipOutro)
        }
    }
}

@Composable
private fun SkipPill(text: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

/** The speed and the picture's quality, as the sheet the settings row opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(
    snapshot: PlayerSnapshot,
    speed: Float,
    onSpeed: (Float) -> Unit,
    onQuality: (TrackInfo?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("Speed")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                    val label = if (option == 1f) "1×" else "${option}×"
                    Box(Modifier.weight(1f)) {
                        Pill(
                            text = label,
                            selected = speed == option,
                            onClick = { onSpeed(option) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            SettingGroup("The picture's quality")
            if (!snapshot.hasSeveralQualities) {
                Text(
                    text = "This stream carries one picture, so there is nothing to choose.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                Column {
                    SheetRow(
                        label = "Whatever it can",
                        on = snapshot.selectedQuality == null,
                        onClick = { onQuality(null) },
                    )
                    // One row per height: two bitrates of 1080p are the same choice to a user.
                    snapshot.qualities.distinctBy { it.height }.forEach { quality ->
                        SheetRow(
                            label = quality.qualityLabel,
                            on = snapshot.selectedQuality?.height == quality.height,
                            onClick = { onQuality(quality) },
                        )
                    }
                }
            }
        }
    }
}

/** The subtitle tracks: off, the side-loaded ones, and the ones the stream carries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSheet(
    snapshot: PlayerSnapshot,
    subtitles: List<SubtitleTrack>,
    selected: SubtitleTrack?,
    offsetSeconds: Double,
    onOffset: (Double) -> Unit,
    onSelect: (SubtitleTrack?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = LocalAccent.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("Subtitles")
            SheetRow(label = "None", on = selected == null, onClick = { onSelect(null) })
            subtitles.forEachIndexed { index, track ->
                SheetRow(
                    label = listOfNotNull(
                        track.name.takeIf { it.isNotBlank() },
                        track.language?.uppercase()?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "Subtitle ${index + 1}" },
                    on = selected == track,
                    onClick = { onSelect(track) },
                )
            }
            // The tracks the stream itself carries, which a side-loaded file cannot replace.
            snapshot.textTracks.filter { track -> subtitles.none { it.name == track.label } }
                .forEach { track ->
                    SheetRow(
                        label = track.label.ifBlank { track.language?.uppercase() ?: "In the stream" },
                        on = snapshot.selectedText?.groupIndex == track.groupIndex &&
                            snapshot.selectedText?.trackIndex == track.trackIndex,
                        onClick = { onSelect(SubtitleTrack(url = "", label = track.label, language = track.language.orEmpty())) },
                    )
                }
            SettingGroup("How far off the picture they are")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${String.format("%.1f", offsetSeconds)} s",
                    style = MaterialTheme.typography.bodyLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                OffsetPill("−0.5") { onOffset(offsetSeconds - 0.5) }
                Spacer(Modifier.width(8.dp))
                OffsetPill("+0.5") { onOffset(offsetSeconds + 0.5) }
            }
            Slider(
                value = offsetSeconds.coerceIn(-10.0, 10.0).toFloat(),
                onValueChange = { onOffset(it.toDouble()) },
                valueRange = -10f..10f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Text(
                text = "Negative is sooner than the picture, positive is later. The offset is kept for " +
                    "every episode.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** The audio of the episode (sub or dub) and the tracks this stream carries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(
    snapshot: PlayerSnapshot,
    audio: Audio,
    onAudio: (Audio) -> Unit,
    onSelect: (TrackInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("The episode's audio")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    Pill(text = "Subtitled", selected = audio == Audio.SUB, onClick = { onAudio(Audio.SUB) },
                        modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.weight(1f)) {
                    Pill(text = "Dubbed", selected = audio == Audio.DUB, onClick = { onAudio(Audio.DUB) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            SettingGroup("The tracks in this stream")
            if (snapshot.audioTracks.size <= 1) {
                Text(
                    text = "This stream carries one audio track. A dubbed one is another server's, " +
                        "under the episode's audio.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                snapshot.audioTracks.forEach { track ->
                    SheetRow(
                        label = listOfNotNull(
                            track.label.takeIf { it.isNotBlank() },
                            track.language?.uppercase(),
                        ).joinToString(" · ").ifBlank { "Track" },
                        on = snapshot.selectedAudio?.groupIndex == track.groupIndex &&
                            snapshot.selectedAudio?.trackIndex == track.trackIndex,
                        onClick = { onSelect(track) },
                    )
                }
            }
        }
    }
}

/** The servers that have this episode, for the sheet that switches between them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    servers: List<String>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            SettingGroup("Another server")
            Text(
                text = "The same episode from another server: another picture, another set of subtitles.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
            )
            if (servers.isEmpty()) {
                Text(
                    text = "This source gives one server for the episode.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            servers.forEach { server ->
                SheetRow(label = server, on = server == current, onClick = { onPick(server) })
            }
        }
    }
}

/** One row of a sheet, with a tick on the one that is on. */
@Composable
private fun SheetRow(label: String, on: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (on) accent else MaterialTheme.colorScheme.onBackground,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun OffsetPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** "Episode 7 starts in 5…", as the card an ended episode shows. */
@Composable
fun NextUpCard(
    episodeNumber: Int,
    hasNext: Boolean,
    seconds: Int,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut(),
        modifier = modifier.padding(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.86f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (hasNext) "Episode ${episodeNumber + 1} in $seconds…" else "That was the last episode",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (hasNext) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((1f - seconds / 6f).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(accent)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasNext) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(accent)
                            .clickable(onClick = onNext)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Play it now", color = Color.Black, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                Box(
                    modifier = Modifier
                        .then(if (hasNext) Modifier else Modifier.weight(1f))
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (hasNext) "Stop here" else "Back to the anime",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** An icon of the player's chrome (white, over the picture). */
@Composable
private fun ChromeIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    size: Int = 24,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(imageVector = imageVector, contentDescription = contentDescription, tint = tint,
            modifier = Modifier.size(size.dp))
    }
}

/** A word of the player's chrome, tapped like an icon. */
@Composable
private fun ChromeText(text: String, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (onClick != null) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
