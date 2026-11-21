package app.kuroka.api

/*
 * The anime itself, as the catalogs describe it (AniList is the one Kuroka ships).
 * Every field a provider or a page may need, so the UI never asks a catalog twice.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** The audio of an episode: its original language with subtitles, or a dub. */
enum class Audio(val code: String) {
    SUB("sub"), DUB("dub");

    companion object {
        fun of(code: String?): Audio = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SUB
    }
}

/** What kind of release an anime is (AniList's `format`). */
enum class MediaFormat(val code: String, val label: String) {
    TV("TV", "TV"),
    TV_SHORT("TV_SHORT", "TV Short"),
    MOVIE("MOVIE", "Movie"),
    SPECIAL("SPECIAL", "Special"),
    OVA("OVA", "OVA"),
    ONA("ONA", "ONA"),
    MUSIC("MUSIC", "Music"),
    UNKNOWN("", "—");

    companion object {
        fun of(code: String?): MediaFormat = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Whether an anime is airing now, finished, or not started yet. */
enum class AiringStatus(val code: String, val label: String) {
    RELEASING("RELEASING", "Airing"),
    FINISHED("FINISHED", "Finished"),
    NOT_YET_RELEASED("NOT_YET_RELEASED", "Not yet aired"),
    CANCELLED("CANCELLED", "Cancelled"),
    HIATUS("HIATUS", "Hiatus"),
    UNKNOWN("", "—");

    companion object {
        fun of(code: String?): AiringStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** The broadcast season (AniList's `season`). */
enum class Season(val code: String, val label: String) {
    WINTER("WINTER", "Winter"),
    SPRING("SPRING", "Spring"),
    SUMMER("SUMMER", "Summer"),
    FALL("FALL", "Fall"),
    UNKNOWN("", "—");

    companion object {
        fun of(code: String?): Season = entries.firstOrNull { it.code == code } ?: UNKNOWN

        /** The season [month] (1-12) falls in, in the northern hemisphere, as the catalogs count it. */
        fun ofMonth(month: Int): Season = when (month) {
            12, 1, 2 -> WINTER
            3, 4, 5 -> SPRING
            6, 7, 8 -> SUMMER
            else -> FALL
        }
    }
}

/** A date the catalog knows only partly ("2024", "2024-04", "2024-04-07"). */
data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null) {
    val isEmpty: Boolean get() = year == null && month == null && day == null

    /** "7 Apr 2024", "Apr 2024" or "2024"; null when nothing is known. */
    fun display(): String? {
        if (year == null && month == null) return null
        val m = month?.let { MONTHS.getOrNull(it - 1) }
        return listOfNotNull(day, m, year).joinToString(" ")
    }

    companion object {
        val MONTHS = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
    }
}

/** The titles of an anime in the languages the catalog has. */
data class Title(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
) {
    /** What the app shows: the user's own choice of the catalog, else the first title there is. */
    val display: String
        get() = firstNotBlank(userPreferred, english, romaji, native) ?: "—"

    /** The romaji title providers match on (their own sites are romanised). */
    val primary: String
        get() = firstNotBlank(romaji, english, native, userPreferred) ?: ""

    /** Every title, for a provider to try one after another. */
    val all: List<String>
        get() = listOfNotNull(english, romaji, native, userPreferred).distinct()

    private fun firstNotBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}

/** The next episode of an airing anime, and when it comes. */
data class AiringEpisode(val episode: Int, val airingAt: Long, val timeUntilAiring: Long)

/** A trailer the catalog links to (YouTube, Dailymotion). */
data class Trailer(val id: String, val site: String, val thumbnail: String?) {
    /** The page to open in a browser; null for a site Kuroka does not know. */
    val url: String?
        get() = when (site.lowercase()) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
}

/** An episode as the catalog describes it (title, thumbnail, whether it is a filler). */
data class EpisodeMeta(
    val number: Double,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val duration: Int? = null,
    val filler: Boolean = false,
    val recap: Boolean = false,
)

/**
 * One face of an anime's cast, as the catalog gives it: the name to show, the portrait, and the
 * part it plays ("Main", "Supporting", "Background").
 */
data class Character(
    val id: Int,
    val name: String = "",
    val image: String? = null,
    val role: String = "",
) {
    /** What a cast row shows: the name, or a stand-in when the site gave none. */
    val label: String get() = name.ifBlank { "Character $id" }
}

/**
 * One anime, everything a page shows: the poster and the banner, what it is about, how it is
 * doing (score, popularity, airing), and the episode list and cast the catalog itself has.
 */
data class Anime(
    val id: Int,
    val idMal: Int? = null,
    val title: Title = Title(),
    val cover: String? = null,
    val banner: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val format: MediaFormat = MediaFormat.UNKNOWN,
    val status: AiringStatus = AiringStatus.UNKNOWN,
    val season: Season = Season.UNKNOWN,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val nextAiring: AiringEpisode? = null,
    val synonyms: List<String> = emptyList(),
    val country: String = "JP",
    val isAdult: Boolean = false,
    val studios: List<String> = emptyList(),
    val trailer: Trailer? = null,
    val episodeMeta: List<EpisodeMeta> = emptyList(),
    val characters: List<Character> = emptyList(),
) {
    /** What a provider needs to find this anime on its own site. */
    fun toRef() = AnimeRef(
        id = id, idMal = idMal, title = title, year = seasonYear ?: startDate?.year,
        format = format, synonyms = synonyms, episodes = episodes, episodeMeta = episodeMeta,
    )

    /** "TV · 24 ep · 2024", the one line a card shows under its title. */
    val summaryLine: String
        get() = listOfNotNull(
            format.label.takeIf { format != MediaFormat.UNKNOWN },
            status.label.takeIf { status == AiringStatus.RELEASING },
            episodes?.let { if (it > 0) "$it ep" else null },
            (seasonYear ?: startDate?.year)?.toString(),
        ).joinToString(" · ")

    /** The season this anime belongs to, as a label ("Spring 2024"). */
    val seasonLabel: String?
        get() = if (season == Season.UNKNOWN || seasonYear == null) null else "${season.label} $seasonYear"
}

/** The identity of an anime a provider searches for: its ids, titles and shape. */
data class AnimeRef(
    val id: Int,
    val idMal: Int? = null,
    val title: Title = Title(),
    val year: Int? = null,
    val format: MediaFormat = MediaFormat.UNKNOWN,
    val synonyms: List<String> = emptyList(),
    val episodes: Int? = null,
    val episodeMeta: List<EpisodeMeta> = emptyList(),
    /** The other titles the episode guide knows (a provider may list the anime under one of them). */
    val guideTitles: List<String> = emptyList(),
) {
    /** The metadata the catalog has of one episode (its title, thumbnail, filler mark). */
    fun meta(number: Double): EpisodeMeta? = episodeMeta.firstOrNull { it.number == number }

    /** The same identity with the episode guide's answers folded in. */
    fun withGuide(malId: Int? = null, titles: List<String> = emptyList(), meta: List<EpisodeMeta> = emptyList()) =
        copy(
            idMal = this.idMal ?: malId,
            guideTitles = (guideTitles + titles).distinct(),
            episodeMeta = if (meta.isNotEmpty()) meta else episodeMeta,
        )
}

/** How two anime are related; the season sheet walks the SEQUEL/PREQUEL chain. */
enum class RelationType(val code: String, val label: String) {
    SEQUEL("SEQUEL", "Sequel"),
    PREQUEL("PREQUEL", "Prequel"),
    SIDE_STORY("SIDE_STORY", "Side story"),
    PARENT("PARENT", "Parent story"),
    SPIN_OFF("SPIN_OFF", "Spin-off"),
    ALTERNATIVE("ALTERNATIVE", "Alternative"),
    SUMMARY("SUMMARY", "Summary"),
    CHARACTER("CHARACTER", "Character"),
    OTHER("OTHER", "Other");

    companion object {
        fun of(code: String?): RelationType = entries.firstOrNull { it.code == code } ?: OTHER
    }

    /** Whether the relation is another telling of the same story (a season, in the sheet). */
    val isSeason: Boolean get() = this == SEQUEL || this == PREQUEL || this == SIDE_STORY || this == SPIN_OFF
}

/** One link of a relation: an anime and how it hangs off the one asked about. */
data class Relation(
    val type: RelationType,
    val id: Int,
    val title: Title,
    val cover: String?,
    val format: MediaFormat,
    val status: AiringStatus,
    val year: Int?,
    val season: Season,
    val episodes: Int?,
)

/** One row of the season sheet: a season of the same story, in order. */
data class SeasonEntry(
    val id: Int,
    val title: Title,
    val cover: String?,
    val format: MediaFormat,
    val status: AiringStatus,
    val season: Season,
    val year: Int?,
    val episodes: Int?,
    /** What links it to the anime the sheet was opened from ("Sequel", "Prequel", "This season"). */
    val relation: String,
)

/** The seasons of one story, oldest first, with the one being watched. */
data class Seasons(val entries: List<SeasonEntry>, val currentId: Int) {
    val current: SeasonEntry? get() = entries.firstOrNull { it.id == currentId }
    val hasSeveral: Boolean get() = entries.size > 1
}
