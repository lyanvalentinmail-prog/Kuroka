package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.Anime
import app.kuroka.api.AnimeRef
import app.kuroka.api.Audio
import app.kuroka.api.Catalog
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.EpisodeResolver
import app.kuroka.api.Favourite
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.api.Relation
import app.kuroka.api.Seasons
import app.kuroka.api.WatchProgress
import app.kuroka.di.Graph
import app.kuroka.host.account.AccountRepo
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * One anime's page: the anime itself, the episodes a source has of it, the seasons of its story, the
 * anime related to it and the ones to watch after it — and what the user has done with it (its list,
 * its stars, its heart, the episode it stopped in, the episodes on the phone).
 *
 * The episode list comes from the first source that has one, and every source that has one is kept in
 * [bundles] for the sheet that switches between them: an anime one source lists as a special and
 * another as a season plays either way, and a source that is down is not the end of the page.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

class AnimeViewModel(
    private val catalog: Catalog = Graph.catalog,
    private val resolver: EpisodeResolver = Graph.resolver,
    private val library: LibraryStore = Graph.library,
    private val account: AccountRepo = Graph.account,
    private val downloads: DownloadRepo = Graph.downloads,
    private val prefs: Prefs = Graph.prefs,
) : ViewModel() {

    private val _anime = MutableStateFlow<Anime?>(null)
    val anime: StateFlow<Anime?> get() = _anime

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> get() = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    private val _bundle = MutableStateFlow<EpisodeBundle?>(null)
    /** The bundle the page is showing: the first source that answered, or the one picked by hand. */
    val bundle: StateFlow<EpisodeBundle?> get() = _bundle

    private val _bundles = MutableStateFlow<List<EpisodeBundle>>(emptyList())
    /** Every source that has this anime, for the sheet that switches between them. */
    val bundles: StateFlow<List<EpisodeBundle>> get() = _bundles

    private val _audio = MutableStateFlow(Audio.SUB)
    /** Subtitled or dubbed: the list shown, and what a play asks for. */
    val audio: StateFlow<Audio> get() = _audio

    private val _seasons = MutableStateFlow(Seasons(emptyList(), 0))
    val seasons: StateFlow<Seasons> get() = _seasons

    private val _relations = MutableStateFlow<List<Relation>>(emptyList())
    val relations: StateFlow<List<Relation>> get() = _relations

    private val _recommendations = MutableStateFlow<List<Anime>>(emptyList())
    val recommendations: StateFlow<List<Anime>> get() = _recommendations

    private val _entry = MutableStateFlow<LibraryEntry?>(null)
    /** The library's own row for this anime, or null when it is on no list. */
    val entry: StateFlow<LibraryEntry?> get() = _entry

    private val _favourite = MutableStateFlow<Favourite?>(null)
    val favourite: StateFlow<Favourite?> get() = _favourite

    private val _progress = MutableStateFlow<WatchProgress?>(null)
    /** The episode that stopped part way, which is where the page's big button goes on from. */
    val progress: StateFlow<WatchProgress?> get() = _progress

    private val _notice = MutableStateFlow<String?>(null)
    /** A word at the foot of the page ("Coming down", "On your completed list"). */
    val notice: StateFlow<String?> get() = _notice

    private var loadJob: Job? = null
    private var askedId = 0

    /**
     * Opens one anime's page. The anime itself comes first — the page has a title and a poster before
     * the sources have answered — and the episode list, the seasons and the relations follow.
     */
    fun load(animeId: Int, force: Boolean = false) {
        if (animeId == askedId && _anime.value != null && !force) {
            readLibrary(animeId)
            return
        }
        askedId = animeId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _bundle.value = null
            _bundles.value = emptyList()
            readLibrary(animeId)

            val found = withContext(Dispatchers.IO) { runCatching { catalog.details(animeId) } }
            val anime = found.getOrNull()
            if (anime == null) {
                _error.value = found.exceptionOrNull()?.message ?: "This anime is not in the catalog"
                _loading.value = false
                return@launch
            }
            _anime.value = anime
            _loading.value = false
            readLibrary(animeId)

            // The audio the user asked for last, unless this anime has no dub at all.
            _audio.value = prefs.now.player.defaultAudio

            val ref = anime.toRef()
            val everywhere = withContext(Dispatchers.IO) {
                runCatching { resolver.episodesEverywhere(ref) }.getOrDefault(emptyList())
            }
            if (animeId != askedId) return@launch
            _bundles.value = everywhere
            _bundle.value = everywhere.firstOrNull() ?: withContext(Dispatchers.IO) {
                runCatching { resolver.episodes(ref) }.getOrNull()
            }
            _bundle.value?.let { bundle ->
                if (_audio.value !in bundle.audios) _audio.value = bundle.audios.firstOrNull() ?: Audio.SUB
            }

            // The rest of the page, side by side: none of it holds the episodes up.
            launch {
                val found = withContext(Dispatchers.IO) { runCatching { catalog.seasons(animeId) } }
                if (animeId == askedId) _seasons.value = found.getOrNull() ?: Seasons(emptyList(), animeId)
            }
            launch {
                val found = withContext(Dispatchers.IO) { runCatching { catalog.relations(animeId) } }
                if (animeId == askedId) _relations.value = found.getOrNull() ?: emptyList()
            }
            launch {
                val found = withContext(Dispatchers.IO) { runCatching { catalog.recommendations(animeId) } }
                if (animeId == askedId) _recommendations.value = found.getOrNull() ?: emptyList()
            }
        }
    }

    /** Asks for the page again (the error's own button). */
    fun refresh() = load(askedId, force = true)

    /** The identity a source needs to find this anime on its own site. */
    fun ref(): AnimeRef? {
        val anime = _anime.value ?: return null
        val guide = _bundle.value
        return anime.toRef().withGuide(
            malId = guide?.malId,
            titles = listOfNotNull(guide?.title?.takeIf { it.isNotBlank() }),
        )
    }

    /** The episodes of the bundle being shown, in the audio being shown, in order. */
    fun episodes(): List<Episode> {
        val bundle = _bundle.value ?: return emptyList()
        val wanted = bundle.episodes(_audio.value)
        // An anime with no dub of its own falls back to the subtitled list rather than to nothing.
        val list = wanted.ifEmpty { bundle.episodes(Audio.SUB) }
        return list.sortedBy { it.number }
    }

    /** The audios this source has (the SUB/DUB switch shows only what is there). */
    fun audios(): List<Audio> = _bundle.value?.audios ?: listOf(Audio.SUB)

    /** Another source's bundle, from the sheet. */
    fun selectBundle(bundle: EpisodeBundle) {
        _bundle.value = bundle
        if (_audio.value !in bundle.audios) _audio.value = bundle.audios.firstOrNull() ?: Audio.SUB
        _notice.value = "The episodes of ${bundle.source}"
    }

    /** Subtitled or dubbed. */
    fun setAudio(audio: Audio) {
        _audio.value = audio
    }

    // --- what the user does with this anime -------------------------------------------------

    /** The heart, on or off (and on AniList too, signed in). */
    fun toggleFavourite() {
        val anime = _anime.value ?: return
        viewModelScope.launch {
            val on = library.toggleFavourite(anime.id, anime.title.display, anime.cover)
            readLibrary(anime.id)
            _notice.value = if (on) "A favourite now" else "The heart is off"
            if (on) account.syncSoon()
        }
    }

    /** A list to be on. */
    fun setStatus(status: ListStatus) {
        val anime = _anime.value ?: return
        viewModelScope.launch {
            val current = library.entryOf(anime.id)
            val entry = (current ?: LibraryEntry(
                animeId = anime.id,
                title = anime.title.display,
                cover = anime.cover,
                totalEpisodes = anime.episodes,
            )).copy(status = status, totalEpisodes = current?.totalEpisodes ?: anime.episodes)
            library.save(entry)
            readLibrary(anime.id)
            _notice.value = "On your ${status.label.lowercase()} list"
            account.syncSoon()
        }
    }

    /** Stars, 0 to 5 (0 takes them off). Rating an anime puts it on the completed list. */
    fun setScore(stars: Int) {
        val anime = _anime.value ?: return
        viewModelScope.launch {
            val current = library.entryOf(anime.id)
            if (stars > 0 && current == null) {
                library.save(
                    LibraryEntry(
                        animeId = anime.id,
                        title = anime.title.display,
                        cover = anime.cover,
                        status = ListStatus.COMPLETED,
                        score = stars.coerceIn(0, 5),
                        totalEpisodes = anime.episodes,
                        progress = anime.episodes ?: 0,
                    )
                )
            } else {
                library.setScore(anime.id, stars, anime.title.display, anime.cover)
            }
            readLibrary(anime.id)
            _notice.value = if (stars == 0) "Not rated any more" else "$stars of 5"
            account.syncSoon()
        }
    }

    /** Off every list. */
    fun removeFromLibrary() {
        val anime = _anime.value ?: return
        viewModelScope.launch {
            library.remove(anime.id)
            readLibrary(anime.id)
            _notice.value = "Off your library"
            account.syncSoon()
        }
    }

    /**
     * Takes one episode down: the streams are asked for first, since a download is a stream and its
     * headers, and the best one the source gave is written to the phone.
     */
    fun download(episode: Episode) {
        val anime = _anime.value ?: return
        val ref = ref() ?: return
        val audio = _audio.value
        viewModelScope.launch {
            if (downloads.isDownloaded(anime.id, audio, episode.number)) {
                _notice.value = "Episode ${episode.numberLabel} is on the phone already"
                return@launch
            }
            _notice.value = "Asking ${episode.source.ifBlank { _bundle.value?.source ?: "the sources" }}…"
            val watch = withContext(Dispatchers.IO) {
                runCatching { resolver.watch(ref, audio, episode.number, episode.id) }.getOrNull()
            }
            if (watch == null || watch.best == null) {
                _notice.value = "No stream to take down for episode ${episode.numberLabel}"
                return@launch
            }
            val item = downloads.download(anime = anime, episode = episode, watch = watch, audio = audio)
            _notice.value = if (item == null) "The download did not start"
            else "Episode ${episode.numberLabel} is coming down"
        }
    }

    /** The next [count] episodes, taken down one after another (the page's own button). */
    fun downloadNext(count: Int = 5) {
        val episodes = episodes()
        if (episodes.isEmpty()) {
            _notice.value = "No episodes to take down"
            return
        }
        val watched = _entry.value?.progress ?: 0
        val wanted = episodes.filter { it.number > watched }.take(count).ifEmpty { episodes.take(count) }
        _notice.value = "${wanted.size} episodes are coming down"
        wanted.forEach { episode -> download(episode) }
    }

    /** The episodes of this anime that are on the phone. */
    fun downloaded(): List<app.kuroka.api.DownloadItem> {
        val anime = _anime.value ?: return emptyList()
        return downloads.itemsOf(anime.id)
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Reads what the library has of this anime, so the page shows it as it is. */
    private fun readLibrary(animeId: Int) {
        _entry.value = library.entryOf(animeId)
        _favourite.value = library.favouriteOf(animeId)
        _progress.value = library.progressOf(animeId)
    }

    override fun onCleared() {
        loadJob?.cancel()
    }
}
