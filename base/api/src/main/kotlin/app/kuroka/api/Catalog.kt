package app.kuroka.api

/*
 * The contracts the UI talks to: a catalog that describes anime (AniList), the sources that
 * stream them, and the account they sync to. The UI knows these three and nothing else.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

import kotlinx.coroutines.flow.StateFlow

/** One page of a list the catalog gives, and whether there is another one after it. */
data class Page<T>(val items: List<T>, val page: Int = 1, val hasNext: Boolean = false) {
    companion object { fun <T> empty() = Page<T>(emptyList(), 1, false) }
}

/** How a search sorts its answers (AniList's own sort names). */
enum class Sort(val code: String, val label: String) {
    TRENDING("TRENDING_DESC", "Trending"),
    POPULARITY("POPULARITY_DESC", "Most popular"),
    SCORE("SCORE_DESC", "Highest rated"),
    FAVOURITES("FAVOURITES_DESC", "Most favoured"),
    NEWEST("START_DATE_DESC", "Newest"),
    OLDEST("START_DATE_ASC", "Oldest"),
    TITLE("TITLE_ROMAJI", "Name A-Z"),
    UPDATED("UPDATED_AT_DESC", "Recently updated");
}

/** What a search narrows down to; the filter sheet of the search screen edits one of these. */
data class SearchFilters(
    val query: String = "",
    val genres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val format: MediaFormat? = null,
    val status: AiringStatus? = null,
    val season: Season? = null,
    val year: Int? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val audio: Audio? = null,
    val sort: Sort = Sort.POPULARITY,
    val page: Int = 1,
    val perPage: Int = 24,
) {
    /** The same filters on another page. */
    fun at(page: Int) = copy(page = page)

    /** True when anything but the query narrows the search down (the sheet shows a dot). */
    val narrowed: Boolean
        get() = genres.isNotEmpty() || excludedGenres.isNotEmpty() || format != null || status != null ||
            season != null || year != null || yearFrom != null || yearTo != null || audio != null ||
            sort != Sort.POPULARITY
}

/**
 * The catalog: what anime there are and what they are about. Kuroka ships AniList; the interface
 * is here so another one can answer it too.
 */
interface Catalog {
    /** The name shown in Settings, Sources ("AniList"). */
    val name: String

    suspend fun search(filters: SearchFilters): Page<Anime>
    suspend fun trending(page: Int = 1, perPage: Int = 20): Page<Anime>
    suspend fun popular(page: Int = 1, perPage: Int = 20): Page<Anime>
    suspend fun seasonal(year: Int, season: Season, page: Int = 1, perPage: Int = 20): Page<Anime>
    /** What is airing soon, soonest first (the schedule page). */
    suspend fun airingNext(page: Int = 1, perPage: Int = 20): Page<Anime>
    /** One anime, with its episode titles and thumbnails when the catalog has them. */
    suspend fun details(id: Int): Anime?
    /** The anime related to [id] (the seasons, the side stories, the summaries). */
    suspend fun relations(id: Int): List<Relation>
    /** The season chain [id] belongs to, oldest first (the season sheet). */
    suspend fun seasons(id: Int): Seasons
    /** What to watch after [id]. */
    suspend fun recommendations(id: Int): List<Anime>
    /** Every genre the catalog knows, for the filter sheet. */
    suspend fun genres(): List<String>
    /** The year the newest anime of the catalog is from (the year filter's upper bound). */
    fun currentYear(): Int
}

/** One source of streams, and whether the registry can ask it right now. */
data class SourceState(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val hasDub: Boolean,
    val languages: List<String>,
    /** The order the resolver asks them in (0 first). */
    val order: Int,
    /** True after enough failures in a row: skipped for a while, so a page still loads. */
    val down: Boolean = false,
    /** What the source says about itself in Settings, Sources. */
    val note: String = "",
)

/**
 * Finds the episodes and the streams of an anime, asking the enabled sources in the user's order
 * (like Orpheon's source order: the first one that answers wins).
 */
interface EpisodeResolver {
    val sources: StateFlow<List<SourceState>>

    /** The episodes of [anime], from the first source that has them. */
    suspend fun episodes(anime: AnimeRef): EpisodeBundle?

    /** The streams of one episode; [preferServer] asks a server the user picked by hand. */
    suspend fun watch(
        anime: AnimeRef,
        audio: Audio,
        episode: Double,
        episodeId: String? = null,
        preferServer: String? = null,
    ): WatchResult?

    /** The episode list of every enabled source, for the sheet that switches between them. */
    suspend fun episodesEverywhere(anime: AnimeRef): List<EpisodeBundle>

    fun reorder(ids: List<String>)
    fun setEnabled(id: String, enabled: Boolean)

    /** Forgets a source's failure count (Settings, Sources, "Try again"). */
    fun reset(id: String)
}
