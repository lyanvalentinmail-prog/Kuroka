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
import app.kuroka.sources.net.Http
import app.kuroka.sources.net.int
import app.kuroka.sources.net.text
import app.kuroka.sources.util.episodeNumber
import org.json.JSONArray
import org.json.JSONObject

/*
 * A Kitsune server of your own (https://github.com/Dovakiin0/Kitsune, MIT): the web app's API,
 * `/api/episodes/{anilistId}` and `/api/watch/{provider}/{anilistId}/{audio}/{id}`, which asks
 * every provider Kitsune has and answers with the streams, the subtitles and the skip times of
 * the first one that worked. Point Kuroka at one in Settings, Sources, and its dozen providers
 * become this app's, with the server doing the scraping and the caching.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class KitsuneServerSource(
    /** The address of the server, without a path ("https://anime.example.com"); empty = off. */
    private val baseUrl: () -> String?,
) : AnimeSource {

    override val id = "kitsune"
    override val name = "Kitsune server"
    override val note = "Your own Kitsune backend: every provider it has, over its API."
    override val hasDub = true
    override val subtitleLanguages = listOf("en", "es", "pt", "fr", "de", "it", "ru", "ar", "ja", "ko", "zh", "id", "ms", "th", "vi", "hi")
    /** Off until an address is given: a source with no server answers nothing. */
    override val enabledByDefault = false

    /** Ready only once Settings, Sources has an address to ask. */
    override val isReady: Boolean get() = looksLikeServer(base())

    private fun base(): String? = baseUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    /** The provider the server answered with last for this anime (the watch call asks it back). */
    private val chosen = java.util.concurrent.ConcurrentHashMap<Int, String>()

    override suspend fun episodes(anime: AnimeRef): EpisodeBundle? {
        val base = base() ?: return null
        val answer = runCatching { Http.json("$base/api/episodes/${anime.id}", Http.jsonHeaders(base)) }.getOrNull()
            ?: return null
        val bundle = KitsuneJson.episodes(answer, anime.id, preferredProvider(anime.id)) ?: return null
        chosen[anime.id] = bundle.source
        return bundle
    }

    override suspend fun watch(anime: AnimeRef, audio: Audio, episode: Double, episodeId: String?): WatchResult? {
        val base = base() ?: return null
        // The episode list's own id is the server's path ("watch/senshi/21/sub/senshi-12"); without
        // one, the provider that answered the list is asked for that number.
        val path = episodeId?.removePrefix("/")?.let { if (it.startsWith("watch/")) it else null }
            ?: ProviderSupport.episodeId(chosen[anime.id] ?: "senshi", anime.id, audio, episode)
        val answer = runCatching { Http.json("$base/api/$path", Http.jsonHeaders(base)) }.getOrNull() ?: return null
        return KitsuneJson.watch(answer, anime.id, audio, episode)
    }

    /** The provider to keep when the server answers with several: the user's own, else the fullest. */
    private fun preferredProvider(animeId: Int): String? = chosen[animeId]

    companion object {
        /** True when the address in the settings looks like one Kuroka can ask. */
        fun looksLikeServer(url: String?): Boolean {
            val u = url?.trim() ?: return false
            return u.startsWith("http://") || u.startsWith("https://")
        }
    }
}

/**
 * The answers of a Kitsune server, read into Kuroka's models. Kept apart so a test can feed them
 * from a file, and so the app's own providers answer the same shape.
 */
object KitsuneJson {

    /** The providers a server answers with, in the order Kuroka prefers them. */
    val PREFERRED = listOf(
        "senshi", "animeonsen", "anikoto", "reanime", "mkissa", "anizone", "aniwaves", "anipm",
        "animegg", "animedunya", "animenosub", "kickassanime",
    )

    /**
     * The episode lists of every provider in the answer, as one bundle: the preferred provider
     * that has episodes wins, so the same anime keeps coming from the same place.
     */
    fun episodes(answer: JSONObject, animeId: Int, prefer: String? = null): EpisodeBundle? {
        val names = ArrayList<String>()
        val keys = answer.keys()
        while (keys.hasNext()) names += keys.next()
        val candidates = names.filter { answer.optJSONObject(it)?.optJSONObject("episodes") != null }
        if (candidates.isEmpty()) return null
        val ordered = candidates.sortedByDescending { name ->
            val score = PREFERRED.indexOf(name)
            (if (name == prefer) 1000 else 0) + (if (score >= 0) 100 - score else 0) +
                (answer.optJSONObject(name)?.optJSONObject("episodes")?.optJSONArray("sub")?.length() ?: 0) / 10
        }
        for (name in ordered) {
            val provider = answer.optJSONObject(name) ?: continue
            val bundle = bundleOf(provider, animeId, name) ?: continue
            if (!bundle.isEmpty) return bundle
        }
        return null
    }

    private fun bundleOf(provider: JSONObject, animeId: Int, name: String): EpisodeBundle? {
        val episodes = provider.optJSONObject("episodes") ?: return null
        val meta = provider.optJSONObject("meta")
        val sub = listOf(episodes.optJSONArray("sub"), episodes.optJSONArray("Sub")).firstNotNullOfOrNull { it }
            ?.let { list(it, animeId, Audio.SUB, name) } ?: emptyList()
        val dub = episodes.optJSONArray("dub")?.let { list(it, animeId, Audio.DUB, name) } ?: emptyList()
        if (sub.isEmpty() && dub.isEmpty()) return null
        return EpisodeBundle(
            animeId = animeId,
            malId = meta?.int("malId"),
            source = name,
            title = meta?.text("title"),
            sub = sub,
            dub = dub,
        )
    }

    private fun list(a: JSONArray, animeId: Int, audio: Audio, source: String): List<Episode> {
        val out = ArrayList<Episode>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val number = o.optDouble("number").takeIf { !it.isNaN() && it > 0 }
                ?: episodeNumber(o.text("number") ?: o.text("episode")) ?: continue
            out += Episode(
                id = o.text("id") ?: ProviderSupport.episodeId(source, animeId, audio, number),
                number = number,
                title = o.text("title"),
                description = o.text("description"),
                image = o.text("image"),
                airDate = o.text("airDate"),
                duration = o.int("duration"),
                audio = audio,
                filler = o.optBoolean("filler"),
                recap = o.optBoolean("recap"),
                source = o.text("source") ?: source,
            )
        }
        return out.sortedBy { it.number }
    }

    /** One episode's streams, subtitles, downloads and skip times. */
    fun watch(answer: JSONObject, animeId: Int, audio: Audio, episode: Double): WatchResult? {
        val streams = ArrayList<StreamSource>()
        val subtitles = ArrayList<SubtitleTrack>()
        val headers = headerMap(answer.optJSONObject("headers"))
        val referer = answer.text("referer")

        answer.optJSONArray("streams")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val url = o.text("url") ?: continue
                val own = o.optJSONArray("subtitles")?.let { tracks(it, headers) } ?: emptyList()
                streams += StreamSource(
                    url = url,
                    type = StreamType.of(o.text("type"), url),
                    server = o.text("server") ?: "Server ${streams.size + 1}",
                    referer = o.text("referer") ?: referer,
                    headers = if (headers.isEmpty()) ProviderSupport.streamHeaders(o.text("referer") ?: referer) else headers,
                    quality = o.text("quality"),
                    subtitles = own,
                    priority = o.int("priority") ?: (if (o.optBoolean("isActive")) 5 else 3),
                )
                own.forEach { t -> if (subtitles.none { it.url == t.url }) subtitles += t }
            }
        }
        answer.optJSONArray("subtitles")?.let { a ->
            tracks(a, headers).forEach { t -> if (subtitles.none { it.url == t.url }) subtitles += t }
        }
        val downloads = ArrayList<DownloadLink>()
        answer.optJSONArray("downloads")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val url = o.text("url") ?: continue
                downloads += DownloadLink(url, o.text("label") ?: "Download", headers)
            }
        }
        if (streams.isEmpty()) return null
        val intro = answer.optJSONObject("intro")
        val outro = answer.optJSONObject("outro")
        val skip = SkipTimes(
            introStart = intro?.optDouble("start", 0.0) ?: 0.0,
            introEnd = intro?.optDouble("end", 0.0) ?: 0.0,
            outroStart = outro?.optDouble("start", 0.0) ?: 0.0,
            outroEnd = outro?.optDouble("end", 0.0) ?: 0.0,
        )
        return WatchResult(
            animeId = answer.int("anilistId") ?: animeId,
            malId = answer.int("malId"),
            episode = answer.optDouble("episode", episode).takeIf { !it.isNaN() } ?: episode,
            audio = Audio.of(answer.text("audio") ?: audio.code),
            streams = streams.sortedByDescending { it.priority },
            subtitles = subtitles,
            downloads = downloads,
            skip = skip,
            headers = headers,
        )
    }

    private fun tracks(a: JSONArray, headers: Map<String, String>): List<SubtitleTrack> {
        val out = ArrayList<SubtitleTrack>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val url = o.text("url") ?: o.text("src") ?: continue
            out += SubtitleTrack.of(
                url = url,
                label = o.text("label") ?: o.text("name"),
                language = o.text("srclang") ?: o.text("lang") ?: o.text("language"),
                isDefault = o.optBoolean("default"),
                headers = headers,
            )
        }
        return out
    }

    private fun headerMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            o.text(k)?.let { out[k] = it }
        }
        return out
    }
}
