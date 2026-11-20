package app.kuroka.sources.guide

import app.kuroka.api.EpisodeMeta
import app.kuroka.sources.Memo
import app.kuroka.sources.net.Http
import app.kuroka.sources.net.int
import app.kuroka.sources.net.text
import org.json.JSONObject
import java.io.IOException

/*
 * The episode guide: what AniList's own episode list does not say (a title in English, a
 * thumbnail, whether an episode is a filler or a recap, how long it runs). AniZip answers it from
 * an AniList id, and AniSkip from a MAL id and an episode number says where the intro and the
 * outro of that episode are, so the player can skip them.
 *
 * Both are public APIs, asked once per anime and kept for a day.
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** What AniZip knows of one episode. */
data class GuideEpisode(
    val number: Double,
    val title: String? = null,
    val overview: String? = null,
    val image: String? = null,
    val airdate: String? = null,
    val runtimeMinutes: Int? = null,
    val filler: Boolean = false,
    val recap: Boolean = false,
)

/** What AniZip knows of one anime: its other ids, its titles and its episodes. */
data class AnimeGuide(
    val anilistId: Int,
    val malId: Int? = null,
    val titles: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val cover: String? = null,
    val episodes: Map<Double, GuideEpisode> = emptyMap(),
) {
    fun meta(number: Double): GuideEpisode? = episodes[number]

    /** The guide as the models of :api describe episode metadata. */
    fun toMeta(): List<EpisodeMeta> = episodes.values.sortedBy { it.number }.map {
        EpisodeMeta(
            number = it.number,
            title = it.title,
            description = it.overview,
            image = it.image,
            airDate = it.airdate,
            duration = it.runtimeMinutes?.let { m -> m * 60 },
            filler = it.filler,
            recap = it.recap,
        )
    }
}

object AniZip {
    private const val ENDPOINT = "https://api.ani.zip/mappings"

    /** The guide of an AniList id, or null when AniZip does not know that anime. */
    suspend fun guide(anilistId: Int): AnimeGuide? = Memo.get("anizip:$anilistId", Memo.DAY) {
        runCatching { parse(anilistId, Http.json("$ENDPOINT?anilist_id=$anilistId")) }.getOrNull()
    }

    private fun parse(anilistId: Int, o: JSONObject): AnimeGuide? {
        val titles = ArrayList<String>()
        o.optJSONObject("titles")?.let { t ->
            listOf("en", "x-jat", "ja", "ja-jp").forEach { k -> t.text(k)?.let { titles += it } }
        }
        o.text("title")?.let { titles += it }
        val episodes = HashMap<Double, GuideEpisode>()
        o.optJSONObject("episodes")?.let { eps ->
            eps.keys().forEach { key ->
                val number = key.toDoubleOrNull() ?: return@forEach
                val e = eps.optJSONObject(key) ?: return@forEach
                val title = e.optJSONObject("title")
                episodes[number] = GuideEpisode(
                    number = number,
                    title = title?.text("en") ?: title?.text("x-jat"),
                    overview = e.text("overview") ?: e.text("summary"),
                    image = e.text("image"),
                    airdate = e.text("airdate") ?: e.text("aired"),
                    runtimeMinutes = e.int("runtime") ?: e.int("length"),
                    filler = !e.isNull("filler") && e.optBoolean("filler"),
                    recap = !e.isNull("recap") && e.optBoolean("recap"),
                )
            }
        }
        val mal = o.int("mal_id") ?: o.int("malId")
        if (episodes.isEmpty() && mal == null && titles.isEmpty()) return null
        return AnimeGuide(
            anilistId = anilistId,
            malId = mal,
            titles = titles.distinct(),
            episodeCount = o.int("episodeCount") ?: o.int("episode_count") ?: episodes.size.takeIf { it > 0 },
            cover = o.optJSONObject("images")?.text("cover"),
            episodes = episodes,
        )
    }
}

/** Where the intro and the outro of an episode are, in seconds (AniSkip). */
data class Skip(val start: Double, val end: Double) {
    val isValid: Boolean get() = end > start && end > 0.0
}

object AniSkip {
    private const val ENDPOINT = "https://api.aniskip.com/v2/skip-times"

    /**
     * The intro and outro of one episode of a MAL id. [episodeLength] of 0 asks AniSkip for
     * whatever it knows; a provider that says how long the episode runs gets a better outro.
     */
    suspend fun skipTimes(malId: Int, episode: Double, episodeLength: Int = 0): Pair<Skip, Skip> =
        Memo.get("aniskip:$malId:$episode", Memo.DAY) {
            val op = Skip(0.0, 0.0)
            val ed = Skip(0.0, 0.0)
            try {
                val number = if (episode % 1.0 == 0.0) episode.toInt().toString() else episode.toString()
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https").host("api.aniskip.com")
                    .addPathSegments("v2/skip-times/$malId/$number")
                    .addQueryParameter("types[]", "op")
                    .addQueryParameter("types[]", "ed")
                    .addQueryParameter("episodeLength", episodeLength.toString())
                    .build().toString()
                val answer = Http.json(url, Http.jsonHeaders(), tries = 1)
                val results = answer.optJSONArray("results")
                var intro = op
                var outro = ed
                if (results != null) for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val interval = r.optJSONObject("interval") ?: continue
                    val start = interval.optDouble("startTime", 0.0)
                    val end = interval.optDouble("endTime", 0.0)
                    val skip = Skip(start, end)
                    if (!skip.isValid) continue
                    when (r.text("skipType")) {
                        "op" -> intro = skip
                        "ed" -> outro = skip
                        "mixed-ed" -> if (!outro.isValid) outro = skip
                        "mixed-op" -> if (!intro.isValid) intro = skip
                    }
                }
                Pair(intro, outro)
            } catch (e: IOException) {
                Pair(op, ed)
            }
        } ?: Pair(Skip(0.0, 0.0), Skip(0.0, 0.0))
}
