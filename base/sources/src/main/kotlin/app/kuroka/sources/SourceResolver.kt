package app.kuroka.sources

import app.kuroka.api.AnimeRef
import app.kuroka.api.AnimeSource
import app.kuroka.api.Audio
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.EpisodeResolver
import app.kuroka.api.SourceState
import app.kuroka.api.WatchResult
import app.kuroka.sources.providers.AnimeDunyaSource
import app.kuroka.sources.providers.AnimeOnsenSource
import app.kuroka.sources.providers.KitsuneServerSource
import app.kuroka.sources.providers.ProviderSupport
import app.kuroka.sources.providers.SenshiSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * The resolver: the sources in the user's order, asked one after another until one answers, with
 * a source that keeps failing left aside for a while so a page still loads. This is Orpheon's
 * source order (GPL-3.0, https://github.com/kota-zo/Orpheon) and Kitsune's provider strategy
 * (MIT, https://github.com/Dovakiin0/Kitsune) in one: the first source that has the anime wins,
 * and the episode it came from is asked back for the streams.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** The sources Kuroka ships, in the order a fresh install asks them. */
object Sources {
    /**
     * @param kitsuneUrl the address of the user's own Kitsune server, empty when there is none.
     */
    fun builtIns(kitsuneUrl: () -> String? = { null }): List<AnimeSource> = listOf(
        SenshiSource(),
        AnimeOnsenSource(),
        AnimeDunyaSource(),
        KitsuneServerSource(kitsuneUrl),
    )
}

class SourceResolver(
    private val all: List<AnimeSource>,
    /** The order the user put them in, and the ones they turned off (Settings, Sources). */
    private val order: () -> List<String> = { emptyList() },
    private val disabled: () -> Set<String> = { emptySet() },
    /** How long one source may take before the next one is asked. */
    private val timeoutMs: Long = 25_000L,
) : EpisodeResolver {

    /** How many failures in a row put a source aside, and for how long. */
    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val downUntil = ConcurrentHashMap<String, Long>()

    private val _sources = MutableStateFlow(states())
    override val sources: StateFlow<List<SourceState>> get() = _sources

    private fun states(): List<SourceState> {
        val wanted = order()
        val off = disabled()
        val now = System.currentTimeMillis()
        return all.map { s ->
            SourceState(
                id = s.id,
                name = s.name,
                enabled = s.id !in off && (s.enabledByDefault || s.id in wanted),
                hasDub = s.hasDub,
                languages = s.subtitleLanguages,
                order = wanted.indexOf(s.id).let { if (it < 0) Int.MAX_VALUE else it },
                down = (downUntil[s.id] ?: 0L) > now,
                note = if (s.isReady) s.note else s.note + " (needs its address in Settings, Sources)",
            )
        }.sortedBy { it.order }
    }

    /** Reads the settings again (the UI calls it when a source is turned on or moved). */
    fun refresh() {
        _sources.value = states()
    }

    /** Whether the user wants this source asked: not turned off, and able to answer. */
    private fun enabled(s: AnimeSource): Boolean = s.id !in disabled() && s.isReady

    private fun isDown(id: String): Boolean = (downUntil[id] ?: 0L) > System.currentTimeMillis()

    private fun asked(): List<AnimeSource> {
        val wanted = order()
        return all.filter { enabled(it) && !isDown(it.id) }
            .sortedBy { wanted.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    private fun fail(id: String) {
        val count = failures.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        if (count >= 3) {
            downUntil[id] = System.currentTimeMillis() + DOWN_FOR
            failures[id]?.set(0)
        }
        refresh()
    }

    private fun ok(id: String) {
        if ((failures[id]?.get() ?: 0) > 0) failures[id]?.set(0)
        if (downUntil.remove(id) != null) refresh()
    }

    override suspend fun episodes(anime: AnimeRef): EpisodeBundle? =
        Memo.get("bundle:${anime.id}", Memo.HOUR) { episodesNow(anime) }

    private suspend fun episodesNow(anime: AnimeRef): EpisodeBundle? {
        val ref = ProviderSupport.enriched(anime)
        for (source in asked()) {
            val bundle = withTimeoutOrNull(timeoutMs) {
                runCatching { source.episodes(ref) }.getOrNull()
            }
            if (bundle == null) {
                fail(source.id)
                continue
            }
            if (bundle.isEmpty) continue
            ok(source.id)
            return bundle
        }
        return null
    }

    override suspend fun watch(
        anime: AnimeRef,
        audio: Audio,
        episode: Double,
        episodeId: String?,
        preferServer: String?,
    ): WatchResult? {
        val ref = ProviderSupport.enriched(anime)
        // The episode's own id names the source it came from: that one is asked first, so the
        // streams are of the same site the list was (an episode number means different things
        // from one site to another).
        val fromId = episodeId?.split('/')?.getOrNull(1)
        val ordered = asked().let { list ->
            if (fromId == null) list
            else list.sortedByDescending { it.id == fromId }
        }
        var lastError: String? = null
        for (source in ordered) {
            if (audio == Audio.DUB && !source.hasDub) continue
            val result = withTimeoutOrNull(timeoutMs) {
                runCatching { source.watch(ref, audio, episode, episodeId) }
                    .onFailure { lastError = it.message }
                    .getOrNull()
            }
            if (result == null || result.streams.isEmpty()) {
                if (result == null) fail(source.id)
                continue
            }
            ok(source.id)
            // The server the user picked by hand, or the one the settings remember, first.
            val wanted = preferServer ?: result.streams.firstOrNull { it.server == rememberedServer }?.server
            val streams = if (wanted == null) result.streams
            else result.streams.sortedByDescending { it.server.contains(wanted, ignoreCase = true) }
            return result.copy(streams = streams)
        }
        // The last thing a source said, for the player's "no streams" line.
        lastFailure = lastError
        return null
    }

    /** The server the player asked for last (Settings, Playback, "remember the server"). */
    @Volatile
    var rememberedServer: String? = null

    /** What the last source that was asked said when it had nothing (shown by the player). */
    @Volatile
    var lastFailure: String? = null
        private set

    override suspend fun episodesEverywhere(anime: AnimeRef): List<EpisodeBundle> = coroutineScope {
        val ref = ProviderSupport.enriched(anime)
        asked().map { source ->
            async {
                withTimeoutOrNull(timeoutMs) { runCatching { source.episodes(ref) }.getOrNull() }
                    ?.takeIf { !it.isEmpty }
            }
        }.map { it.await() }.filterNotNull()
    }

    override fun reorder(ids: List<String>) {
        // The order itself lives in the settings; the resolver only shows it.
        refresh()
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        refresh()
    }

    override fun reset(id: String) {
        failures[id]?.set(0)
        downUntil.remove(id)
        refresh()
    }

    /** Forgets the episode lists kept for an anime (a new episode has aired). */
    fun forget(animeId: Int) {
        Memo.forget("bundle:$animeId")
    }

    private companion object {
        /** How long a source that failed three times in a row is left aside. */
        const val DOWN_FOR = 5 * 60 * 1000L
    }
}
