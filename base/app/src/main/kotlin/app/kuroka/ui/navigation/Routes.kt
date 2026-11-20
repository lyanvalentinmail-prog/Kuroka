package app.kuroka.ui.navigation

/*
 * Where the app can be, as the navigation graph knows it: five tabs (home, search, library,
 * downloads, settings), an anime page, the player, and the pages a tab leads to (the account, the
 * sources, the schedule, a genre, the first-run setup).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    const val ACCOUNT = "account"
    const val SOURCES = "settings/sources"
    const val SCHEDULE = "schedule"
    const val ONBOARDING = "onboarding"
    const val UPDATES = "settings/updates"
    const val ABOUT = "settings/about"
    const val SUBTITLES = "settings/subtitles"
    const val DISPLAY = "settings/display"
    const val PLAYER_SETTINGS = "settings/player"
    const val DOWNLOAD_SETTINGS = "settings/downloads"
    const val STORAGE = "settings/storage"

    /** An anime page. */
    const val ANIME = "anime/{animeId}"
    fun anime(animeId: Int) = "anime/$animeId"

    /** A genre's page, as a search with one filter already on. */
    const val GENRE = "genre/{name}"
    fun genre(name: String) = "genre/${java.net.URLEncoder.encode(name, "UTF-8")}"

    /** The player: one anime, one episode, one audio; the rest comes from the episode itself. */
    const val WATCH = "watch/{animeId}/{number}/{audio}"
    const val playerPattern = WATCH

    /**
     * @param number the episode's number (13.5 for a half episode, as AniList counts them).
     * @param audio "sub" or "dub".
     * @param episodeId an episode id a source gave, so the player does not have to find it again.
     * @param server a server the user picked by hand.
     */
    fun watch(
        animeId: Int,
        number: Double,
        audio: String = "sub",
        episodeId: String? = null,
        server: String? = null,
        source: String? = null,
    ): String {
        val n = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
        val query = StringBuilder()
        episodeId?.let { query.append("&episodeId=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        server?.let { query.append("&server=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        source?.let { query.append("&source=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        return "watch/$animeId/$n/$audio" + (if (query.isEmpty()) "" else "?${query.substring(1)}")
    }

    /** The tabs the bottom bar shows, in order. */
    val tabs = listOf(HOME, SEARCH, LIBRARY, DOWNLOADS, SETTINGS)
}
