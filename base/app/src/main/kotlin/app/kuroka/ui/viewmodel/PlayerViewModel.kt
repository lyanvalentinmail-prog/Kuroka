package app.kuroka.ui.viewmodel

import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.Anime
import app.kuroka.api.AnimeRef
import app.kuroka.api.Audio
import app.kuroka.api.Catalog
import app.kuroka.api.DownloadState
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.EpisodeResolver
import app.kuroka.api.ListStatus
import app.kuroka.api.SkipTimes
import app.kuroka.api.StreamSource
import app.kuroka.api.StreamType
import app.kuroka.api.SubtitleTrack
import app.kuroka.api.WatchProgress
import app.kuroka.api.WatchResult
import app.kuroka.di.Graph
import app.kuroka.di.NowEpisode
import app.kuroka.di.NowPlaying
import app.kuroka.host.account.AccountRepo
import app.kuroka.host.cast.CastController
import app.kuroka.host.cast.CastItem
import app.kuroka.host.cast.CastSubtitle
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.playback.PlaybackItem
import app.kuroka.host.playback.PlayerHost
import app.kuroka.host.playback.PlayerSnapshot
import app.kuroka.host.playback.TrackInfo
import app.kuroka.host.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The player's head: it finds the streams of one episode, puts the best of them on the player,
 * tries the next one when a server refuses, keeps the subtitles and the skip times with it, saves
 * how far the episode got, goes on to the next one when this one ends, and hands the whole thing
 * to a Chromecast when the user asks.
 *
 * The approach is Kitsune's (MIT, © Dovakiin0, https://github.com/Dovakiin0/Kitsune): a list of
 * candidate streams per episode, walked in the order the provider ranks them, with the audio the
 * user picked and the server they picked by hand kept for the next episode.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** Where the player is with the episode it was given. */
enum class PlayerPhase { IDLE, FINDING, READY, CASTING, ERROR }

/** What the player screen draws, as one value. */
data class PlayerUi(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val anime: Anime? = null,
    val bundle: EpisodeBundle? = null,
    val episode: Episode? = null,
    val number: Double = 1.0,
    val audio: Audio = Audio.SUB,
    val watch: WatchResult? = null,
    /** The streams of this episode, best first. */
    val streams: List<StreamSource> = emptyList(),
    val streamIndex: Int = -1,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val skip: SkipTimes = SkipTimes.None,
    /** The episode is inside its intro or its outro right now (the skip button shows). */
    val inIntro: Boolean = false,
    val inOutro: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** The episode is on the phone, so it plays with no network at all. */
    val offline: Boolean = false,
    val episodes: List<Episode> = emptyList(),
    val nextNumber: Double? = null,
    val previousNumber: Double? = null,
    val casting: Boolean = false,
) {
    val stream: StreamSource? get() = streams.getOrNull(streamIndex)
    val servers: List<String> get() = streams.map { it.server }.distinct().filter { it.isNotBlank() }
    val title: String get() = anime?.title?.display ?: "Kuroka"
    val episodeLabel: String
        get() = episode?.label()?.takeIf { it.isNotBlank() }
            ?: "Episode ${if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()}"
}

class PlayerViewModel(
    private val catalog: Catalog = Graph.catalog,
    private val resolver: EpisodeResolver = Graph.resolver,
    private val library: LibraryStore = Graph.library,
    private val account: AccountRepo = Graph.account,
    private val downloads: DownloadRepo = Graph.downloads,
    private val prefs: Prefs = Graph.prefs,
    private val cast: CastController = Graph.cast,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUi())
    /** What the player screen draws. */
    val ui: StateFlow<PlayerUi> get() = _ui

    /** What the player itself is doing (position, tracks, cues). */
    val snapshot: StateFlow<PlayerSnapshot> get() = PlayerHost.snapshot

    /** The Cast state, so the screen can drop its picture and show the TV's. */
    val castState: StateFlow<app.kuroka.host.cast.CastState> get() = cast.state

    private var ref: AnimeRef? = null
    private var progressJob: Job? = null
    private var skipJob: Job? = null
    private var watched = false
    private var askedEpisodeId: String? = null
    private var askedServer: String? = null

    init {
        PlayerHost.onEnded = { onEnded() }
        PlayerHost.onError = { why -> onPlayerError(why) }
        PlayerHost.onVideoSize = { w, h -> onPicture(w, h) }
        viewModelScope.launch {
            PlayerHost.subtitleOffsetMs = (prefs.now.player.subtitles.offsetSeconds * 1000).toLong()
            PlayerHost.useEmbeddedStyles = prefs.now.player.subtitles.useEmbeddedStyles
        }
        viewModelScope.launch {
            prefs.settings.collect { s ->
                PlayerHost.subtitleOffsetMs = (s.player.subtitles.offsetSeconds * 1000).toLong()
                PlayerHost.useEmbeddedStyles = s.player.subtitles.useEmbeddedStyles
            }
        }
        viewModelScope.launch {
            cast.state.collect { state ->
                _ui.value = _ui.value.copy(casting = state.casting)
                if (state.phase == app.kuroka.host.cast.CastPhase.STOPPED ||
                    state.phase == app.kuroka.host.cast.CastPhase.ERROR
                ) {
                    // The TV stopped: the phone takes the episode back where the TV left it.
                    if (state.positionMs > 0) PlayerHost.seekTo(state.positionMs)
                    PlayerHost.play()
                }
            }
        }
    }

    /** The picture's shape, for the activity's picture-in-picture window. */
    private var onPicture: (Int, Int) -> Unit = { _, _ -> }
    fun onPictureSize(handler: (Int, Int) -> Unit) {
        onPicture = handler
    }

    /**
     * Starts one episode. [episodeId] and [server] come from the screen that asked (an episode row
     * knows its own id, and a server the user picked by hand is asked for again).
     */
    fun start(
        animeId: Int,
        number: Double,
        audio: Audio,
        episodeId: String? = null,
        server: String? = null,
        source: String? = null,
    ) {
        val current = _ui.value
        if (current.number == number && current.audio == audio &&
            current.phase != PlayerPhase.IDLE && current.phase != PlayerPhase.ERROR &&
            current.anime?.id == animeId
        ) return
        askedEpisodeId = episodeId
        askedServer = server ?: prefs.now.player.lastServer?.takeIf { prefs.now.player.rememberServer }
        _ui.value = PlayerUi(
            phase = PlayerPhase.FINDING, number = number, audio = audio,
            anime = current.anime?.takeIf { it.id == animeId },
            bundle = current.bundle?.takeIf { it.animeId == animeId },
            episodes = current.episodes.takeIf { current.anime?.id == animeId } ?: emptyList(),
        )
        stopTracking()
        viewModelScope.launch {
            val anime = withContext(Dispatchers.IO) { runCatching { catalog.details(animeId) }.getOrNull() }
            if (anime == null) {
                _ui.value = _ui.value.copy(
                    phase = PlayerPhase.ERROR,
                    error = "The page of this anime did not load",
                )
                return@launch
            }
            ref = anime.toRef()
            // The episode list: the one the screen had, else the first source that answers.
            val bundle = _ui.value.bundle?.takeIf { it.animeId == animeId && !it.isEmpty }
                ?: withContext(Dispatchers.IO) { runCatching { resolver.episodes(anime.toRef()) }.getOrNull() }
                ?: withContext(Dispatchers.IO) {
                    runCatching { resolver.episodesEverywhere(anime.toRef()) }.getOrDefault(emptyList())
                        .firstOrNull { it.episodes(audio).isNotEmpty() }
                }
            val episodes = (bundle?.episodes(audio) ?: emptyList()).sortedBy { it.number }
            val episode = bundle?.episode(audio, number)
                ?: episodes.firstOrNull { it.number == number }
                ?: Episode(
                    id = episodeId ?: "",
                    number = number,
                    audio = audio,
                    source = source ?: bundle?.source ?: "",
                )
            _ui.value = _ui.value.copy(
                anime = anime, bundle = bundle, episode = episode, episodes = episodes,
                nextNumber = bundle?.next(number, audio)?.number ?: episodes.firstOrNull { it.number > number }?.number,
                previousNumber = bundle?.previous(number, audio)?.number ?: episodes.lastOrNull { it.number < number }?.number,
            )
            NowPlaying.set(
                NowEpisode(
                    animeId = anime.id,
                    title = anime.title.display,
                    cover = anime.cover,
                    banner = anime.banner,
                    number = number,
                    episodeTitle = episode.title,
                    audio = audio,
                    source = episode.source.ifBlank { bundle?.source ?: "" },
                    server = askedServer ?: "",
                    totalEpisodes = episodes.size,
                )
            )
            // An episode that is on the phone plays from the phone, whatever the network is doing.
            val offline = downloads.itemOf(anime.id, audio, number)?.takeIf { it.state == DownloadState.COMPLETED }
            if (offline != null) {
                playOffline(anime, episode, offline)
                return@launch
            }
            findStreams(anime, episode, number, audio, episodeId, askedServer)
        }
    }

    /** Plays a download: its own playlist, its headers and the subtitles taken down with it. */
    private fun playOffline(anime: Anime, episode: Episode, item: app.kuroka.api.DownloadItem) {
        _ui.value = _ui.value.copy(
            phase = PlayerPhase.READY,
            offline = true,
            streams = listOf(
                StreamSource(
                    url = item.uri, type = StreamType.HLS, server = item.server.ifBlank { "Download" },
                    headers = item.headers, subtitles = item.subtitles,
                )
            ),
            streamIndex = 0,
            subtitles = item.subtitles,
        )
        put(
            url = item.uri,
            headers = item.headers,
            subtitles = item.subtitles,
            anime = anime,
            episode = episode,
        )
    }

    /** Asks the sources for the streams of one episode and puts the best of them on the player. */
    private suspend fun findStreams(
        anime: Anime,
        episode: Episode,
        number: Double,
        audio: Audio,
        episodeId: String?,
        server: String?,
    ) {
        val reference = ref ?: anime.toRef()
        _ui.value = _ui.value.copy(phase = PlayerPhase.FINDING, error = null, offline = false)
        val watch = withContext(Dispatchers.IO) {
            runCatching { resolver.watch(reference, audio, number, episodeId, server) }.getOrNull()
        }
        if (watch == null || watch.streams.isEmpty()) {
            _ui.value = _ui.value.copy(
                phase = PlayerPhase.ERROR,
                error = watch?.let { "The sources gave no stream for this episode" }
                    ?: "No source answered for this episode",
            )
            return
        }
        // The provider ranks its streams; a server the user picked by hand wins over that order.
        val ordered = watch.streams.sortedByDescending { it.priority }
        val wanted = server?.let { asked -> ordered.indexOfFirst { it.server == asked } }?.takeIf { it >= 0 } ?: 0
        val subtitles = watch.allSubtitles
        _ui.value = _ui.value.copy(
            phase = PlayerPhase.READY,
            watch = watch,
            streams = ordered,
            streamIndex = wanted,
            subtitles = subtitles,
            skip = watch.skip,
        )
        if (watch.skip.hasAny) startSkipWatch()
        put(
            url = ordered[wanted].url,
            headers = headersOf(ordered[wanted], watch),
            subtitles = subtitles,
            anime = anime,
            episode = episode,
            server = ordered[wanted].server,
        )
    }

    /** A stream's own headers, with the referer and the episode's headers under them. */
    private fun headersOf(stream: StreamSource, watch: WatchResult): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out += watch.headers
        stream.referer?.let { out["Referer"] = it }
        out += stream.headers
        return out
    }

    /** Puts a stream on the player, at the position the last watch left. */
    private fun put(
        url: String,
        headers: Map<String, String>,
        subtitles: List<SubtitleTrack>,
        anime: Anime,
        episode: Episode,
        server: String? = null,
    ) {
        val settings = prefs.now.player
        val wanted = preferredSubtitles(subtitles)
        val start = library.progressOf(anime.id)
            ?.takeIf { it.episodeNumber == episode.number && settings.resume && it.positionMs > 30_000L }
            ?.positionMs ?: 0L
        PlayerHost.load(
            PlaybackItem(
                uri = url,
                headers = headers,
                subtitles = if (wanted != null) listOf(wanted) else emptyList(),
                title = anime.title.display,
                episode = episode.label(),
                artwork = episode.image ?: anime.cover,
                mediaId = "${anime.id}:${episode.number}:${episode.audio.code}",
                startPositionMs = start,
            )
        )
        // The subtitle language the settings ask for, and the audio language, by themselves.
        settings.subtitles.preferredLanguage.takeIf { it.isNotBlank() }?.let { PlayerHost.preferTextLanguage(it) }
        if (wanted != null) PlayerHost.selectText(snapshotTrackOf(wanted))
        _ui.value = _ui.value.copy(
            phase = PlayerPhase.READY,
            notice = server?.let { "Playing from $it" },
        )
        if (!server.isNullOrBlank()) prefs.rememberServer(server)
        startTracking(anime, episode)
    }

    /** The subtitle track the settings prefer, else the one the provider marks as the default. */
    private fun preferredSubtitles(subtitles: List<SubtitleTrack>): SubtitleTrack? {
        if (subtitles.isEmpty()) return null
        val wanted = prefs.now.player.subtitles.preferredLanguage
        if (wanted.isNotBlank()) {
            subtitles.firstOrNull { it.language.equals(wanted, true) || it.label.equals(wanted, true) }
                ?.let { return it }
        }
        return subtitles.firstOrNull { it.isDefault } ?: subtitles.first()
    }

    /** The player's own track for a subtitle Kuroka side-loaded (the first text track). */
    private fun snapshotTrackOf(track: SubtitleTrack): TrackInfo? =
        PlayerHost.snapshot.value.textTracks.firstOrNull {
            it.label.equals(track.name, true) || it.language.equals(track.language, true)
        }

    /** The next stream of the list, when a server refuses or gives nothing (Kitsune's fallback). */
    fun nextStream(): Boolean {
        val ui = _ui.value
        val next = ui.streamIndex + 1
        if (next >= ui.streams.size) return false
        val stream = ui.streams[next]
        val anime = ui.anime ?: return false
        val episode = ui.episode ?: return false
        _ui.value = ui.copy(streamIndex = next, phase = PlayerPhase.READY, error = null,
            notice = "Trying ${stream.server.ifBlank { "another server" }}")
        put(
            url = stream.url,
            headers = headersOf(stream, ui.watch ?: WatchResult(animeId = anime.id)),
            subtitles = ui.subtitles,
            anime = anime,
            episode = episode,
            server = stream.server,
        )
        return true
    }

    /** A server the user picked in the sheet. */
    fun selectServer(name: String) {
        val ui = _ui.value
        val at = ui.streams.indexOfFirst { it.server == name }
        if (at < 0) return
        val anime = ui.anime ?: return
        val episode = ui.episode ?: return
        val stream = ui.streams[at]
        _ui.value = ui.copy(streamIndex = at, notice = "Playing from $name")
        prefs.rememberServer(name)
        put(stream.url, headersOf(stream, ui.watch ?: WatchResult(animeId = anime.id)), ui.subtitles, anime, episode, name)
    }

    /** A subtitle of the list, or null to turn them off. */
    fun selectSubtitle(track: SubtitleTrack?) {
        if (track == null) {
            PlayerHost.selectText(null)
            return
        }
        val ui = _ui.value
        val anime = ui.anime ?: return
        val episode = ui.episode ?: return
        val stream = ui.stream
        // A side-loaded file needs the media item to carry it, so the episode is put on again where
        // it was; a track the stream itself has is only selected.
        val own = snapshotTrackOf(track)
        if (own != null) {
            PlayerHost.selectText(own)
            return
        }
        val position = PlayerHost.positionMs
        PlayerHost.load(
            PlaybackItem(
                uri = stream?.url ?: return,
                headers = stream?.let { headersOf(it, ui.watch ?: WatchResult(animeId = anime.id)) } ?: emptyMap(),
                subtitles = listOf(track),
                title = anime.title.display,
                episode = episode.label(),
                artwork = episode.image ?: anime.cover,
                startPositionMs = position,
            )
        )
        viewModelScope.launch {
            var waited = 0
            while (waited < 4000 && PlayerHost.snapshot.value.textTracks.isEmpty()) {
                delay(150); waited += 150
            }
            PlayerHost.selectText(PlayerHost.snapshot.value.textTracks.firstOrNull())
        }
    }

    fun selectAudioTrack(track: TrackInfo) = PlayerHost.selectAudio(track)
    fun selectQuality(track: TrackInfo?) = PlayerHost.selectQuality(track)
    fun setSpeed(speed: Float) {
        PlayerHost.setSpeed(speed)
        prefs.player { it.copy(speed = speed) }
    }

    /** Another stream of the same episode is there to fall back on. */
    fun hasNextStream(): Boolean = _ui.value.streamIndex + 1 < _ui.value.streams.size

    /** Asks for the same episode again, from the start (a stream that would not play). */
    fun refresh() {
        val ui = _ui.value
        val anime = ui.anime ?: return
        start(
            anime.id,
            ui.number,
            ui.audio,
            ui.episode?.id ?: askedEpisodeId,
            ui.stream?.server ?: askedServer,
            ui.episode?.source,
        )
    }

    /** The subtitles' offset, kept for every episode (Settings, Subtitles). */
    fun setSubtitleOffset(seconds: Double) {
        prefs.subtitles { it.copy(offsetSeconds = seconds.coerceIn(-10.0, 10.0)) }
        PlayerHost.subtitleOffsetMs = (seconds * 1000).toLong()
    }

    /** Takes the notice away once the user has read it. */
    fun clearNotice() {
        if (_ui.value.notice != null) _ui.value = _ui.value.copy(notice = null)
    }

    fun playPause() = PlayerHost.playPause()
    fun seekTo(ms: Long) = PlayerHost.seekTo(ms)
    fun seekBy(ms: Long) = PlayerHost.seekBy(ms)
    fun doubleTapSeek(forward: Boolean) {
        val seconds = prefs.now.player.doubleTapSeekSeconds * 1000L
        PlayerHost.seekBy(if (forward) seconds else -seconds)
    }

    /** The picture a screen draws on. */
    fun attach(view: SurfaceView) = PlayerHost.attach(view)
    fun detach(view: SurfaceView) = PlayerHost.detach(view)

    /** Jumps over the intro, or over the outro (which starts the next episode). */
    fun skipIntro() {
        val skip = _ui.value.skip
        if (skip.hasIntro) PlayerHost.seekTo((skip.introEnd * 1000).toLong())
    }

    fun skipOutro() {
        val skip = _ui.value.skip
        if (skip.hasOutro) PlayerHost.seekTo((skip.outroEnd * 1000).toLong())
        else next()
    }

    /** The next episode, when the list has one. */
    fun next() {
        val ui = _ui.value
        val number = ui.nextNumber ?: return
        val anime = ui.anime ?: return
        start(anime.id, number, ui.audio, ui.bundle?.episode(ui.audio, number)?.id, ui.stream?.server, ui.episode?.source)
    }

    fun previous() {
        val ui = _ui.value
        val number = ui.previousNumber ?: return
        val anime = ui.anime ?: return
        start(anime.id, number, ui.audio, ui.bundle?.episode(ui.audio, number)?.id, ui.stream?.server, ui.episode?.source)
    }

    /** Sub or dub, without leaving the episode. */
    fun setAudio(audio: Audio) {
        val ui = _ui.value
        val anime = ui.anime ?: return
        start(anime.id, ui.number, audio, ui.bundle?.episode(audio, ui.number)?.id, ui.stream?.server, ui.episode?.source)
    }

    /** A source's own episode list, for the sheet that switches sources. */
    fun selectBundle(bundle: EpisodeBundle) {
        val ui = _ui.value
        val anime = ui.anime ?: return
        val episodes = bundle.episodes(ui.audio).sortedBy { it.number }
        _ui.value = ui.copy(
            bundle = bundle, episodes = episodes,
            nextNumber = bundle.next(ui.number, ui.audio)?.number,
            previousNumber = bundle.previous(ui.number, ui.audio)?.number,
        )
        start(anime.id, ui.number, ui.audio, bundle.episode(ui.audio, ui.number)?.id, null, bundle.source)
    }

    /** Takes this episode down to the phone. */
    fun download() {
        val ui = _ui.value
        val anime = ui.anime ?: return
        val episode = ui.episode ?: return
        val watch = ui.watch
        viewModelScope.launch {
            if (watch == null) {
                _ui.value = _ui.value.copy(notice = "There is no stream to take down")
                return@launch
            }
            val started = downloads.download(anime, episode, watch, ui.stream, ui.audio)
            _ui.value = _ui.value.copy(
                notice = if (started != null) "Taking episode ${episode.numberLabel} down"
                else "The download did not start (Settings, Downloads says why)"
            )
        }
    }

    /** Puts the episode on a Chromecast; the phone's own player pauses. */
    fun castTo(device: app.kuroka.host.cast.CastDevice) {
        val ui = _ui.value
        val stream = ui.stream ?: return
        val anime = ui.anime ?: return
        val episode = ui.episode ?: return
        PlayerHost.pause()
        cast.cast(
            device,
            CastItem(
                url = stream.url,
                headers = headersOf(stream, ui.watch ?: WatchResult(animeId = anime.id)),
                title = anime.title.display,
                subtitle = episode.label(),
                poster = episode.image ?: anime.cover,
                subtitles = ui.subtitles.map { CastSubtitle(it.url, it.name, it.language, it.headers) },
                startPositionMs = PlayerHost.positionMs,
                durationMs = PlayerHost.durationMs,
            ),
        )
        _ui.value = ui.copy(phase = PlayerPhase.CASTING)
    }

    /** Takes the episode back from the TV. */
    fun stopCasting() {
        val position = cast.state.value.positionMs.takeIf { it > 0 } ?: PlayerHost.positionMs
        cast.disconnect()
        PlayerHost.seekTo(position)
        PlayerHost.play()
        _ui.value = _ui.value.copy(phase = PlayerPhase.READY)
    }

    /** The episode ended: mark it watched, save it, and go on when the settings say so. */
    private fun onEnded() {
        val ui = _ui.value
        val anime = ui.anime ?: return
        val episode = ui.episode ?: return
        watched = true
        viewModelScope.launch {
            markWatched(anime, episode, complete = true)
            if (prefs.now.player.autoPlayNext && ui.nextNumber != null && !ui.casting) {
                delay(1200)
                next()
            }
        }
    }

    /** A stream failed: try the next server, and say so when there is none left. */
    private fun onPlayerError(why: String) {
        val tried = nextStream()
        _ui.value = _ui.value.copy(
            error = if (tried) null else why,
            phase = if (tried) PlayerPhase.READY else PlayerPhase.ERROR,
        )
    }

    /** Saves the position every ten seconds, and once more when the screen goes. */
    private fun startTracking(anime: Anime, episode: Episode) {
        stopTracking()
        watched = false
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val snapshot = PlayerHost.snapshot.value
                if (!snapshot.playing) continue
                val position = snapshot.positionMs
                val duration = snapshot.durationMs
                if (position < 10_000L || duration <= 0L) continue
                watched = true
                save(anime, episode, position, duration, complete = false)
                // Inside the intro or the outro: the skip button and the auto-skip want to know.
                val skip = _ui.value.skip
                val seconds = position / 1000.0
                _ui.value = _ui.value.copy(
                    inIntro = skip.hasIntro && seconds >= skip.introStart && seconds < skip.introEnd,
                    inOutro = skip.hasOutro && seconds >= skip.outroStart,
                )
                if (prefs.now.player.autoSkipIntro && _ui.value.inIntro) {
                    PlayerHost.seekTo((skip.introEnd * 1000).toLong())
                    _ui.value = _ui.value.copy(inIntro = false)
                }
                if (prefs.now.player.skipRecap && episode.recap) continue
            }
        }
        skipJob = viewModelScope.launch {
            // A finer tick for the skip button and the intro, which ten seconds is too coarse for.
            while (isActive) {
                delay(1000)
                val skip = _ui.value.skip
                if (!skip.hasAny) continue
                val seconds = PlayerHost.positionMs / 1000.0
                val inIntro = skip.hasIntro && seconds >= skip.introStart && seconds < skip.introEnd
                val inOutro = skip.hasOutro && seconds >= skip.outroStart
                if (inIntro != _ui.value.inIntro || inOutro != _ui.value.inOutro) {
                    _ui.value = _ui.value.copy(inIntro = inIntro, inOutro = inOutro)
                }
                if (prefs.now.player.autoSkipIntro && inIntro) {
                    PlayerHost.seekTo((skip.introEnd * 1000).toLong())
                }
            }
        }
    }

    private fun stopTracking() {
        progressJob?.cancel()
        progressJob = null
        skipJob?.cancel()
        skipJob = null
    }

    /** How far the episode got, on the phone and on the account. */
    private suspend fun save(anime: Anime, episode: Episode, position: Long, duration: Long, complete: Boolean) {
        library.saveProgress(
            WatchProgress(
                animeId = anime.id,
                title = anime.title.display,
                cover = anime.cover,
                episodeImage = episode.image,
                episodeNumber = episode.number,
                episodeTitle = episode.title,
                positionMs = if (complete) duration else position,
                durationMs = duration,
                source = episode.source,
                audio = episode.audio,
                completed = complete,
            )
        )
    }

    /** The episode counts as watched: the library's episodes go up and the account hears it. */
    private suspend fun markWatched(anime: Anime, episode: Episode, complete: Boolean) {
        val duration = PlayerHost.durationMs.takeIf { it > 0 } ?: (episode.duration?.times(60_000L) ?: 0L)
        save(anime, episode, if (complete) duration else PlayerHost.positionMs, duration, complete)
        val entry = library.entryOf(anime.id)
        val number = episode.number.toInt()
        if (entry == null) {
            library.setStatus(
                LibraryEntry(
                    animeId = anime.id, title = anime.title.display, cover = anime.cover,
                    totalEpisodes = anime.episodes, audio = episode.audio, progress = number,
                ),
                if (complete && anime.episodes != null && number >= anime.episodes) ListStatus.COMPLETED
                else ListStatus.WATCHING,
            )
        } else if (number > entry.progress) {
            library.setEpisodeProgress(anime.id, number)
        }
        account.saveProgress(anime.id, number, PlayerHost.positionMs)
        account.syncSoon()
    }

    /** The screen went away: save once more, and let go of the picture. */
    override fun onCleared() {
        val ui = _ui.value
        val anime = ui.anime
        val episode = ui.episode
        if (anime != null && episode != null && watched) {
            val position = PlayerHost.positionMs
            val duration = PlayerHost.durationMs
            viewModelScope.launch { save(anime, episode, position, duration, complete = false) }
        }
        stopTracking()
        PlayerHost.onEnded = null
        PlayerHost.onError = null
        PlayerHost.onVideoSize = null
        NowPlaying.clear()
        super.onCleared()
    }

    /** The activity went to the back: keep the position, and the audio only if asked for. */
    fun onBackground() {
        val ui = _ui.value
        val anime = ui.anime ?: return
        val episode = ui.episode ?: return
        viewModelScope.launch {
            save(anime, episode, PlayerHost.positionMs, PlayerHost.durationMs, complete = false)
        }
        if (!prefs.now.player.backgroundAudio) {
            PlayerHost.pause()
            PlayerHost.releaseVideo()
        }
    }

    /** The activity came back: the picture is attached again by the screen. */
    fun onForeground() {
        if (_ui.value.phase == PlayerPhase.ERROR || _ui.value.phase == PlayerPhase.IDLE) return
    }

    /** Nothing is playing any more (the mini player's close button). */
    fun stopPlayback() {
        stopTracking()
        PlayerHost.stop()
        NowPlaying.clear()
        _ui.value = PlayerUi()
    }
}
