package app.kuroka.sources.providers

import app.kuroka.api.AnimeRef
import app.kuroka.api.AnimeSource
import app.kuroka.api.Audio
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.SkipTimes
import app.kuroka.api.StreamSource
import app.kuroka.api.StreamType
import app.kuroka.api.SubtitleTrack
import app.kuroka.api.WatchResult
import app.kuroka.sources.Memo
import app.kuroka.sources.net.Http
import app.kuroka.sources.net.int
import app.kuroka.sources.net.text
import app.kuroka.sources.util.Embedded
import app.kuroka.sources.util.titleScore
import org.json.JSONObject
import java.net.URLDecoder

/*
 * Anime Onsen (animeonsen.xyz): a licensed streaming site with an API of its own. The home page
 * hands out a session (a cookie whose characters are each one off, and three meta tags with the
 * API's address and a search token); with it the search, the episode list and the video come as
 * JSON, and the video is a DASH manifest with its subtitle tracks. Subtitled only.
 *
 * Ported from Kitsune (MIT, © Dovakiin0, https://github.com/Dovakiin0/Kitsune,
 * src/lib/providers/providers/animeonsen.js).
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class AnimeOnsenSource : AnimeSource {

    override val id = "animeonsen"
    override val name = "Anime Onsen"
    override val note = "A licensed site's own API: subtitled DASH streams with their subtitles."
    override val hasDub = false
    override val subtitleLanguages = listOf("en", "es", "pt", "fr", "de", "it", "ru", "ar", "id", "ms", "vi", "th", "ko", "zh")

    private companion object {
        const val SITE = "https://www.animeonsen.xyz"
    }

    /** What the home page handed out: the API's token and addresses, for the life of the process. */
    private class Session(val token: String, val apiOrigin: String, val searchOrigin: String, val searchToken: String)

    @Volatile private var session: Session? = null

    private suspend fun session(force: Boolean = false): Session? {
        session?.let { if (!force) return it }
        val raw = Http.raw(SITE, Http.browserHeaders())
        if (!raw.isOk) return null
        val cookie = Embedded.cookieValue(raw.headers("Set-Cookie"), "ao.session") ?: return null
        val apiOrigin = Embedded.metaContent(raw.body, "ao-api-origin") ?: return null
        val searchOrigin = Embedded.metaContent(raw.body, "ao-search-origin") ?: return null
        val searchToken = Embedded.metaContent(raw.body, "ao-search-token") ?: return null
        val token = decodeToken(cookie) ?: return null
        return Session(token, origin(apiOrigin), origin(searchOrigin), searchToken).also { session = it }
    }

    /** The cookie's base64, decoded, with every character one further on: the site's own token. */
    private fun decodeToken(cookie: String): String? = runCatching {
        val decoded = String(java.util.Base64.getDecoder().decode(URLDecoder.decode(cookie, "UTF-8")), Charsets.UTF_8)
        val token = decoded.map { (it.code + 1).toChar() }.joinToString("")
        token.takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x20..0x7E } }
    }.getOrNull()

    private fun origin(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}" + (u.port.takeIf { it > 0 }?.let { ":$it" } ?: "")
    }.getOrDefault(url.trimEnd('/'))

    private suspend fun api(path: String, retry: Boolean = true): JSONObject? {
        val s = session() ?: return null
        val headers = mapOf(
            "Authorization" to "Bearer ${s.token}",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to SITE,
            "Referer" to "$SITE/",
            "User-Agent" to Http.UA,
        )
        val answer = runCatching { Http.json(s.apiOrigin + path, headers) }.getOrNull()
        if (answer == null && retry) {
            session = null
            return api(path, false)
        }
        return answer
    }

    private suspend fun search(query: String): List<JSONObject> {
        val s = session() ?: return emptyList()
        val body = JSONObject().put(
            "queries",
            org.json.JSONArray().put(JSONObject().put("indexUid", "content").put("q", query).put("limit", 20))
        )
        val headers = mapOf(
            "Authorization" to "Bearer ${s.searchToken}",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Origin" to SITE,
            "Referer" to "$SITE/",
            "User-Agent" to Http.UA,
        )
        val answer = runCatching { Http.json("${s.searchOrigin}/multi-search", headers, "POST", Http.jsonBody(body)) }.getOrNull()
            ?: return emptyList()
        val hits = answer.optJSONArray("results")?.optJSONObject(0)?.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<JSONObject>(hits.length())
        for (i in 0 until hits.length()) hits.optJSONObject(i)?.let { out += it }
        return out
    }

    /** What the site calls this anime: its content id, how many episodes it has, whether it is a movie. */
    private data class Series(val contentId: String, val title: String, val malId: Int?, val episodeCount: Int, val isMovie: Boolean)

    private suspend fun series(ref: AnimeRef): Series? = Memo.get("onsen:series:${ref.id}", Memo.DAY) {
        val titles = ProviderSupport.titles(ref)
        if (titles.isEmpty()) return@get null
        val candidates = LinkedHashMap<String, JSONObject>()
        ProviderSupport.queries(ref, 6).forEach { q ->
            search(q).forEach { hit ->
                val cid = hit.text("content_id") ?: return@forEach
                candidates.putIfAbsent(cid, hit)
            }
        }
        if (candidates.isEmpty()) return@get null
        val expected = ref.episodes?.takeIf { it > 0 } ?: ProviderSupport.guide(ref)?.episodeCount
        val wantMovie = ref.format == app.kuroka.api.MediaFormat.MOVIE

        var best: Series? = null
        var bestScore = 0f
        for ((contentId, hit) in candidates) {
            val name = hit.text("content_title_en") ?: hit.text("content_title") ?: ""
            val jp = hit.text("content_title_jp")
            val score = titleScore(ref, name, jp, contentId)
            if (score < 250) continue
            // What the site has of it: the MAL id says at once whether it is the same anime.
            val video = api("/v4/content/${encode(contentId)}/video/1") ?: continue
            val metadata = video.optJSONObject("metadata") ?: continue
            val malId = metadata.int("mal_id")
            if (ref.idMal != null && malId != null && malId != ref.idMal) continue
            val isMovie = metadata.optBoolean("is_movie")
            if (isMovie != wantMovie) continue
            val count = metadata.int("total_episodes") ?: 0
            // A series that has far fewer episodes than the anime is another release of it.
            val coverage = coverage(count, expected)
            val total = score / 1000f * 0.75f + coverage * 0.25f
            if (total > bestScore) {
                bestScore = total
                best = Series(contentId, name, malId, count, isMovie)
            }
        }
        best.takeIf { bestScore >= 0.45f }
    }

    /** How much of the anime a candidate covers (a recap or a single episode scores low). */
    private fun coverage(have: Int, expected: Int?): Float {
        if (expected == null || expected < 2) return 1f
        if (have <= 0) return 0.5f
        if (have == expected) return 1f
        if (have > expected && have <= expected + 2) return 0.95f
        return (have.toFloat() / expected.toFloat()).coerceIn(0f, 1f)
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** The site's own episodes: their number as the site counts it, and their title. */
    private suspend fun episodesOf(series: Series): List<Pair<String, Double>> {
        val data = api("/v4/content/${encode(series.contentId)}/episodes") ?: return emptyList()
        val out = ArrayList<Pair<String, Double>>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val number = key.toDoubleOrNull() ?: continue
            if (number < 1.0) continue
            out += key to number
        }
        if (out.isEmpty() && series.isMovie) return listOf("1" to 1.0)
        return out.sortedBy { it.second }
    }

    private fun titleOf(data: JSONObject?, key: String): String? =
        data?.optJSONObject(key)?.let { it.text("contentTitle_episode_en") ?: it.text("contentTitle_episode_jp") }

    override suspend fun episodes(anime: AnimeRef): EpisodeBundle? {
        val series = series(anime) ?: return null
        val list = episodesOf(series)
        if (list.isEmpty()) return null
        val details = api("/v4/content/${encode(series.contentId)}/episodes")
        val guide = ProviderSupport.guide(anime)
        val out = ArrayList<Episode>()
        // The site may carry more episodes than this season (a "Part 2" of one long series): keep
        // as many as the anime has, from the first one on.
        val expected = anime.episodes?.takeIf { it > 0 } ?: guide?.episodeCount
        val kept = if (expected != null && list.size > expected) list.take(expected) else list
        kept.forEachIndexed { index, (sourceNumber, _) ->
            val episodeNumber = (index + 1).toDouble()
            out += Episode(
                id = ProviderSupport.episodeId(id, anime.id, Audio.SUB, episodeNumber),
                number = episodeNumber,
                title = titleOf(details, sourceNumber),
                audio = Audio.SUB,
                source = id,
            )
        }
        return EpisodeBundle(
            animeId = anime.id, malId = series.malId ?: anime.idMal, source = id, title = series.title,
            sub = ProviderSupport.list(out, anime, guide),
        )
    }

    /**
     * [episodeId] is the synthetic id this source's own list gave (see
     * [ProviderSupport.episodeId]), so an episode is found by its number and the audio asked for.
     */
    override suspend fun watch(
        anime: AnimeRef, audio: Audio, episode: Double, episodeId: String?,
    ): WatchResult? {
        if (audio == Audio.DUB) return null
        val series = series(anime) ?: return null
        val list = episodesOf(series)
        val expected = anime.episodes?.takeIf { it > 0 } ?: ProviderSupport.guide(anime)?.episodeCount
        val kept = if (expected != null && list.size > expected) list.take(expected) else list
        val index = kept.indexOfFirst { it.second == episode }
            .takeIf { it >= 0 } ?: (episode.toInt() - 1).takeIf { it in kept.indices } ?: return null
        val sourceNumber = kept[index].first

        val s = session() ?: return null
        val video = api("/v4/content/${encode(series.contentId)}/video/${encode(sourceNumber)}") ?: return null
        val uri = video.optJSONObject("uri") ?: return null
        val stream = uri.text("stream") ?: return null
        val headers = ProviderSupport.streamHeaders("$SITE/", mapOf("Authorization" to "Bearer ${s.token}"))

        val subtitles = ArrayList<SubtitleTrack>()
        val labels = video.optJSONObject("metadata")?.optJSONObject("subtitles")
        val subs = uri.optJSONObject("subtitles")
        if (subs != null) {
            val keys = subs.keys()
            while (keys.hasNext()) {
                val language = keys.next()
                val url = subs.text(language) ?: continue
                subtitles += SubtitleTrack.of(
                    url = url,
                    label = labels?.text(language) ?: language,
                    language = language,
                    isDefault = language == "en-US" || language == "en",
                    headers = headers,
                )
            }
        }

        // The site marks the intro of some episodes itself.
        var skip = SkipTimes.None
        val meta = video.optJSONObject("metadata")
        val marks = meta?.opt("episode")
        if (marks is org.json.JSONArray) {
            for (i in 0 until marks.length()) {
                val m = marks.optJSONObject(i) ?: continue
                val start = m.optDouble("skipIntro_s", 0.0)
                val end = m.optDouble("skipIntro_e", 0.0)
                if (end > start && end > 0) { skip = SkipTimes(start, end, 0.0, 0.0); break }
            }
        } else if (marks is JSONObject) {
            val start = marks.optDouble("skipIntro_s", 0.0)
            val end = marks.optDouble("skipIntro_e", 0.0)
            if (end > start && end > 0) skip = SkipTimes(start, end, 0.0, 0.0)
        }
        if (!skip.hasAny) {
            val malId = series.malId ?: anime.idMal
            if (malId != null) {
                val (intro, outro) = app.kuroka.sources.guide.AniSkip.skipTimes(malId, episode)
                if (intro.isValid || outro.isValid) skip = SkipTimes(intro.start, intro.end, outro.start, outro.end)
            }
        }

        return WatchResult(
            animeId = anime.id, malId = series.malId ?: anime.idMal, episode = episode, audio = Audio.SUB,
            streams = listOf(
                StreamSource(
                    url = stream, type = StreamType.DASH, server = "Anime Onsen", referer = "$SITE/",
                    headers = headers, subtitles = subtitles, priority = 5,
                )
            ),
            subtitles = subtitles, skip = skip, headers = headers,
        )
    }
}
