package app.kuroka.sources.anilist

import app.kuroka.api.AccountUser
import app.kuroka.api.AiringEpisode
import app.kuroka.api.AiringStatus
import app.kuroka.api.Anime
import app.kuroka.api.Character
import app.kuroka.api.EpisodeMeta
import app.kuroka.api.Favourite
import app.kuroka.api.FuzzyDate
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.api.MediaFormat
import app.kuroka.api.Page
import app.kuroka.api.Relation
import app.kuroka.api.RelationType
import app.kuroka.api.Season
import app.kuroka.api.SeasonEntry
import app.kuroka.api.Title
import app.kuroka.api.Trailer
import app.kuroka.sources.net.bool
import app.kuroka.sources.net.int
import app.kuroka.sources.net.long
import app.kuroka.sources.net.mapList
import app.kuroka.sources.net.stringList
import app.kuroka.sources.net.text
import app.kuroka.sources.util.decodeEntities
import app.kuroka.sources.util.stripTags
import org.json.JSONArray
import org.json.JSONObject

/*
 * AniList's answers, read into Kuroka's models. One place, so a field the UI starts using is
 * parsed once and every query that carries it fills it.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object AniListParse {

    /** The description AniList sends as HTML: the tags and the line breaks come back plain. */
    private fun cleanDescription(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val withBreaks = raw.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
        val plain = stripTags(withBreaks)
        return plain.ifBlank { null }
    }

    fun title(o: JSONObject?): Title {
        val t = o ?: return Title()
        return Title(
            romaji = t.text("romaji")?.let { decodeEntities(it) },
            english = t.text("english")?.let { decodeEntities(it) },
            native = t.text("native")?.let { decodeEntities(it) },
            userPreferred = t.text("userPreferred")?.let { decodeEntities(it) },
        )
    }

    fun date(o: JSONObject?): FuzzyDate? {
        val d = o ?: return null
        val date = FuzzyDate(d.int("year"), d.int("month"), d.int("day"))
        return if (date.isEmpty) null else date
    }

    fun cover(o: JSONObject?): String? {
        val c = o ?: return null
        return c.text("extraLarge") ?: c.text("large") ?: c.text("medium")
    }

    /** One media node, as every row and page of the app reads it. */
    fun media(o: JSONObject?): Anime? {
        val m = o ?: return null
        val id = m.int("id") ?: return null
        val studios = m.optJSONObject("studios")?.optJSONArray("nodes")?.mapList { it.text("name") } ?: emptyList()
        // A spoiler tag is left out: the page shows what the anime is, not how it ends.
        val tags = m.optJSONArray("tags")?.mapList {
            if (it.bool("isMediaSpoiler")) null else it.text("name")
        } ?: emptyList()
        return Anime(
            id = id,
            idMal = m.int("idMal"),
            title = title(m.optJSONObject("title")),
            cover = cover(m.optJSONObject("coverImage")),
            banner = m.text("bannerImage"),
            description = cleanDescription(m.text("description")),
            genres = m.optJSONArray("genres")?.stringList() ?: emptyList(),
            tags = tags,
            format = MediaFormat.of(m.text("format")),
            status = AiringStatus.of(m.text("status")),
            season = Season.of(m.text("season")),
            seasonYear = m.int("seasonYear"),
            episodes = m.int("episodes"),
            duration = m.int("duration"),
            averageScore = m.int("averageScore"),
            popularity = m.int("popularity"),
            favourites = m.int("favourites"),
            startDate = date(m.optJSONObject("startDate")),
            endDate = date(m.optJSONObject("endDate")),
            nextAiring = m.optJSONObject("nextAiringEpisode")?.let {
                val ep = it.int("episode") ?: return@let null
                AiringEpisode(ep, it.long("airingAt") ?: 0L, it.long("timeUntilAiring") ?: 0L)
            },
            synonyms = m.optJSONArray("synonyms")?.stringList() ?: emptyList(),
            country = m.text("countryOfOrigin") ?: "JP",
            isAdult = m.bool("isAdult"),
            studios = studios,
            trailer = m.optJSONObject("trailer")?.let {
                val tid = it.text("id") ?: return@let null
                Trailer(tid, it.text("site") ?: "", it.text("thumbnail"))
            },
            episodeMeta = streamingEpisodes(m.optJSONArray("streamingEpisodes")),
        )
    }

    /**
     * The episodes AniList itself lists (its streaming partners' episodes): titles and
     * thumbnails, numbered by their place in the list, as the site shows them.
     */
    fun streamingEpisodes(a: JSONArray?): List<EpisodeMeta> {
        val arr = a ?: return emptyList()
        val out = ArrayList<EpisodeMeta>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val number = (i + 1).toDouble()
            out += EpisodeMeta(
                number = number,
                title = o.text("title")?.let { decodeEntities(it) },
                image = o.text("thumbnail"),
            )
        }
        return out
    }

    fun relations(o: JSONObject?): List<Relation> {
        val edges = o?.optJSONObject("relations")?.optJSONArray("edges") ?: return emptyList()
        return edges.mapList { edge ->
            val node = edge.optJSONObject("node") ?: return@mapList null
            // A relation may be a manga or a character: only another anime is a season or a story.
            if (node.text("type")?.let { it != "ANIME" && it.isNotEmpty() } == true) return@mapList null
            val id = node.int("id") ?: return@mapList null
            Relation(
                type = RelationType.of(edge.text("relationType")),
                id = id,
                title = title(node.optJSONObject("title")),
                cover = cover(node.optJSONObject("coverImage")),
                format = MediaFormat.of(node.text("format")),
                status = AiringStatus.of(node.text("status")),
                year = node.int("seasonYear"),
                season = Season.of(node.text("season")),
                episodes = node.int("episodes"),
            )
        }
    }

    /** The recommendations of an anime page, as light rows. */
    fun recommendations(o: JSONObject?): List<Anime> {
        val nodes = o?.optJSONObject("recommendations")?.optJSONArray("nodes") ?: return emptyList()
        return nodes.mapList { n -> media(n.optJSONObject("mediaRecommendation")) }
    }

    /** One link of a relation, as a row of the season sheet. */
    fun seasonEntry(r: Relation, relationLabel: String) = SeasonEntry(
        id = r.id, title = r.title, cover = r.cover, format = r.format, status = r.status,
        season = r.season, year = r.year, episodes = r.episodes, relation = relationLabel,
    )

    /** The characters of an anime page, in the order the site gives them. */
    fun characters(media: JSONObject?): List<Character> {
        val edges = media?.optJSONObject("characters")?.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<Character>()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val node = edge.optJSONObject("node") ?: continue
            val id = node.int("id") ?: continue
            val name = node.optJSONObject("name")
            out += Character(
                id = id,
                name = decodeEntities(name?.text("full") ?: name?.text("native") ?: ""),
                image = cover(node.optJSONObject("image")),
                role = edge.text("role")?.lowercase()?.replaceFirstChar { it.titlecase() } ?: "",
            )
        }
        return out
    }

    fun viewer(o: JSONObject?): AccountUser? {
        val v = o ?: return null
        val id = v.int("id") ?: return null
        val stats = v.optJSONObject("statistics")?.optJSONObject("anime")
        return AccountUser(
            id = id,
            name = v.text("name") ?: "user",
            avatar = cover(v.optJSONObject("avatar")),
            banner = v.text("bannerImage"),
            siteUrl = v.text("siteUrl") ?: "https://anilist.co/user/$id",
            episodesWatched = stats?.int("episodesWatched") ?: 0,
            minutesWatched = stats?.long("minutesWatched") ?: 0L,
            meanScore = stats?.optDouble("meanScore", 0.0) ?: 0.0,
            animeMeanScore = stats?.optDouble("meanScore", 0.0) ?: 0.0,
        )
    }

    /** One entry of the user's list on the site, as the phone keeps it. */
    fun listEntry(o: JSONObject?): LibraryEntry? {
        val l = o ?: return null
        val media = l.optJSONObject("media") ?: return null
        val animeId = media.int("id") ?: return null
        val status = ListStatus.of(l.text("status")) ?: ListStatus.WATCHING
        val remoteScore = l.optDouble("score", 0.0)
        return LibraryEntry(
            animeId = animeId,
            title = title(media.optJSONObject("title")).display,
            cover = cover(media.optJSONObject("coverImage")),
            status = status,
            score = LibraryEntry.stars(remoteScore.toInt()),
            progress = l.int("progress") ?: 0,
            totalEpisodes = media.int("episodes"),
            updatedAt = (l.long("updatedAt") ?: 0L) * 1000L,
            notes = l.text("notes"),
            remoteId = l.int("id"),
            dirty = false,
        )
    }

    /** The favourites of the account, as the phone keeps them. */
    fun favourites(nodes: JSONArray?): List<Favourite> {
        val arr = nodes ?: return emptyList()
        return arr.mapList { o ->
            val id = o.int("id") ?: return@mapList null
            Favourite(
                animeId = id,
                title = title(o.optJSONObject("title")).display,
                cover = cover(o.optJSONObject("coverImage")),
                addedAt = System.currentTimeMillis(),
                remote = true,
            )
        }
    }

    /** A page's items, whatever the node inside it is called. */
    fun <T> page(data: JSONObject?, node: String, read: (JSONObject) -> T?): Page<T> {
        val p = data?.optJSONObject("Page") ?: return Page(emptyList(), 1, false)
        val info = p.optJSONObject("pageInfo")
        val items = p.optJSONArray(node)?.mapList { read(it) } ?: emptyList()
        return Page(
            items = items,
            page = info?.int("currentPage") ?: 1,
            hasNext = info?.bool("hasNextPage") ?: false,
        )
    }

}
