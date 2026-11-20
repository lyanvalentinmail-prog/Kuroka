package app.kuroka.sources.providers

import app.kuroka.api.AnimeRef
import app.kuroka.api.Audio
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeMeta
import app.kuroka.sources.guide.AniZip
import app.kuroka.sources.guide.AnimeGuide
import app.kuroka.sources.util.buildTitles
import app.kuroka.sources.util.episodeNumber
import app.kuroka.sources.util.searchQueries
import app.kuroka.sources.util.titleScore

/*
 * What the providers share: the ids a site searches by, the episode guide that fills in what a
 * site does not say (a title, a thumbnail, whether an episode is a filler), the watch ids the
 * player gives back, and the headers a stream wants.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object ProviderSupport {

    /** The episode guide of an anime, asked once and kept for a day (its titles, MAL id, episodes). */
    suspend fun guide(ref: AnimeRef): AnimeGuide? = AniZip.guide(ref.id)

    /** The MAL id a site searches by: AniList's own, else the guide's. */
    suspend fun malId(ref: AnimeRef): Int? = ref.idMal ?: guide(ref)?.malId

    /** The identity with the guide folded in: the MAL id, the other titles, the episode metadata. */
    suspend fun enriched(ref: AnimeRef): AnimeRef {
        val guide = guide(ref) ?: return ref
        val meta = guide.toMeta().ifEmpty { ref.episodeMeta }
        return ref.withGuide(malId = guide.malId, titles = guide.titles, meta = meta)
    }

    /** The titles to search a site with, the guide's included. */
    suspend fun titles(ref: AnimeRef): List<String> =
        buildTitles(ref.title, ref.synonyms, guide(ref)?.titles ?: emptyList())

    /** The searches to try, shortest first. */
    suspend fun queries(ref: AnimeRef, limit: Int = 8): List<String> = searchQueries(titles(ref), limit)

    /** "12", or "13.5" for a half episode: the number a watch id carries. */
    fun numberLabel(number: Double): String =
        if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

    /**
     * The watch id of an episode, in the shape Kitsune's own providers use
     * (`watch/senshi/21/sub/senshi-12`), so a Kitsune server answers the same ids.
     */
    fun episodeId(source: String, animeId: Int, audio: Audio, number: Double): String =
        "watch/$source/$animeId/${audio.code}/$source-${numberLabel(number)}"

    /** The anime id, audio and episode number a watch id carries. */
    fun parseEpisodeId(id: String): Triple<Int, Audio, Double>? {
        val parts = id.split('/')
        if (parts.size < 5 || parts[0] != "watch") return null
        val animeId = parts[2].toIntOrNull() ?: return null
        val audio = Audio.of(parts[3])
        val tail = parts.last().substringAfterLast('-')
        val number = episodeNumber(tail) ?: return null
        return Triple(animeId, audio, number)
    }

    /** One episode of a list, with what the guide knows of that number folded in. */
    fun enrich(episode: Episode, ref: AnimeRef, guide: AnimeGuide? = null): Episode {
        val meta: EpisodeMeta? = guide?.meta(episode.number)?.let { g ->
            EpisodeMeta(
                number = g.number, title = g.title, description = g.overview, image = g.image,
                airDate = g.airdate, duration = g.runtimeMinutes?.let { it * 60 },
                filler = g.filler, recap = g.recap,
            )
        } ?: ref.meta(episode.number)
        if (meta == null) return episode
        return episode.copy(
            title = episode.title ?: meta.title,
            description = episode.description ?: meta.description,
            image = episode.image ?: meta.image,
            airDate = episode.airDate ?: meta.airDate,
            duration = episode.duration ?: meta.duration,
            filler = episode.filler || meta.filler,
            recap = episode.recap || meta.recap,
        )
    }

    /** A list of episodes, sorted and enriched, without the duplicates a site sometimes sends. */
    fun list(episodes: List<Episode>, ref: AnimeRef, guide: AnimeGuide? = null): List<Episode> =
        episodes.distinctBy { it.number }.sortedBy { it.number }.map { enrich(it, ref, guide) }

    /**
     * The best candidate of a site's own search: the one whose titles match the anime's, with a
     * modifier ("Movie", "Season 2") the target does not have scored down, as Kitsune does.
     */
    fun bestCandidate(ref: AnimeRef, candidates: List<Pair<String, String?>>, slugOf: (Pair<String, String?>) -> String? = { null }): Pair<String, String?>? {
        if (candidates.isEmpty()) return null
        return candidates.map { it to titleScore(ref, it.first, it.second, slugOf(it)) }
            .sortedByDescending { it.second }
            .firstOrNull { it.second >= 250 }?.first
    }

    /** The headers a stream and its subtitles want: a browser's, with the site as the referer. */
    fun streamHeaders(referer: String?, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = app.kuroka.sources.net.Http.UA
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        h["Accept"] = "*/*"
        h["Accept-Language"] = "en-US,en;q=0.9"
        h.putAll(extra)
        return h
    }
}
