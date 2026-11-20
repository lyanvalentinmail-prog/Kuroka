package app.kuroka.di

import app.kuroka.api.Audio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The episode that is on the player right now, as the rest of the app knows it: the mini player
 * draws it, the bottom bar makes room for it, an anime page shows which of its episodes is
 * playing, and "go back to the player" from anywhere navigates by it.
 *
 * The player itself is the host's ([app.kuroka.host.playback.PlayerHost]); this is only what it is
 * playing, which the player screen publishes and clears.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** One episode on the player. */
data class NowEpisode(
    val animeId: Int,
    val title: String,
    val cover: String?,
    val banner: String?,
    val number: Double,
    val episodeTitle: String?,
    val audio: Audio,
    val source: String,
    val server: String,
    /** The total the episode list has, so the mini player can say "3 of 12". */
    val totalEpisodes: Int = 0,
    /** Where this episode started, so "go on watching" does not start it again. */
    val startPositionMs: Long = 0L,
)

object NowPlaying {

    private val _episode = MutableStateFlow<NowEpisode?>(null)
    /** What is on the player, or null when nothing is. */
    val episode: StateFlow<NowEpisode?> get() = _episode

    /** The player screen publishes the episode it loaded. */
    fun set(episode: NowEpisode?) {
        _episode.value = episode
    }

    /** The player screen went away and nothing is playing. */
    fun clear() {
        _episode.value = null
    }

    /** True when this anime is on the player (a page marks the episode that is). */
    fun isPlaying(animeId: Int): Boolean = _episode.value?.animeId == animeId
}
