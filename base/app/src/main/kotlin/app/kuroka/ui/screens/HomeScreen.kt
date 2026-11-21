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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
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
import app.kuroka.api.WatchProgress
import app.kuroka.di.Graph
import app.kuroka.ui.components.BarAction
import app.kuroka.ui.components.ErrorRetry
import app.kuroka.ui.components.Loading
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.PosterShelf
import app.kuroka.ui.components.SectionHeader
import app.kuroka.ui.components.clockOf
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.HomeViewModel
import app.kuroka.ui.viewmodel.KurokaViewModels

/*
 * The home page: what the user was watching (a shelf of the episodes that stopped part way, each
 * one a tap from going on), then the catalog's rows — trending, this season, the most popular, what
 * airs next, the best rated — in the order Settings, Display puts them in, and a row of genres
 * under them.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

private val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery",
    "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
)

@Composable
fun HomeScreen(
    onAnime: (Int) -> Unit,
    onWatch: (Int, Double, app.kuroka.api.Audio) -> Unit,
    onSearch: (String?) -> Unit,
    onGenre: (String) -> Unit,
    onSchedule: () -> Unit,
    model: HomeViewModel = viewModel(factory = KurokaViewModels),
) {
    val sections by model.sections.collectAsState()
    val continueWatching by model.continueWatching.collectAsState()
    val loading by model.loading.collectAsState()
    val error by model.error.collectAsState()
    val season by model.season.collectAsState()
    val library = Graph.library
    val downloads = Graph.downloads

    LaunchedEffect(Unit) { model.load() }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && sections.isEmpty() && continueWatching.isEmpty() ->
                Loading(what = "Asking the catalog…")

            error != null && sections.isEmpty() -> ErrorRetry(message = error ?: "", onRetry = { model.refresh() })

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 26.dp),
            ) {
                // The page's own head: the name, and the three ways out of it.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 20.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Kuroka",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = LocalAccent.current,
                            )
                            Text(
                                text = "Anime with no ads in it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BarAction(Icons.Rounded.Search, "Search", onClick = { onSearch(null) })
                        BarAction(Icons.Rounded.CalendarMonth, "The schedule", onClick = onSchedule)
                        BarAction(Icons.Rounded.Refresh, "Ask again", onClick = { model.refresh() })
                    }
                }

                if (continueWatching.isNotEmpty()) {
                    item { SectionHeader(title = "Go on watching") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(continueWatching.size) { index ->
                                ContinueCard(
                                    progress = continueWatching[index],
                                    onPlay = {
                                        onWatch(it.animeId, it.episodeNumber, it.audio)
                                    },
                                    onAnime = { onAnime(continueWatching[index].animeId) },
                                )
                            }
                        }
                    }
                }

                sections.forEach { section ->
                    item {
                        PosterShelf(
                            title = if (section.id == "seasonal") season else sectionTitle(section.id),
                            anime = section.anime,
                            onAnime = { onAnime(it.id) },
                            progressOf = { anime -> library.progressOf(anime.id)?.takeIf { !it.completed } },
                            starsOf = { anime -> library.entryOf(anime.id)?.score ?: 0 },
                            downloadedOf = { anime -> downloads.itemsOf(anime.id).isNotEmpty() },
                            favouriteOf = { anime -> library.isFavourite(anime.id) },
                        )
                    }
                }

                item { SectionHeader(title = "By genre") }
                item { Genres(onGenre = onGenre) }

                if (error != null) {
                    item {
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The genres, as a wrap of chips (a tap is a search narrowed to one). */
@Composable
private fun Genres(onGenre: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GENRES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { genre ->
                    Pill(text = genre, onClick = { onGenre(genre) }, modifier = Modifier.weight(1f))
                }
                // A short row is padded out, so the chips keep their width.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One card of "go on watching": the episode's picture, its title, and how far it got. */
@Composable
private fun ContinueCard(
    progress: WatchProgress,
    onPlay: (WatchProgress) -> Unit,
    onAnime: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(
        modifier = Modifier
            .width(230.dp)
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
                widthPx = 460,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onPlay(progress) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Go on watching", tint = Color.White,
                    modifier = Modifier.size(24.dp))
            }
            // How far the episode got, along the picture's foot.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.fraction)
                        .height(3.dp)
                        .background(accent)
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                text = progress.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = progress.episodeLabel + (progress.episodeTitle?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (progress.durationMs > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = clockOf(progress.durationMs - progress.positionMs) + " left",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }
    }
}

private fun sectionTitle(id: String): String = when (id) {
    "trending" -> "Trending now"
    "popular" -> "Most popular"
    "airing" -> "Airing soon"
    "top" -> "Highest rated"
    "seasonal" -> "This season"
    else -> id
}
