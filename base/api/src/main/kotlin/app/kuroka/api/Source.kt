package app.kuroka.api

/*
 * The contract a source of streams answers: the episodes of an anime and the streams of one of
 * them. A provider implements it; the resolver asks the enabled ones in the user's order.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/**
 * Where episodes come from. Kuroka hosts nothing: a source is a site's own API or pages, read as
 * its author publishes them. Use only the sources you have the right to use.
 */
interface AnimeSource {
    /** A short id, kept in the settings ("senshi"). */
    val id: String

    /** What the settings and the server sheet show ("Senshi"). */
    val name: String

    /** One line saying what the source is and what it has (Settings, Sources). */
    val note: String get() = ""

    /** The subtitle languages it serves, as short codes ("en", "es", "ar"). */
    val subtitleLanguages: List<String> get() = listOf("en")

    /** True when it can give a dubbed audio (else the SUB/DUB switch hides it). */
    val hasDub: Boolean get() = false

    /** True when a fresh install asks this source (the user can turn it off). */
    val enabledByDefault: Boolean get() = true

    /**
     * True when the source can answer at all: a source that needs an address or a key the user
     * has not given yet is not ready, and the settings say what is missing.
     */
    val isReady: Boolean get() = true

    /**
     * The episodes of [anime], or null when this source does not have it. Throwing is allowed:
     * the resolver counts it as a failure of this source and asks the next one.
     */
    suspend fun episodes(anime: AnimeRef): EpisodeBundle?

    /**
     * The streams of one episode, or null when this source has nothing for it. [episodeId] is the
     * id the source's own episode list gave (a source that serves several sites needs it).
     */
    suspend fun watch(anime: AnimeRef, audio: Audio, episode: Double, episodeId: String? = null): WatchResult?
}
