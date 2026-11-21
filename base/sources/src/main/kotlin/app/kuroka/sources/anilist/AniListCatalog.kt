package app.kuroka.sources.anilist

import app.kuroka.api.AiringStatus
import app.kuroka.api.Anime
import app.kuroka.api.Catalog
import app.kuroka.api.Character
import app.kuroka.api.MediaFormat
import app.kuroka.api.Page
import app.kuroka.api.Relation
import app.kuroka.api.RelationType
import app.kuroka.api.SearchFilters
import app.kuroka.api.Season
import app.kuroka.api.SeasonEntry
import app.kuroka.api.Seasons
import app.kuroka.api.Sort
import app.kuroka.sources.Memo
import app.kuroka.sources.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/*
 * AniList as the catalog: what anime there are, what they are about, how they hang together
 * (the season sheet walks the prequel/sequel chain) and what to watch next. It is a public
 * GraphQL API and needs no key for reading; a signed-in account reads the same pages with its
 * token, so the lists it may see are the user's own.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class AniListCatalog(
    /** The account's token, when there is one (a page may then show the user's own state). */
    private val token: () -> String? = { null },
    /** Whether the searches may answer anime marked for adults (Settings, Sources). */
    private val allowAdult: () -> Boolean = { false },
) : Catalog {

    override val name = "AniList"

    override fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private suspend fun ask(query: String, variables: JSONObject, what: String): JSONObject =
        Http.graphql(AniListQueries.ENDPOINT, query, variables, token(), what = what)

    private fun adultFlag(o: JSONObject) {
        // No flag at all: AniList answers the anime for everyone. `true` would ask only for those.
        if (!allowAdult()) o.put("isAdult", false)
    }

    override suspend fun search(filters: SearchFilters): Page<Anime> {
        val v = JSONObject()
            .put("page", filters.page.coerceAtLeast(1))
            .put("perPage", filters.perPage.coerceIn(1, 50))
            .put("type", "ANIME")
            .put("sort", JSONArray().put(filters.sort.code))
        if (filters.query.isNotBlank()) v.put("search", filters.query.trim())
        if (filters.genres.isNotEmpty()) v.put("genre_in", JSONArray(filters.genres as Collection<*>))
        if (filters.excludedGenres.isNotEmpty()) v.put("genre_not_in", JSONArray(filters.excludedGenres as Collection<*>))
        filters.format?.takeIf { it != MediaFormat.UNKNOWN }?.let { v.put("format", it.code) }
        filters.status?.takeIf { it != AiringStatus.UNKNOWN }?.let { v.put("status", it.code) }
        filters.season?.takeIf { it != Season.UNKNOWN }?.let { v.put("season", it.code) }
        filters.year?.let { v.put("seasonYear", it) }
        filters.yearFrom?.let { v.put("startDate_greater", it * 10000 + 101) }
        filters.yearTo?.let { v.put("startDate_lesser", it * 10000 + 1231) }
        v.put("episodes_greater", 0)
        adultFlag(v)
        val data = ask(AniListQueries.SEARCH, v, "search")
        return AniListParse.page(data, "media") { AniListParse.media(it) }.let { Page(it.items, it.page, it.hasNext) }
    }

    /** One row of the home page: a sort, a season when the row is "this season", the airing window. */
    private suspend fun row(sort: Sort, page: Int, perPage: Int, season: Season? = null,
                            year: Int? = null, airingFrom: Long? = null, airingTo: Long? = null): Page<Anime> {
        val v = JSONObject()
            .put("page", page.coerceAtLeast(1))
            .put("perPage", perPage.coerceIn(1, 50))
            .put("sort", JSONArray().put(sort.code))
        season?.takeIf { it != Season.UNKNOWN }?.let { v.put("season", it.code) }
        year?.let { v.put("seasonYear", it) }
        airingFrom?.let { v.put("airingAt_greater", it / 1000) }
        airingTo?.let { v.put("airingAt_lesser", it / 1000) }
        adultFlag(v)
        val data = ask(AniListQueries.PAGE_ROW, v, sort.label)
        return AniListParse.page(data, "media") { AniListParse.media(it) }.let { Page(it.items, it.page, it.hasNext) }
    }

    override suspend fun trending(page: Int, perPage: Int) = row(Sort.TRENDING, page, perPage)

    override suspend fun popular(page: Int, perPage: Int) = row(Sort.POPULARITY, page, perPage)

    override suspend fun seasonal(year: Int, season: Season, page: Int, perPage: Int) =
        row(Sort.POPULARITY, page, perPage, season, year)

    override suspend fun airingNext(page: Int, perPage: Int): Page<Anime> {
        val now = System.currentTimeMillis()
        return row(Sort.POPULARITY, page, perPage, airingFrom = now, airingTo = now + 7L * 24 * 3600 * 1000)
    }

    /** The season the app opens on: the one airing now, or the last one when between seasons. */
    fun currentSeason(): Pair<Int, Season> {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        val season = Season.ofMonth(month)
        // The first days of January still air the fall season's last episodes.
        return if (month == 1 && cal.get(Calendar.DAY_OF_MONTH) <= 7) Pair(year - 1, Season.FALL) else Pair(year, season)
    }

    override suspend fun details(id: Int): Anime? = Memo.get("anilist:media:$id", Memo.HOUR) {
        val v = JSONObject().put("id", id)
        val data = ask(AniListQueries.DETAILS, v, "anime page")
        val media = data.optJSONObject("Media") ?: return@get null
        AniListParse.media(media)?.copy(characters = AniListParse.characters(media))
    }

    override suspend fun relations(id: Int): List<Relation> = Memo.get("anilist:relations:$id", Memo.HOUR) {
        val v = JSONObject().put("id", id)
        val data = ask(AniListQueries.RELATIONS, v, "relations")
        AniListParse.relations(data.optJSONObject("Media"))
    } ?: emptyList()

    override suspend fun recommendations(id: Int): List<Anime> =
        Memo.get("anilist:recs:$id", Memo.HOUR) {
            val v = JSONObject().put("id", id)
            val data = ask(AniListQueries.RECOMMENDATIONS, v, "recommendations")
            AniListParse.recommendations(data.optJSONObject("Media"))
        } ?: emptyList()

    /**
     * The seasons of one story: back to the first one, then forward again, so the sheet reads in
     * order. Side stories and spin-offs hang off the season they belong to. Every step is one
     * light relations call, and the walk stops when it comes back to an anime it has seen.
     */
    override suspend fun seasons(id: Int): Seasons = Memo.get("anilist:seasons:$id", Memo.HOUR) {
        val self = details(id)
        val seen = LinkedHashSet<Int>()
        val chain = ArrayList<SeasonEntry>()

        // Back to the root of the chain.
        var root = id
        var rootEntry: SeasonEntry? = self?.let {
            SeasonEntry(it.id, it.title, it.cover, it.format, it.status, it.season,
                it.seasonYear ?: it.startDate?.year, it.episodes, "This season")
        }
        var guard = 0
        while (guard++ < 12) {
            val rels = relations(root)
            val prequel = rels.firstOrNull { it.type == RelationType.PREQUEL && seen.add(it.id) } ?: break
            root = prequel.id
            rootEntry = AniListParse.seasonEntry(prequel, "Prequel")
        }
        if (rootEntry == null) return@get Seasons(emptyList(), id)

        // Forward from the root, picking up the side stories of every season on the way.
        var current = root
        var pending: SeasonEntry? = rootEntry
        guard = 0
        while (guard++ < 24) {
            val entry = pending ?: break
            seen += current
            chain += entry
            val rels = relations(current)
            rels.filter { it.type == RelationType.SIDE_STORY || it.type == RelationType.SPIN_OFF || it.type == RelationType.PARENT }
                .filter { seen.add(it.id) }
                .forEach { chain += AniListParse.seasonEntry(it, it.type.label) }
            val sequel = rels.firstOrNull { it.type == RelationType.SEQUEL && it.id !in seen }
            if (sequel == null) {
                pending = null
            } else {
                current = sequel.id
                pending = AniListParse.seasonEntry(sequel, "Sequel")
            }
        }

        // Oldest first: a chain that started in the middle is turned around by its years.
        val ordered = chain.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.season.ordinal }))
        Seasons(ordered, id)
    } ?: Seasons(emptyList(), id)

    override suspend fun genres(): List<String> = Memo.get("anilist:genres", Memo.DAY) {
        val data = ask(AniListQueries.GENRES, JSONObject(), "genres")
        val arr = data.optJSONArray("GenreCollection") ?: return@get emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
        out.sorted()
    } ?: emptyList()

    /** The home page's rows, asked together (one round trip each, in parallel). */
    suspend fun home(perPage: Int = 12): HomeRows = coroutineScope {
        val (year, season) = currentSeason()
        val trending = async { runCatching { trending(1, perPage).items }.getOrDefault(emptyList()) }
        val seasonal = async { runCatching { seasonal(year, season, 1, perPage).items }.getOrDefault(emptyList()) }
        val popular = async { runCatching { popular(1, perPage).items }.getOrDefault(emptyList()) }
        val airing = async { runCatching { airingNext(1, perPage).items }.getOrDefault(emptyList()) }
        val top = async { runCatching { row(Sort.SCORE, 1, perPage).items }.getOrDefault(emptyList()) }
        HomeRows(
            year = year,
            season = season,
            trending = trending.await(),
            seasonal = seasonal.await(),
            popular = popular.await(),
            airing = airing.await(),
            top = top.await(),
        )
    }
}

/** What the home page shows, in the order Settings, Display puts it in. */
data class HomeRows(
    val year: Int,
    val season: Season,
    val trending: List<Anime> = emptyList(),
    val seasonal: List<Anime> = emptyList(),
    val popular: List<Anime> = emptyList(),
    val airing: List<Anime> = emptyList(),
    val top: List<Anime> = emptyList(),
) {
    fun section(id: String): List<Anime> = when (id) {
        "trending" -> trending
        "seasonal" -> seasonal
        "popular" -> popular
        "airing" -> airing
        "top" -> top
        else -> emptyList()
    }
}
