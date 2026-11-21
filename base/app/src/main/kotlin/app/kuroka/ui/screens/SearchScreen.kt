package app.kuroka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kuroka.api.AiringStatus
import app.kuroka.api.Audio
import app.kuroka.api.MediaFormat
import app.kuroka.api.Season
import app.kuroka.api.Sort
import app.kuroka.di.Graph
import app.kuroka.ui.components.AnimeCard
import app.kuroka.ui.components.BarAction
import app.kuroka.ui.components.ChipRow
import app.kuroka.ui.components.EmptyState
import app.kuroka.ui.components.ErrorRetry
import app.kuroka.ui.components.KurokaTopBar
import app.kuroka.ui.components.Loading
import app.kuroka.ui.components.Pill
import app.kuroka.ui.components.SearchField
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.KurokaViewModels
import app.kuroka.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * The search: a field at the top, the filters a sheet narrows it with (genre, format, how it is
 * airing, season, years, sub or dub, sort), and the answers in a grid of posters that asks for the
 * next page when it is scrolled to its end.
 *
 * A genre page is the same screen with one filter already on, so a chip on the home page and the
 * filter sheet lead to the same place.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onAnime: (Int) -> Unit,
    initialQuery: String? = null,
    genre: String? = null,
    onBack: (() -> Unit)? = null,
    model: SearchViewModel = viewModel(factory = KurokaViewModels),
) {
    val filters by model.filters.collectAsState()
    val results by model.results.collectAsState()
    val loading by model.loading.collectAsState()
    val loadingMore by model.loadingMore.collectAsState()
    val hasNext by model.hasNext.collectAsState()
    val error by model.error.collectAsState()
    val genres by model.genres.collectAsState()
    val library = Graph.library
    var sheet by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()

    LaunchedEffect(Unit) {
        model.loadGenres()
        when {
            genre != null -> model.onlyGenre(genre)
            !initialQuery.isNullOrBlank() -> {
                model.query(initialQuery)
            }
        }
    }

    // The next page, asked when the grid comes to its end.
    LaunchedEffect(grid, results.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (hasNext && last >= results.size - 6) model.more()
            }
    }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null || genre != null) {
            KurokaTopBar(
                title = genre ?: "Search",
                onBack = onBack,
                actions = { BarAction(Icons.Rounded.Tune, "Filters", onClick = { sheet = true }, badge = filters.narrowed) },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SearchField(
                        value = filters.query,
                        onValue = { model.query(it) },
                        onSearch = { model.search() },
                        autoFocus = true,
                    )
                }
                BarAction(Icons.Rounded.Tune, "Filters", onClick = { sheet = true }, badge = filters.narrowed)
            }
        }

        // What the search is narrowed to, as chips that can be taken off one by one.
        val active = remember(filters) {
            buildList {
                filters.genres.forEach { genre -> add(genre to { model.toggleGenre(genre) }) }
                filters.excludedGenres.forEach { genre -> add("− $genre" to { model.toggleExcluded(genre) }) }
                filters.format?.let { add(it.label to { model.setFormat(null) }) }
                filters.status?.let { add(it.label to { model.setStatus(null) }) }
                filters.season?.let { add(it.label to { model.setSeason(null) }) }
                filters.audio?.let { audio ->
                    add((if (audio == Audio.DUB) "Dubbed" else "Subtitled") to { model.setAudio(null) })
                }
                if (filters.yearFrom != null || filters.yearTo != null) {
                    val label = if (filters.yearFrom == filters.yearTo) "${filters.yearFrom}"
                    else if (filters.yearTo == null) "From ${filters.yearFrom}"
                    else "${filters.yearFrom}–${filters.yearTo}"
                    add(label to { model.setYears(null, null) })
                }
                if (filters.sort != Sort.POPULARITY) add(filters.sort.label to { model.setSort(Sort.POPULARITY) })
            }
        }
        if (active.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(active.size) { index ->
                    val chip = active[index]
                    Pill(text = chip.first, selected = true, onClick = chip.second)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                loading && results.isEmpty() -> Loading(
                    what = if (filters.query.isBlank()) "Asking for the list…" else "Searching…"
                )

                error != null && results.isEmpty() -> ErrorRetry(message = error ?: "", onRetry = { model.search() })

                results.isEmpty() && !loading -> EmptyState(
                    title = if (filters.query.isBlank() && !filters.narrowed) "Search an anime"
                    else "Nothing answered that",
                    body = if (filters.query.isBlank() && !filters.narrowed)
                        "Type a title, or narrow a list with the filters."
                    else "Try another spelling, or take a filter off.",
                )

                else -> LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(minSize = 118.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results.size) { index ->
                        val anime = results[index]
                        AnimeCard(
                            anime = anime,
                            width = 160,
                            onClick = { onAnime(anime.id) },
                            progress = library.progressOf(anime.id)?.takeIf { !it.completed },
                            stars = library.entryOf(anime.id)?.score ?: 0,
                            favourite = library.isFavourite(anime.id),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (loadingMore || hasNext) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (loadingMore) {
                                    CircularProgressIndicator(
                                        color = LocalAccent.current,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.height(18.dp).width(18.dp),
                                    )
                                } else {
                                    TextButton(onClick = { model.more() }) { Text("More") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheet) {
        FilterSheet(
            filters = filters,
            genres = genres,
            years = model.years(),
            onDismiss = { sheet = false },
            onClear = { model.clearFilters() },
            onSort = { model.setSort(it) },
            onFormat = { model.setFormat(it) },
            onStatus = { model.setStatus(it) },
            onSeason = { model.setSeason(it) },
            onYears = { from, to -> model.setYears(from, to) },
            onAudio = { model.setAudio(it) },
            onGenre = { model.toggleGenre(it) },
            onExclude = { model.toggleExcluded(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    filters: app.kuroka.api.SearchFilters,
    genres: List<String>,
    years: IntRange,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSort: (Sort) -> Unit,
    onFormat: (MediaFormat?) -> Unit,
    onStatus: (AiringStatus?) -> Unit,
    onSeason: (Season?) -> Unit,
    onYears: (Int?, Int?) -> Unit,
    onAudio: (Audio?) -> Unit,
    onGenre: (String) -> Unit,
    onExclude: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .height(560.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Narrow the search",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear") }
            }

            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                item { SettingGroup("Sort by") }
                item {
                    Wrap(items = Sort.entries.map { it.label },
                        selected = setOf(filters.sort.label),
                        onPick = { label -> Sort.entries.firstOrNull { it.label == label }?.let(onSort) })
                }
                item { SettingGroup("Kind") }
                item {
                    Wrap(
                        items = listOf("Any") + MediaFormat.entries.filter { it != MediaFormat.UNKNOWN }.map { it.label },
                        selected = setOf(filters.format?.label ?: "Any"),
                        onPick = { label ->
                            onFormat(MediaFormat.entries.firstOrNull { it.label == label })
                        },
                    )
                }
                item { SettingGroup("Airing") }
                item {
                    Wrap(
                        items = listOf("Any") + AiringStatus.entries.filter { it != AiringStatus.UNKNOWN }.map { it.label },
                        selected = setOf(filters.status?.label ?: "Any"),
                        onPick = { label -> onStatus(AiringStatus.entries.firstOrNull { it.label == label }) },
                    )
                }
                item { SettingGroup("Season") }
                item {
                    Wrap(
                        items = listOf("Any") + Season.entries.filter { it != Season.UNKNOWN }.map { it.label },
                        selected = setOf(filters.season?.label ?: "Any"),
                        onPick = { label -> onSeason(Season.entries.firstOrNull { it.label == label }) },
                    )
                }
                item { SettingGroup("Audio") }
                item {
                    Wrap(
                        items = listOf("Any", "Subtitled", "Dubbed"),
                        selected = setOf(
                            when (filters.audio) {
                                Audio.SUB -> "Subtitled"
                                Audio.DUB -> "Dubbed"
                                null -> "Any"
                            }
                        ),
                        onPick = { label ->
                            onAudio(
                                when (label) {
                                    "Subtitled" -> Audio.SUB
                                    "Dubbed" -> Audio.DUB
                                    else -> null
                                }
                            )
                        },
                    )
                }
                item { SettingGroup("Years") }
                item { YearsRow(years = years, from = filters.yearFrom, to = filters.yearTo, onYears = onYears) }
                item { SettingGroup("Genres") }
                item {
                    if (genres.isEmpty()) {
                        Text(
                            text = "The catalog's genres are being asked for…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    } else {
                        GenreWrap(
                            genres = genres,
                            wanted = filters.genres.toSet(),
                            excluded = filters.excludedGenres.toSet(),
                            onPick = onGenre,
                            onExclude = onExclude,
                        )
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

/** A group of chips that wrap, with one of them picked. */
@Composable
private fun Wrap(items: List<String>, selected: Set<String>, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Pill(text = item, selected = item in selected, onClick = { onPick(item) },
                        modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The genres, tapped to want one and long-pressed to leave one out. */
@Composable
private fun GenreWrap(
    genres: List<String>,
    wanted: Set<String>,
    excluded: Set<String>,
    onPick: (String) -> Unit,
    onExclude: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tap to want a genre; tap it again to leave it out.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        genres.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { genre ->
                    val state = when (genre) {
                        in wanted -> 1
                        in excluded -> 2
                        else -> 0
                    }
                    Pill(
                        text = when (state) {
                            2 -> "− $genre"
                            else -> genre
                        },
                        selected = state != 0,
                        onClick = { if (state == 1) onExclude(genre) else onPick(genre) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The years, as two rows of decades and the ones in them. */
@Composable
private fun YearsRow(years: IntRange, from: Int?, to: Int?, onYears: (Int?, Int?) -> Unit) {
    val decades = (years.last downTo 1970 step 10).toList()
    var decade by remember { mutableStateOf(from?.let { it - it % 10 } ?: decades.first()) }
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(decades.size) { index ->
                val value = decades[index]
                Pill(
                    text = "${value}s",
                    selected = value == decade,
                    onClick = { decade = value },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val inDecade = (decade..minOf(decade + 9, years.last)).toList()
        Wrap(
            items = listOf("Any") + inDecade.map { it.toString() },
            selected = setOf((from ?: to)?.toString() ?: "Any"),
            onPick = { label ->
                if (label == "Any") onYears(null, null)
                else {
                    val year = label.toIntOrNull() ?: return@Wrap
                    // One tap asks from that year on; a second one narrows it to that year alone.
                    if (from == year && to == null) onYears(year, year) else onYears(year, null)
                }
            },
        )
        if (from != null || to != null) {
            Text(
                text = if (from == to) "Of $from" else if (to == null) "From $from on" else "$from to $to",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAccent.current,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}
