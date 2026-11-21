package app.kuroka.sources.providers

import app.kuroka.api.AnimeRef
import app.kuroka.api.AnimeSource
import app.kuroka.api.Audio
import app.kuroka.api.Episode
import app.kuroka.api.EpisodeBundle
import app.kuroka.api.StreamSource
import app.kuroka.api.StreamType
import app.kuroka.api.SubtitleTrack
import app.kuroka.api.WatchResult
import app.kuroka.sources.Memo
import app.kuroka.sources.guide.AniSkip
import app.kuroka.api.SkipTimes
import app.kuroka.sources.net.Http
import app.kuroka.sources.net.text
import app.kuroka.sources.util.Embedded
import app.kuroka.sources.util.decodeEntities
import org.json.JSONObject

/*
 * Anime Dunya (anime-dunya.com): a page per anime and a page per episode, each carrying its data
 * as escaped JSON inside the HTML, as a Next.js site does. The stream is one HLS playlist with
 * the subtitles the site has for it; the site is Arabic-first and its subtitles come in many
 * languages.
 *
 * Ported from Kitsune (MIT, © Dovakiin0, https://github.com/Dovakiin0/Kitsune,
 * src/lib/providers/providers/animedunya.js).
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class AnimeDunyaSource : AnimeSource {

    override val id = "animedunya"
    override val name = "Anime Dunya"
    override val note = "Subtitled streams with subtitles in many languages. Searches by the MAL id."
    override val hasDub = false
    override val subtitleLanguages = listOf("ar", "en", "fr", "es", "de", "it", "pt", "ru", "tr", "id", "ms", "zh", "ja", "ko")

    private companion object {
        const val BASE = "https://anime-dunya.com"
        val THUMBNAIL = Regex("(https?://[^\\s\"'`<>]+?/thumbnail/)([a-zA-Z0-9]+?)/((?:small|large)\\.jpg)")
    }

    private val headers: Map<String, String>
        get() = Http.browserHeaders("$BASE/")

    override suspend fun episodes(anime: AnimeRef): EpisodeBundle? {
        val malId = ProviderSupport.malId(anime) ?: return null
        val html = Memo.get("dunya:page:$malId", Memo.HOUR) {
            runCatching { Http.text("$BASE/en/anime/$malId", headers) }.getOrNull()
        } ?: return null

        val episodes = Embedded.array(html, "episodes") ?: return null
        if (episodes.length() == 0) return null

        // The thumbnails live on a CDN whose base the page itself carries.
        var cdnBase = "https://cdn.anime-dunya.com/thumbnail/"
        var cdnExt = "small.jpg"
        THUMBNAIL.find(html)?.let {
            cdnBase = it.groupValues[1]
            cdnExt = it.groupValues[3]
        }

        val out = ArrayList<Episode>()
        for (i in 0 until episodes.length()) {
            val e = episodes.optJSONObject(i) ?: continue
            val streamId = e.text("streamId") ?: continue
            val number = e.optDouble("episodeNumber").takeIf { !it.isNaN() && it > 0 } ?: continue
            // The title in English when the site translated it, else the first one it has.
            var title: String? = null
            var firstTitle: String? = null
            val translations = e.opt("translations")
            if (translations is org.json.JSONArray) {
                for (t in 0 until translations.length()) {
                    val tr = translations.optJSONObject(t) ?: continue
                    val translated = tr.text("title") ?: continue
                    if (firstTitle == null) firstTitle = translated
                    if (tr.text("language") == "en") title = translated
                }
            } else if (translations is JSONObject) {
                firstTitle = translations.text("title")
            }
            if (title == null) title = firstTitle
            out += Episode(
                id = ProviderSupport.episodeId(id, anime.id, Audio.SUB, number),
                number = number,
                title = title?.let { decodeEntities(it) },
                image = cdnBase + streamId + "/" + cdnExt,
                audio = Audio.SUB,
                filler = e.optBoolean("filler"),
                recap = e.optBoolean("recap"),
                source = id,
            )
        }
        if (out.isEmpty()) return null
        val guide = ProviderSupport.guide(anime)
        return EpisodeBundle(
            animeId = anime.id, malId = malId, source = id, title = anime.title.display,
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
        val malId = ProviderSupport.malId(anime) ?: return null
        val number = ProviderSupport.numberLabel(episode)
        val html = runCatching { Http.text("$BASE/en/play/$malId/$number", headers) }.getOrNull() ?: return null

        val stream = Embedded.obj(html, "stream") ?: return null
        val url = stream.text("source") ?: Embedded.string(html, "source") ?: return null
        if (url.isBlank()) return null

        val subtitles = ArrayList<SubtitleTrack>()
        stream.optJSONArray("subtitles")?.let { tracks ->
            for (t in 0 until tracks.length()) {
                val track = tracks.optJSONObject(t) ?: continue
                val trackUrl = track.text("src") ?: track.text("url") ?: continue
                subtitles += SubtitleTrack.of(
                    url = trackUrl,
                    label = track.text("label"),
                    language = track.text("srclang") ?: track.text("lang"),
                    isDefault = track.optBoolean("default"),
                    headers = ProviderSupport.streamHeaders("$BASE/"),
                )
            }
        }

        val (intro, outro) = AniSkip.skipTimes(malId, episode)
        return WatchResult(
            animeId = anime.id, malId = malId, episode = episode, audio = Audio.SUB,
            streams = listOf(
                StreamSource(
                    url = url, type = StreamType.of(null, url), server = "Anime Dunya", referer = "$BASE/",
                    headers = ProviderSupport.streamHeaders("$BASE/"), subtitles = subtitles, priority = 5,
                )
            ),
            subtitles = subtitles,
            skip = SkipTimes(intro.start, intro.end, outro.start, outro.end),
            headers = ProviderSupport.streamHeaders("$BASE/"),
        )
    }
}
