package app.kuroka.sources.providers

import app.kuroka.api.AnimeRef
import app.kuroka.api.AnimeSource
import app.kuroka.api.Audio
import app.kuroka.api.DownloadLink
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.SkipTimes
import app.kuroka.api.StreamSource
import app.kuroka.api.StreamType
import app.kuroka.api.SubtitleTrack
import app.kuroka.api.WatchResult
import app.kuroka.sources.Memo
import app.kuroka.sources.guide.AniSkip
import app.kuroka.sources.net.Http
import app.kuroka.sources.net.text
import app.kuroka.sources.util.decodeEntities
import org.json.JSONArray
import org.json.JSONObject

/*
 * Senshi (senshi.to): a JSON API of episodes and of the servers that carry them, with the video
 * coming from a CDN that answers with an HLS playlist and its subtitle tracks. It searches by the
 * MAL id, so the guide's mapping is what makes it work for an AniList id.
 *
 * Ported from Kitsune (MIT, © Dovakiin0, https://github.com/Dovakiin0/Kitsune,
 * src/lib/providers/providers/senshi.js).
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class SenshiSource : AnimeSource {

    override val id = "senshi"
    override val name = "Senshi"
    override val note = "Subtitled and dubbed, many subtitle languages. Searches by the MAL id."
    override val hasDub = true
    override val subtitleLanguages = listOf(
        "en", "es", "pt", "fr", "de", "it", "ru", "ar", "ja", "ko", "zh", "hi", "id", "ms", "th", "vi",
    )

    private companion object {
        const val BASE = "https://senshi.to"
        const val VID_CLOUD = "https://s.vidcloud.se"
    }

    private val headers: Map<String, String>
        get() = mapOf("User-Agent" to Http.UA, "Referer" to "$BASE/", "Accept" to "application/json, text/plain, */*")

    /** The episodes of a MAL id, as the site lists them (one row per episode, both audios). */
    private suspend fun episodeJson(malId: Int): JSONArray {
        // The answer is a bare array, which JSONObject cannot hold: read it as text.
        val text = Memo.get("senshi:list:$malId", Memo.HOUR) {
            runCatching { Http.text("$BASE/episodes/$malId", headers) }.getOrNull()
        } ?: return JSONArray()
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }

    private suspend fun embeds(malId: Int, number: Double): JSONArray {
        val ep = ProviderSupport.numberLabel(number)
        val text = runCatching { Http.text("$BASE/episode-embeds/$malId/$ep", headers) }.getOrNull()
            ?: return JSONArray()
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }

    override suspend fun episodes(anime: AnimeRef): EpisodeBundle? {
        val malId = ProviderSupport.malId(anime) ?: return null
        val rows = episodeJson(malId)
        if (rows.length() == 0) return null

        // Whether a dub exists: the servers of the first episode say so.
        val probe = embeds(malId, rows.optJSONObject(0)?.let { episodeOf(it) } ?: 1.0)
        val hasDub = (0 until probe.length()).any { isDub(probe.optJSONObject(it)) }

        val sub = ArrayList<Episode>()
        val dub = ArrayList<Episode>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val number = episodeOf(row) ?: continue
            val title = row.text("ep_title")?.let { decodeEntities(it) }
            val image = row.text("ep_thumbnail")
            val filler = row.optBoolean("ep_filler")
            val recap = row.optBoolean("ep_recap")
            val airDate = row.text("ep_airdate") ?: row.text("airdate")
            sub += Episode(
                id = ProviderSupport.episodeId(id, anime.id, Audio.SUB, number),
                number = number, title = title, image = image, airDate = airDate,
                audio = Audio.SUB, filler = filler, recap = recap, source = id,
            )
            if (hasDub) dub += Episode(
                id = ProviderSupport.episodeId(id, anime.id, Audio.DUB, number),
                number = number, title = title, image = image, airDate = airDate,
                audio = Audio.DUB, filler = filler, recap = recap, source = id,
            )
        }
        if (sub.isEmpty()) return null
        val guide = ProviderSupport.guide(anime)
        return EpisodeBundle(
            animeId = anime.id, malId = malId, source = id,
            title = anime.title.display,
            sub = ProviderSupport.list(sub, anime, guide),
            dub = ProviderSupport.list(dub, anime, guide),
        )
    }

    /** The episode number of a row ("ep_id", or the number in its title). */
    private fun episodeOf(row: JSONObject): Double? =
        row.optDouble("ep_id").takeIf { !it.isNaN() && it > 0 }
            ?: app.kuroka.sources.util.episodeNumber(row.text("ep_id") ?: row.text("title"))

    private fun isDub(embed: JSONObject?): Boolean =
        (embed?.text("status") ?: "").equals("dub", ignoreCase = true)

    override suspend fun watch(anime: AnimeRef, audio: Audio, episode: Double): WatchResult? {
        val malId = ProviderSupport.malId(anime) ?: return null
        val embeds = embeds(malId, episode)
        if (embeds.length() == 0) return null
        val wantDub = audio == Audio.DUB
        var chosen: JSONObject? = null
        for (i in 0 until embeds.length()) {
            val e = embeds.optJSONObject(i) ?: continue
            if (isDub(e) == wantDub) { chosen = e; break }
        }
        chosen = chosen ?: embeds.optJSONObject(0) ?: return null

        val streams = ArrayList<StreamSource>()
        val subtitles = ArrayList<SubtitleTrack>()
        val downloads = ArrayList<DownloadLink>()

        // The CDN's own sources: an HLS playlist with its quality and its subtitle tracks.
        val remoteId = chosen.text("remote_source_id")
        if (!remoteId.isNullOrBlank()) {
            val cloudHeaders = mapOf(
                "User-Agent" to Http.UA,
                "Accept" to "application/json,*/*",
                "Origin" to BASE,
                "Referer" to "$BASE/",
            )
            val text = runCatching {
                Http.text("$VID_CLOUD/_v1/sources?id=$remoteId", cloudHeaders)
            }.getOrNull()
            val answer = text?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?: text?.let { runCatching { JSONArray().put(JSONObject(it)) }.getOrNull() }
            if (answer != null) for (i in 0 until answer.length()) {
                val entry = answer.optJSONObject(i) ?: continue
                val source = entry.optJSONObject("source") ?: continue
                val src = source.text("src") ?: continue
                val sourceAudio = source.text("audio")?.lowercase()
                if (sourceAudio != null && sourceAudio != "both" && sourceAudio != audio.code) continue
                val trackHeaders = ProviderSupport.streamHeaders("$BASE/")
                val own = ArrayList<SubtitleTrack>()
                entry.optJSONArray("tracks")?.let { tracks ->
                    for (t in 0 until tracks.length()) {
                        val track = tracks.optJSONObject(t) ?: continue
                        val url = track.text("vtt_url") ?: track.text("url") ?: continue
                        val label = track.text("label") ?: "English"
                        if (label.equals("chapter", ignoreCase = true)) continue
                        val lang = label.lowercase().split(WHITESPACE).first()
                        val sub = SubtitleTrack.of(
                            url = url,
                            label = if (lang == "english") "English" else label,
                            language = if (lang == "english") "en" else lang.take(2),
                            isDefault = track.optBoolean("default"),
                            headers = trackHeaders,
                        )
                        own += sub
                        if (subtitles.none { it.url == sub.url }) subtitles += sub
                    }
                }
                streams += StreamSource(
                    url = src,
                    type = StreamType.HLS,
                    server = source.text("quality")?.let { "Senshi · $it" } ?: "Senshi",
                    referer = "$BASE/",
                    headers = trackHeaders,
                    quality = source.text("quality"),
                    subtitles = own,
                    priority = if (streams.isEmpty()) 5 else 4,
                )
            }
        }

        // The site's own embeds, when the CDN answered nothing.
        chosen.text("url")?.let { url ->
            if (streams.isEmpty()) streams += StreamSource(
                url = url.replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://"),
                type = StreamType.of(null, url),
                server = "Senshi", referer = "$BASE/",
                headers = ProviderSupport.streamHeaders("$BASE/"),
                priority = 4,
            )
        }
        chosen.text("server2")?.let { url ->
            streams += StreamSource(
                url = url, type = StreamType.of(null, url), server = "StreamNin", referer = "$BASE/",
                headers = ProviderSupport.streamHeaders("$BASE/"), priority = 3,
            )
        }
        chosen.text("serverFM")?.let { url ->
            streams += StreamSource(
                url = url, type = StreamType.of(null, url), server = "FileMoon", referer = "$BASE/",
                headers = ProviderSupport.streamHeaders("$BASE/"), priority = 2,
            )
        }
        chosen.text("download")?.let { url ->
            downloads += DownloadLink(url, "Download", ProviderSupport.streamHeaders("$BASE/"))
        }
        if (streams.isEmpty()) return null

        // The site's own intro and outro marks, else AniSkip's.
        val rows = episodeJson(malId)
        var skip = SkipTimes.None
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (episodeOf(row) == episode) {
                skip = SkipTimes(
                    introStart = row.optDouble("intro_start", 0.0),
                    introEnd = row.optDouble("intro_end", 0.0),
                    outroStart = row.optDouble("outro_start", 0.0),
                    outroEnd = row.optDouble("outro_end", 0.0),
                )
                break
            }
        }
        if (!skip.hasAny) {
            val (intro, outro) = AniSkip.skipTimes(malId, episode)
            if (intro.isValid || outro.isValid) skip = SkipTimes(intro.start, intro.end, outro.start, outro.end)
        }

        return WatchResult(
            animeId = anime.id, malId = malId, episode = episode, audio = audio,
            streams = streams.sortedByDescending { it.priority },
            subtitles = subtitles, downloads = downloads, skip = skip,
            headers = ProviderSupport.streamHeaders("$BASE/"),
        )
    }

    private val WHITESPACE = Regex("\\s+")
}
