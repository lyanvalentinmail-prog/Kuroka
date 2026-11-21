package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.AiringStatus
import app.kuroka.api.Anime
import app.kuroka.api.Audio
import app.kuroka.api.Catalog
import app.kuroka.api.MediaFormat
import app.kuroka.api.SearchFilters
import app.kuroka.api.Season
import app.kuroka.api.Sort
import app.kuroka.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The search: a query that is asked 350 ms after the last letter, the filters that narrow it, and
 * the answers, paged — the grid asks for the next page when it is scrolled to its end.
 *
 * A genre page is the same search with one filter already on, which is why `onlyGenre` is a function
 * of its own: it narrows and asks in one go, and the screen has nothing to know about it.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val catalog: Catalog = Graph.catalog,
) : ViewModel() {

    private val _filters = MutableStateFlow(SearchFilters(perPage = Paging.PAGE))
    /** What the search is narrowed to, including the words in the field. */
    val filters: StateFlow<SearchFilters> get() = _filters

    private val _results = MutableStateFlow<List<Anime>>(emptyList())
    val results: StateFlow<List<Anime>> get() = _results

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> get() = _loading

    private val _loadingMore = MutableStateFlow(false)
    /** The next page is being asked for (the spinner at the grid's foot). */
    val loadingMore: StateFlow<Boolean> get() = _loadingMore

    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> get() = _hasNext

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    private val _genres = MutableStateFlow<List<String>>(emptyList())
    /** Every genre the catalog knows, for the filter sheet. */
    val genres: StateFlow<List<String>> get() = _genres

    private val asked = MutableStateFlow("")

    init {
        // The words are asked 350 ms after the last one, so a title being typed is one request.
        viewModelScope.launch {
            asked
                .debounce(350)
                .distinctUntilChanged()
                .map { it.trim() }
                .collect { query ->
                    _filters.value = _filters.value.copy(query = query, page = 1)
                    search()
                }
        }
    }

    /** The words in the field; the search follows on its own. */
    fun query(text: String) {
        asked.value = text
    }

    /** The catalog's genres, once (they do not change under a user). */
    fun loadGenres() {
        if (_genres.value.isNotEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { catalog.genres() } }
            _genres.value = found.getOrNull()?.sorted() ?: emptyList()
        }
    }

    /** Narrows a filter and asks again; every setter of the sheet goes through here. */
    private fun filter(transform: (SearchFilters) -> SearchFilters) {
        _filters.value = transform(_filters.value).copy(page = 1)
        search()
    }

    fun setSort(sort: Sort) = filter { it.copy(sort = sort) }
    fun setFormat(format: MediaFormat?) = filter { it.copy(format = format) }
    fun setStatus(status: AiringStatus?) = filter { it.copy(status = status) }
    fun setSeason(season: Season?) = filter { it.copy(season = season) }
    fun setAudio(audio: Audio?) = filter { it.copy(audio = audio) }

    /** A year range: one tap asks from a year on, a second narrows it to that year alone. */
    fun setYears(from: Int?, to: Int?) = filter { it.copy(yearFrom = from, yearTo = to, year = null) }

    /** A genre wanted; a second tap on the same one leaves it out instead. */
    fun toggleGenre(genre: String) = filter { filters ->
        when (genre) {
            in filters.genres -> filters.copy(genres = filters.genres - genre)
            in filters.excludedGenres -> filters.copy(excludedGenres = filters.excludedGenres - genre)
            else -> filters.copy(genres = filters.genres + genre)
        }
    }

    /** A genre left out of the answers. */
    fun toggleExcluded(genre: String) = filter { filters ->
        when (genre) {
            in filters.excludedGenres -> filters.copy(excludedGenres = filters.excludedGenres - genre)
            else -> filters.copy(
                excludedGenres = filters.excludedGenres + genre,
                genres = filters.genres - genre,
            )
        }
    }

    /** One genre, and nothing else narrowed: a chip on the home page, or a genre page. */
    fun onlyGenre(genre: String) {
        asked.value = ""
        _filters.value = SearchFilters(perPage = Paging.PAGE, genres = listOf(genre))
        search()
    }

    /** Takes every filter off, the words included. */
    fun clearFilters() {
        asked.value = ""
        _filters.value = SearchFilters(perPage = Paging.PAGE)
        _results.value = emptyList()
        _hasNext.value = false
        _error.value = null
    }

    /** Asks for the first page of what the filters say. */
    fun search() {
        val filters = _filters.value
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { catalog.search(filters) } }
            found.onSuccess { page ->
                _results.value = page.items
                _hasNext.value = page.hasNext
            }.onFailure { why ->
                _error.value = why.message ?: "The catalog did not answer"
            }
            _loading.value = false
        }
    }

    /** The next page, added to the answers the grid already has. */
    fun more() {
        if (_loadingMore.value || !_hasNext.value) return
        val filters = _filters.value
        val next = filters.page + 1
        _loadingMore.value = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { catalog.search(filters.at(next)) } }
            found.onSuccess { page ->
                // The same anime twice would draw the same poster in two cells, so it is dropped.
                val seen = _results.value.map { it.id }.toSet()
                _results.value = _results.value + page.items.filterNot { it.id in seen }
                _hasNext.value = page.hasNext
                _filters.value = _filters.value.copy(page = next)
            }.onFailure { why ->
                _error.value = why.message ?: "The next page did not come"
            }
            _loadingMore.value = false
        }
    }

    /** The years the filter offers: this one back to 1970, when the catalog's own anime start. */
    fun years(): IntRange = 1970..catalog.currentYear()
}
