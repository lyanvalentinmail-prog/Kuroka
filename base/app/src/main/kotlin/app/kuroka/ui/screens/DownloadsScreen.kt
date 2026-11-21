package app.kuroka.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.DownloadItem
import app.kuroka.api.DownloadState
import app.kuroka.di.Graph
import app.kuroka.ui.components.BarAction
import app.kuroka.ui.components.EmptyState
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.Notice
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.SearchField
import app.kuroka.ui.components.SectionHeader
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.DownloadsViewModel
import app.kuroka.ui.viewmodel.KurokaViewModels
import kotlinx.coroutines.delay

/*
 * The downloads: what is coming down (a bar, a size, a pause and a take-away on each), what is on
 * the phone (a play that needs no network, and a share that hands the file to another app), and the
 * room they take.
 *
 * A download is the episode's own stream, written through the same data source the player reads, so
 * the two never disagree about headers or subtitles.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun DownloadsScreen(
    onAnime: (Int) -> Unit,
    onOpen: (DownloadItem) -> Unit,
    model: DownloadsViewModel = viewModel(factory = KurokaViewModels),
) {
    val rows by model.rows.collectAsState()
    val items by model.items.collectAsState()
    val active by model.active.collectAsState()
    val query by model.query.collectAsState()
    val only by model.only.collectAsState()
    val notice by model.notice.collectAsState()
    val busy by model.busy.collectAsState()
    val storage by model.storage.collectAsState()
    val downloads = Graph.downloads

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3600)
            model.clearNotice()
        }
    }

    val coming = rows.filter { it.state.isActive || it.state == DownloadState.PAUSED }
    val finished = rows.filter { it.state == DownloadState.COMPLETED }
    val failed = rows.filter { it.state == DownloadState.FAILED }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Downloads",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = storage.ifBlank {
                                if (active > 0) "$active coming down" else "Nothing coming down"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (coming.isNotEmpty()) {
                        BarAction(
                            icon = Icons.Rounded.Pause,
                            contentDescription = "Pause them all",
                            onClick = { model.pauseAll() },
                        )
                        BarAction(
                            icon = Icons.Rounded.PlayArrow,
                            contentDescription = "Start them all again",
                            onClick = { model.resumeAll() },
                        )
                    }
                    if (finished.isNotEmpty()) {
                        BarAction(
                            icon = Icons.Rounded.Delete,
                            contentDescription = "Take the finished ones away",
                            onClick = { model.clearFinished() },
                        )
                    }
                }
            }

            item {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SearchField(
                        value = query,
                        onValue = { model.setQuery(it) },
                        hint = "Search your downloads",
                    )
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val filters = listOf(
                        null to "Everything",
                        DownloadState.DOWNLOADING to "Coming down",
                        DownloadState.PAUSED to "Paused",
                        DownloadState.COMPLETED to "On the phone",
                        DownloadState.FAILED to "Failed",
                    )
                    items(filters.size) { index ->
                        val (state, label) = filters[index]
                        val count = if (state == null) items.size else items.count { it.state == state }
                        Pill(
                            text = if (count > 0) "$label $count" else label,
                            selected = only == state,
                            onClick = { model.setOnly(if (only == state) null else state) },
                        )
                    }
                }
            }

            if (busy != null) {
                item {
                    Text(
                        text = busy ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAccent.current,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }

            if (rows.isEmpty()) {
                item {
                    EmptyState(
                        title = if (items.isEmpty()) "Nothing taken down yet" else "Nothing answers that",
                        body = if (items.isEmpty())
                            "Open an anime's page and tap a download, or take the next five episodes " +
                                "down from the page's own button."
                        else "Try another word, or take the filter off.",
                        icon = Icons.Rounded.Download,
                        modifier = Modifier.height(280.dp),
                    )
                }
            } else {
                if (coming.isNotEmpty()) {
                    item { SectionHeader(title = "Coming down", subtitle = "${coming.size} episodes") }
                    items(coming.size) { index ->
                        DownloadRow(
                            item = coming[index],
                            onPlay = { },
                            onPause = { model.pause(coming[index]) },
                            onResume = { model.resume(coming[index]) },
                            onRemove = { model.remove(coming[index]) },
                            onShare = { model.export(coming[index]) },
                            onAnime = { onAnime(coming[index].animeId) },
                        )
                    }
                }
                if (failed.isNotEmpty()) {
                    item { SectionHeader(title = "Failed", subtitle = "${failed.size} episodes") }
                    items(failed.size) { index ->
                        DownloadRow(
                            item = failed[index],
                            onPlay = { },
                            onPause = { model.pause(failed[index]) },
                            onResume = { model.resume(failed[index]) },
                            onRemove = { model.remove(failed[index]) },
                            onShare = { model.export(failed[index]) },
                            onAnime = { onAnime(failed[index].animeId) },
                        )
                    }
                }
                if (finished.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "On the phone",
                            subtitle = "${finished.size} episodes · ${downloads.usedLabel()}",
                        )
                    }
                    items(finished.size) { index ->
                        DownloadRow(
                            item = finished[index],
                            onPlay = { onOpen(finished[index]) },
                            onPause = { model.pause(finished[index]) },
                            onResume = { model.resume(finished[index]) },
                            onRemove = { model.remove(finished[index]) },
                            onShare = { model.export(finished[index]) },
                            onAnime = { onAnime(finished[index].animeId) },
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            Notice(text = notice, onDismiss = { model.clearNotice() })
        }
    }
}

/** One download: its picture, how far it has got, and what can be done with it. */
@Composable
private fun DownloadRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    onAnime: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (item.state == DownloadState.COMPLETED) onPlay else onAnime)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            NetworkImage(
                url = item.episodeImage ?: item.cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 300,
            )
            when (item.state) {
                DownloadState.COMPLETED -> Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play it from the phone",
                        tint = Color.White, modifier = Modifier.size(19.dp))
                }

                DownloadState.FAILED -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "It failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).size(20.dp),
                )

                else -> Unit
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    item.episodeLabel,
                    item.episodeTitle?.takeIf { it.isNotBlank() && it != item.episodeLabel },
                    if (item.audio.name == "DUB") "Dubbed" else null,
                    item.server.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            when (item.state) {
                DownloadState.COMPLETED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accent,
                        modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${item.sizeLabel} on the phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }

                DownloadState.FAILED -> Text(
                    text = item.failure ?: "It did not come down",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                else -> Column {
                    LinearProgressIndicator(
                        progress = { item.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = if (item.state == DownloadState.PAUSED)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOfNotNull(
                            when (item.state) {
                                DownloadState.QUEUED -> "Waiting its turn"
                                DownloadState.DOWNLOADING -> "${(item.fraction * 100).toInt()}%"
                                DownloadState.PAUSED -> "Paused"
                                DownloadState.REMOVING -> "Going away"
                                else -> null
                            },
                            item.sizeLabel,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        when (item.state) {
            DownloadState.DOWNLOADING, DownloadState.QUEUED ->
                IconButton(onClick = onPause) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause it",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }

            DownloadState.PAUSED, DownloadState.FAILED ->
                IconButton(onClick = onResume) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Start it again", tint = accent,
                        modifier = Modifier.size(20.dp))
                }

            DownloadState.COMPLETED ->
                IconButton(onClick = onShare) {
                    Icon(Icons.Rounded.IosShare, contentDescription = "Hand the file to another app",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                }

            else -> Unit
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Delete, contentDescription = "Take it away",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
    }
}
