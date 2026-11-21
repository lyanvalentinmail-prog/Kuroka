package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.Anime
import app.kuroka.api.Audio
import app.kuroka.api.Catalog
import app.kuroka.api.Favourite
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.api.WatchProgress
import app.kuroka.di.Graph
import app.kuroka.host.account.AccountRepo
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.library.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The library: what is on each list, the favourites, what has been watched and rated, and the
 * numbers the page shows at its top (how many anime, how many episodes, how many hours, the
 * average of the stars given). Everything comes from the phone's own rows; the account's copy is
 * pulled when there is one, and what the user changes here is pushed a few seconds later.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

class LibraryViewModel(
    private val library: LibraryStore = Graph.library,
    private val account: AccountRepo = Graph.account,
    private val catalog: Catalog = Graph.catalog,
) : ViewModel() {

    /** The lists the tabs show, in the order [ListStatus.tabs] gives them. */
    val entries: StateFlow<List<LibraryEntry>> = library.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<Favourite>> = library.favourites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Continue watching, and the history under it (the episodes seen to their end). */
    val progress: StateFlow<List<WatchProgress>> = library.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val phase: StateFlow<app.kuroka.api.AccountPhase> = account.phase
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), account.phase.value)

    private val _tab = MutableStateFlow(ListStatus.WATCHING)
    /** The list being shown. */
    val tab: StateFlow<ListStatus> get() = _tab

    private val _query = MutableStateFlow("")
    /** What the field at the top of the library filters by. */
    val query: StateFlow<String> get() = _query

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> get() = _stats

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> get() = _syncing

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice

    /** The rows of the tab being shown, filtered by the field. */
    val rows: StateFlow<List<LibraryEntry>> =
        combine(entries, _tab, _query) { all, tab, query ->
            val wanted = all.filter { it.status == tab }
            if (query.isBlank()) wanted
            else wanted.filter { it.title.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The favourites, filtered by the same field. */
    val favouriteRows: StateFlow<List<Favourite>> =
        combine(favourites, _query) { all, query ->
            if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            entries.collect { _stats.value = library.stats() }
        }
    }

    fun setTab(status: ListStatus) {
        _tab.value = status
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    /** The stars of an anime, changed from the list row (0 takes them off). */
    fun setScore(entry: LibraryEntry, stars: Int) {
        viewModelScope.launch {
            library.setScore(entry.animeId, stars, entry.title, entry.cover)
            account.syncSoon()
            _stats.value = library.stats()
        }
    }

    /** An anime moved to another list from its row's menu. */
    fun setStatus(entry: LibraryEntry, status: ListStatus) {
        viewModelScope.launch {
            library.setStatus(entry, status)
            account.syncSoon()
            _stats.value = library.stats()
            _notice.value = "${entry.title} is on your ${status.label.lowercase()} list"
        }
    }

    /** How many episodes have been watched, changed by hand from a row. */
    fun setProgress(entry: LibraryEntry, episodes: Int) {
        viewModelScope.launch {
            library.setEpisodeProgress(entry.animeId, episodes)
            account.syncSoon()
            _stats.value = library.stats()
        }
    }

    fun remove(entry: LibraryEntry) {
        viewModelScope.launch {
            library.remove(entry.animeId)
            account.syncSoon()
            _stats.value = library.stats()
            _notice.value = "${entry.title} is off your library"
        }
    }

    fun toggleFavourite(favourite: Favourite) {
        viewModelScope.launch {
            library.toggleFavourite(favourite.animeId, favourite.title, favourite.cover)
            account.syncSoon()
            _stats.value = library.stats()
        }
    }

    /** A row of the history is taken off (the episode is not watched any more). */
    fun forget(progress: WatchProgress) {
        viewModelScope.launch {
            library.forgetProgress(progress.animeId)
            _stats.value = library.stats()
        }
    }

    /** The whole history, gone. */
    fun clearHistory() {
        viewModelScope.launch {
            library.clearProgress()
            _notice.value = "The history is empty"
        }
    }

    /** Pulls the account's list and favourites, then pushes what the phone changed. */
    fun sync() {
        if (account.phase.value != app.kuroka.api.AccountPhase.SIGNED_IN) {
            _notice.value = "Sign in to AniList first (Settings, Account)"
            return
        }
        _syncing.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { account.pullAll() } }
            _syncing.value = false
            _stats.value = library.stats()
            _notice.value = "Your list is up to date"
        }
    }

    /**
     * The anime of a list row, asked when the row is tapped and the page needs more than the row
     * has (its cover is enough for the list, but the player wants the whole identity).
     */
    suspend fun animeOf(animeId: Int): Anime? =
        withContext(Dispatchers.IO) { runCatching { catalog.details(animeId) }.getOrNull() }

    /** The audio a row's "go on watching" starts with. */
    fun audioOf(entry: LibraryEntry): Audio = entry.audio

    fun clearNotice() {
        _notice.value = null
    }
}
