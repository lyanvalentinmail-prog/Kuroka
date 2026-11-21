package app.kuroka.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kuroka.di.Graph
import app.kuroka.di.NowEpisode
import app.kuroka.host.cast.CastController
import app.kuroka.host.playback.PlayerHost
import app.kuroka.ui.look.LocalImmersive
import app.kuroka.ui.look.glass
import app.kuroka.ui.theme.LocalAccent

/*
 * The mini player: the strip above the tabs that says an episode is on, how far it got, and gives
 * the three things a user wants without opening the player — play or pause, the next episode, and
 * close. Tapping anywhere else on it goes back to the player.
 *
 * While the picture is on a Chromecast the strip says so, and its button takes the episode back.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun MiniPlayer(
    episode: NowEpisode,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onNext: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    cast: CastController = Graph.cast,
) {
    val snapshot by PlayerHost.snapshot.collectAsState()
    val castState by cast.state.collectAsState()
    val tokens = LocalImmersive.current
    val accent = LocalAccent.current
    val casting = castState.casting

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .glass(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                NetworkImage(
                    url = episode.cover ?: episode.banner,
                    contentDescription = episode.title,
                    modifier = Modifier.size(46.dp),
                    contentScale = ContentScale.Crop,
                    widthPx = 200,
                )
                if (casting) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (casting) "On ${castState.device?.name ?: "the TV"}"
                    else "Episode ${episodeLabel(episode.number)}" +
                        (episode.episodeTitle?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { if (casting) cast.disconnect().also { PlayerHost.play() } else PlayerHost.playPause() }) {
                Icon(
                    imageVector = if (casting) Icons.Rounded.Cast else
                        if (snapshot.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (casting) "Stop casting" else
                        if (snapshot.playing) "Pause" else "Play",
                    tint = tokens.text,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (!casting && onNext != null) {
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next episode",
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close the player",
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
        // How far the episode got, along the strip's foot.
        val fraction = if (casting) castState.fraction else snapshot.fraction
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(2.5.dp)
                    .background(accent)
            )
        }
    }
}

/** "12", or "13.5" for a half episode. */
private fun episodeLabel(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

/** The mini player's own row, for a screen that wants it without the bar (the player's queue). */
@Composable
fun MiniPlayerRow(
    title: String,
    subtitle: String,
    cover: String?,
    fraction: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NetworkImage(
            url = cover,
            contentDescription = title,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)),
            widthPx = 180,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = accent,
            )
        }
    }
}
