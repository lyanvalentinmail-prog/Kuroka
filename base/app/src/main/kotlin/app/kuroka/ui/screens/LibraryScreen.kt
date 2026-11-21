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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.AccountPhase
import app.kuroka.api.Favourite
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.api.WatchProgress
import app.kuroka.di.Graph
import app.kuroka.ui.components.BarAction
import app.kuroka.ui.components.EmptyState
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.Notice
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.SearchField
import app.kuroka.ui.components.SectionHeader
import app.kuroka.ui.components.StatTile
import app.kuroka.ui.components.Stars
import app.kuroka.ui.components.clockOf
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.theme.PosterShape
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay

/*
 * The library: what the user is watching, what they plan to watch, what they finished and what they
 * gave up on, as tabs; the favourites; the episodes that stopped part way, with a "forget" on each;
 * and the numbers all of it adds up to.
 *
 * Signed in to AniList, the lists are the account's own and a pull brings them down; signed out,
 * they are the phone's, kept in the same tables and pushed when a sign-in happens.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun LibraryScreen(
    onAnime: (Int) -> Unit,
    onWatch: (Int, Double, app.kuroka.api.Audio) -> Unit,
    onAccount: () -> Unit,
    onDownloads: () -> Unit,
    model: LibraryViewModel = viewModel(factory = KurokaViewModels),
) {
    val entries by model.entries.collectAsState()
    val rows by model.rows.collectAsState()
    val favouriteRows by model.favouriteRows.collectAsState()
    val progress by model.progress.collectAsState()
    val tab by model.tab.collectAsState()
    val query by model.query.collectAsState()
    val stats by model.stats.collectAsState()
    val phase by model.phase.collectAsState()
    val syncing by model.syncing.collectAsState()
    val notice by model.notice.collectAsState()
    var showStats by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3200)
            model.clearNotice()
        }
    }

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
                    Text(
                        text = "Your library",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    BarAction(Icons.Rounded.Download, "The downloads", onClick = onDownloads)
                    BarAction(
                        icon = if (phase == AccountPhase.SIGNED_IN) Icons.Rounded.Sync else Icons.Rounded.AccountCircle,
                        contentDescription = if (phase == AccountPhase.SIGNED_IN) "Pull your lists down" else "Your account",
                        onClick = { if (phase == AccountPhase.SIGNED_IN) model.sync() else onAccount() },
                    )
                }
            }
            if (syncing) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        color = LocalAccent.current,
                    )
                }
            }

            // The numbers, folded away until they are asked for.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${stats.anime} anime · ${stats.episodes} episodes · ${stats.hours} h watched",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(text = if (showStats) "Fewer numbers" else "Your numbers", onClick = { showStats = !showStats })
                }
            }
            if (showStats) {
                item { StatsGrid(model = model) }
            }

            // The episodes that stopped part way.
            if (progress.isNotEmpty()) {
                item { SectionHeader(title = "Go on watching", subtitle = "${progress.size} episodes part way") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(progress.size) { index ->
                            ProgressCard(
                                progress = progress[index],
                                onPlay = { onWatch(it.animeId, it.episodeNumber, it.audio) },
                                onAnime = { onAnime(progress[index].animeId) },
                                onForget = { model.forget(progress[index]) },
                            )
                        }
                    }
                }
                if (progress.size > 1) {
                    item {
                        Text(
                            text = "Forget the whole history",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .clickable { model.clearHistory() },
                        )
                    }
                }
            }

            if (favouriteRows.isNotEmpty()) {
                item { SectionHeader(title = "Your favourites", subtitle = "${favouriteRows.size} anime with a heart") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(favouriteRows.size) { index ->
                            val favourite = favouriteRows[index]
                            FavouriteCard(
                                favourite = favourite,
                                onClick = { onAnime(favourite.animeId) },
                                onHeart = { model.toggleFavourite(favourite) },
                            )
                        }
                    }
                }
            }

            // The lists, as tabs.
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ListStatus.tabs.size) { index ->
                        val status = ListStatus.tabs[index]
                        val count = entries.count { it.status == status }
                        Pill(
                            text = if (count > 0) "${status.label} $count" else status.label,
                            selected = status == tab,
                            onClick = { model.setTab(status) },
                        )
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    SearchField(
                        value = query,
                        onValue = { model.setQuery(it) },
                        hint = "Search your library",
                    )
                }
            }

            if (rows.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing on this list yet",
                        body = "Open an anime's page and put it on a list; a rated episode goes on " +
                            "your completed list by itself.",
                        modifier = Modifier.height(260.dp),
                    )
                }
            } else {
                items(rows.size) { index ->
                    val entry = rows[index]
                    LibraryRow(
                        entry = entry,
                        onClick = { onAnime(entry.animeId) },
                        onPlay = {
                            onWatch(entry.animeId, (entry.progress + 1).toDouble(), model.audioOf(entry))
                        },
                        onRate = { model.setScore(entry, it) },
                        onStatus = { model.setStatus(entry, it) },
                        onProgress = { model.setProgress(entry, it) },
                        onRemove = { model.remove(entry) },
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            Notice(text = notice, onDismiss = { model.clearNotice() })
        }
    }
}

/** The numbers the library adds up to, as a grid of tiles. */
@Composable
private fun StatsGrid(model: LibraryViewModel) {
    val stats by model.stats.collectAsState()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(value = stats.anime.toString(), label = "anime on your lists", modifier = Modifier.weight(1f))
            StatTile(value = stats.episodes.toString(), label = "episodes watched", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(value = "${stats.hours} h", label = "of anime (${String.format("%.1f", stats.days)} days)",
                modifier = Modifier.weight(1f))
            StatTile(
                value = if (stats.rated > 0) String.format("%.1f", stats.averageStars) else "—",
                label = "average of ${stats.rated} rated",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(value = stats.completed.toString(), label = "completed", modifier = Modifier.weight(1f))
            StatTile(value = stats.watching.toString(), label = "watching", modifier = Modifier.weight(1f))
            StatTile(value = stats.planned.toString(), label = "planned", modifier = Modifier.weight(1f))
            StatTile(value = stats.favourites.toString(), label = "favourites", modifier = Modifier.weight(1f))
        }
        if (stats.topGenres.isNotEmpty()) {
            SectionHeader(title = "What you watch most")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stats.topGenres.take(4).forEach { (genre, count) ->
                    Pill(text = "$genre $count")
                }
            }
        }
    }
}

/** One episode that stopped part way: its picture, how far it got, and the taps it takes. */
@Composable
private fun ProgressCard(
    progress: WatchProgress,
    onPlay: (WatchProgress) -> Unit,
    onAnime: () -> Unit,
    onForget: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .width(216.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onAnime)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            NetworkImage(
                url = progress.episodeImage ?: progress.cover,
                contentDescription = progress.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 420,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onPlay(progress) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Go on watching", tint = Color.White,
                    modifier = Modifier.size(23.dp))
            }
            Box(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Box(Modifier.fillMaxWidth(progress.fraction).height(3.dp).background(accent))
            }
            IconButton(
                onClick = onForget,
                modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Forget this episode",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = progress.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${progress.episodeLabel} · ${clockOf(progress.durationMs - progress.positionMs)} left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One favourite, as a poster with its heart. */
@Composable
private fun FavouriteCard(favourite: Favourite, onClick: () -> Unit, onHeart: () -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box {
            NetworkImage(
                url = favourite.cover,
                contentDescription = favourite.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(PosterShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                widthPx = 320,
            )
            IconButton(
                onClick = onHeart,
                modifier = Modifier.align(Alignment.BottomEnd).size(34.dp)
            ) {
                Icon(
                    imageVector = if (favourite.remote) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Take the heart off",
                    tint = if (favourite.remote) Color(0xFFFF4F7B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Text(
            text = favourite.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** One anime on a list: its poster, how far it got, its stars, and what can be done with it. */
@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRate: (Int) -> Unit,
    onStatus: (ListStatus) -> Unit,
    onProgress: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val accent = LocalAccent.current
    var open by remember(entry.animeId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkImage(
                url = entry.cover,
                contentDescription = entry.title,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(PosterShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                widthPx = 220,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        "Episode ${entry.progress}" + (entry.totalEpisodes?.let { " of $it" } ?: ""),
                        entry.audio.name.lowercase().let { if (it == "dub") "Dubbed" else "Subtitled" },
                        entry.source?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stars(stars = entry.score, onRate = onRate, size = 20)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (entry.totalEpisodes != null && entry.totalEpisodes!! > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((entry.progress.toFloat() / entry.totalEpisodes!!).coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(accent)
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Go on watching", tint = accent,
                        modifier = Modifier.size(26.dp))
                }
                Text(
                    text = if (open) "Done" else "Edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { open = !open }.padding(4.dp),
                )
            }
        }

        // The row's own editor: the list, the episode it got to, and taking it off.
        if (open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ListStatus.tabs.chunked(3).forEach { column ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            column.forEach { status ->
                                Pill(
                                    text = status.label,
                                    selected = status == entry.status,
                                    onClick = { onStatus(status) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Episode",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Stepper(
                        value = entry.progress,
                        max = entry.totalEpisodes ?: 999,
                        onChange = onProgress,
                    )
                }
                Text(
                    text = "Take it off your library",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onRemove),
                )
            }
        }
    }
}

/** − and + around a number (the episode a list row got to). */
@Composable
private fun Stepper(value: Int, max: Int, onChange: (Int) -> Unit) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton("−") { onChange((value - 1).coerceAtLeast(0)) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(30.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+") { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
    }
}
