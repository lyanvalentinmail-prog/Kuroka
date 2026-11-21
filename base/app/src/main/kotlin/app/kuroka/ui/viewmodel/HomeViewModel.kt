package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.Anime
import app.kuroka.api.WatchProgress
import app.kuroka.di.Graph
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.prefs.Prefs
import app.kuroka.sources.anilist.AniListCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The home page: the rows of the catalog, in the order the user put them in, and the episodes that
 * stopped part way.
 *
 * The rows are asked for all at once — [AniListCatalog.home] runs them side by side, so a slow one
 * does not hold the others up — and kept for the life of the view model: coming back to the tab shows
 * what was already read rather than asking again, and the bar's "ask again" asks once more.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** One row of the home page: what it is called, and the anime in it. */
data class Section(
    val id: String,
    val anime: List<Anime> = emptyList(),
)

/** The catalog's answers for the home page, kept as one value. */
data class HomeRowsState(
    val trending: List<Anime> = emptyList(),
    val seasonal: List<Anime> = emptyList(),
    val popular: List<Anime> = emptyList(),
    val airing: List<Anime> = emptyList(),
    val top: List<Anime> = emptyList(),
) {
    /** One row by the id the settings list them under. */
    fun of(id: String): List<Anime> = when (id) {
        "trending" -> trending
        "seasonal" -> seasonal
        "popular" -> popular
        "airing" -> airing
        "top" -> top
        else -> emptyList()
    }

    /** True when any row came back (an empty home page is an error worth saying). */
    fun any(): Boolean = trending.isNotEmpty() || seasonal.isNotEmpty() || popular.isNotEmpty() ||
        airing.isNotEmpty() || top.isNotEmpty()
}

class HomeViewModel(
    private val catalog: AniListCatalog = Graph.anilist,
    private val library: LibraryStore = Graph.library,
    private val prefs: Prefs = Graph.prefs,
) : ViewModel() {

    private val _rows = MutableStateFlow(HomeRowsState())
    /** What the catalog answered. */
    val rows: StateFlow<HomeRowsState> get() = _rows

    private val _sections = MutableStateFlow<List<Section>>(emptyList())
    /** The rows to draw, in the order Settings, Display put them in. */
    val sections: StateFlow<List<Section>> get() = _sections

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> get() = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    private val _season = MutableStateFlow("")
    /** "Fall 2026": the label the season's row is called by. */
    val season: StateFlow<String> get() = _season

    /** The episodes part way through, newest first: the shelf the home page opens with. */
    val continueWatching: StateFlow<List<WatchProgress>> = library.progress
        .map { progress -> progress.filterNot { it.completed }.take(12) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // A change of order, or of which rows are on, redraws the page without asking again.
        viewModelScope.launch {
            prefs.settings.collect { settings -> orderSections(settings.homeSections) }
        }
    }

    /** Asks the catalog for the rows, when the page is first drawn (and again when asked). */
    fun load(force: Boolean = false) {
        if (_loading.value && !force) return
        if (_rows.value.any() && !force) {
            orderSections(prefs.now.homeSections)
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { catalog.home(perPage = Paging.ROW) } }
            val (year, season) = catalog.currentSeason()
            _season.value = "${season.label} $year"
            found.onSuccess { home ->
                _rows.value = HomeRowsState(
                    trending = home.trending,
                    seasonal = home.seasonal,
                    popular = home.popular,
                    airing = home.airing,
                    top = home.top,
                )
                _error.value = if (home.any()) null else "The catalog answered with nothing"
            }.onFailure { why ->
                _error.value = why.message ?: "The catalog did not answer"
            }
            _loading.value = false
            orderSections(prefs.now.homeSections)
        }
    }

    /** Asks for the rows again: the top bar's own button, or a pull down. */
    fun refresh() = load(force = true)

    /** Puts the rows in the order the settings ask for, leaving out the ones that came back empty. */
    private fun orderSections(order: List<String>) {
        val rows = _rows.value
        _sections.value = order.mapNotNull { id ->
            val anime = rows.of(id)
            // "Go on watching" is the library's shelf, not a row of the catalog.
            if (id == "continue" || anime.isEmpty()) null else Section(id = id, anime = anime)
        }
    }
}
