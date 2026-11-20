package app.kuroka.sources.anilist

/*
 * The GraphQL AniList answers: the queries Kuroka sends, in one place, so a field the app starts
 * using is added once. AniList's schema is public (https://docs.anilist.co) and needs no key for
 * reading; the account's own list and favourites use the same queries with a token.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object AniListQueries {

    const val ENDPOINT = "https://graphql.anilist.co"
    const val AUTHORIZE = "https://anilist.co/api/v2/oauth/authorize"
    const val TOKEN = "https://anilist.co/api/v2/oauth/token"

    /** What a card, a row and a page all read: one media node. */
    const val MEDIA_FRAGMENT = """
fragment kurokaMedia on Media {
  id
  idMal
  type
  format
  status(version: 2)
  season
  seasonYear
  episodes
  duration
  countryOfOrigin
  isAdult
  genres
  synonyms
  tags { name isMediaSpoiler }
  startDate { year month day }
  endDate { year month day }
  title { romaji english native userPreferred }
  coverImage { extraLarge large medium color }
  bannerImage
  description(asHtml: false)
  averageScore
  meanScore
  popularity
  favourites
  nextAiringEpisode { episode airingAt timeUntilAiring }
  trailer { id site thumbnail }
  studios(isMain: true) { nodes { name } }
  streamingEpisodes { title thumbnail url site }
}
"""

    const val PAGE_INFO = """
    pageInfo { total currentPage lastPage hasNextPage perPage }
"""

    /** A search, with every filter the search sheet offers. */
    const val SEARCH = MEDIA_FRAGMENT + """
query (
  ${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}sort: [MediaSort],
  ${'$'}genre_in: [String], ${'$'}genre_not_in: [String], ${'$'}format: MediaFormat,
  ${'$'}status: MediaStatus, ${'$'}season: MediaSeason, ${'$'}seasonYear: Int,
  ${'$'}startDate_greater: FuzzyDateInt, ${'$'}startDate_lesser: FuzzyDateInt,
  ${'$'}isAdult: Boolean, ${'$'}type: MediaType, ${'$'}episodes_greater: Int
) {
  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
""" + PAGE_INFO + """
    media(
      type: ${'$'}type, search: ${'$'}search, sort: ${'$'}sort, genre_in: ${'$'}genre_in,
      genre_not_in: ${'$'}genre_not_in, format: ${'$'}format, status: ${'$'}status,
      season: ${'$'}season, seasonYear: ${'$'}seasonYear, startDate_greater: ${'$'}startDate_greater,
      startDate_lesser: ${'$'}startDate_lesser, isAdult: ${'$'}isAdult, episodes_greater: ${'$'}episodes_greater
    ) { ...kurokaMedia }
  }
}
"""

    /** The home page's rows: trending, popular, this season, airing soon, top rated. */
    const val PAGE_ROW = MEDIA_FRAGMENT + """
query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}season: MediaSeason, ${'$'}seasonYear: Int, ${'$'}isAdult: Boolean, ${'$'}airingAt_greater: Int, ${'$'}airingAt_lesser: Int) {
  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
""" + PAGE_INFO + """
    media(type: ANIME, sort: ${'$'}sort, season: ${'$'}season, seasonYear: ${'$'}seasonYear, isAdult: ${'$'}isAdult, airingAt_greater: ${'$'}airingAt_greater, airingAt_lesser: ${'$'}airingAt_lesser, status_in: [RELEASING, FINISHED, NOT_YET_RELEASED, HIATUS]) { ...kurokaMedia }
  }
}
"""

    /** One anime, everything its page shows. */
    const val DETAILS = MEDIA_FRAGMENT + """
query (${'$'}id: Int) {
  Media(id: ${'$'}id, type: ANIME) {
    ...kurokaMedia
    relations {
      edges {
        relationType(version: 2)
        node {
          ... on Media {
            id type format status(version: 2) season seasonYear episodes
            title { romaji english native userPreferred }
            coverImage { large medium }
          }
        }
      }
    }
    recommendations(sort: RATING_DESC, perPage: 16) {
      nodes {
        mediaRecommendation {
          id format status(version: 2) seasonYear episodes
          title { romaji english native userPreferred }
          coverImage { large medium }
        }
      }
    }
    characters(sort: ROLE, perPage: 12) {
      nodes { id name { full native } image { large medium } }
      edges { role }
    }
    staff(sort: LANGUAGE, perPage: 8) { nodes { id name { full } languageV2 } }
    stats { scoreDistribution { score amount } statusDistribution { status amount } }
  }
}
"""

    /** The relations alone, for the season sheet (a lighter call than the whole page). */
    const val RELATIONS = """
query (${'$'}id: Int) {
  Media(id: ${'$'}id, type: ANIME) {
    id
    relations {
      edges {
        relationType(version: 2)
        node {
          ... on Media {
            id type format status(version: 2) season seasonYear episodes
            title { romaji english native userPreferred }
            coverImage { large medium }
          }
        }
      }
    }
  }
}
"""

    const val RECOMMENDATIONS = """
query (${'$'}id: Int) {
  Media(id: ${'$'}id, type: ANIME) {
    recommendations(sort: RATING_DESC, perPage: 20) {
      nodes {
        mediaRecommendation {
          id format status(version: 2) seasonYear episodes averageScore
          title { romaji english native userPreferred }
          coverImage { large medium }
        }
      }
    }
  }
}
"""

    /** The genres, for the filter sheet (asked once, kept for the day). */
    const val GENRES = """
query { GenreCollection }
"""

    /** The viewer: who signed in, and what they have watched. */
    const val VIEWER = """
query {
  Viewer {
    id name avatar { large medium } bannerImage siteUrl
    options { titleLanguage displayAdultContent }
    statistics {
      anime { count episodesWatched minutesWatched meanScore }
    }
    mediaListOptions { scoreFormat }
  }
}
"""

    /** An entry of the user's list, saved (status, stars, episodes watched). */
    const val SAVE_ENTRY = """
mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}score: Float, ${'$'}progress: Int, ${'$'}notes: String) {
  SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, score: ${'$'}score, progress: ${'$'}progress, notes: ${'$'}notes) {
    id status score progress
  }
}
"""

    const val DELETE_ENTRY = """
mutation (${'$'}id: Int) { DeleteMediaListEntry(id: ${'$'}id) { deleted } }
"""

    const val TOGGLE_FAVOURITE = """
mutation (${'$'}animeId: Int) { ToggleFavourite(animeId: ${'$'}animeId) { anime { nodes { id } } } }
"""

    /** The user's whole anime list, in one call per status. */
    const val MEDIA_LIST = """
query (${'$'}userId: Int, ${'$'}status: MediaListStatus, ${'$'}perPage: Int, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
""" + PAGE_INFO + """
    mediaList(userId: ${'$'}userId, status: ${'$'}status, sort: UPDATED_TIME_DESC, type: ANIME) {
      id status score progress notes updatedAt startedAt { year month day } completedAt { year month day }
      media {
        id idMal episodes format seasonYear title { romaji english native userPreferred }
        coverImage { large medium }
      }
    }
  }
}
"""

    const val FAVOURITES = """
query (${'$'}userId: Int, ${'$'}perPage: Int, ${'$'}page: Int) {
  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
""" + PAGE_INFO + """
    favourites(userId: ${'$'}userId) {
      anime { nodes { id title { romaji english native userPreferred } coverImage { large medium } } }
    }
  }
}
"""

    /** Is this anime on the user's list, and is it a favourite? (an anime page's first call). */
    const val MY_STATE = """
query (${'$'}mediaId: Int, ${'$'}userId: Int) {
  MediaList(mediaId: ${'$'}mediaId, userId: ${'$'}userId) { id status score progress }
}
"""

    const val IS_FAVOURITE = """
query (${'$'}mediaId: Int) { Media(id: ${'$'}mediaId) { isFavourite } }
"""
}
