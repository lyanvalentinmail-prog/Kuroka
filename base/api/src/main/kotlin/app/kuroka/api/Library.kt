package app.kuroka.api

/*
 * Your library: the list an anime is on (with its stars), how far an episode got, the favourites
 * and the downloads. Kept on the phone, and pushed to the account when there is one.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** The list an anime is on, as AniList names them (so a signed-in account syncs without a map). */
enum class ListStatus(val code: String, val label: String) {
    WATCHING("CURRENT", "Watching"),
    PLANNED("PLANNING", "Planning"),
    COMPLETED("COMPLETED", "Completed"),
    DROPPED("DROPPED", "Dropped"),
    PAUSED("PAUSED", "Paused"),
    REPEATING("REPEATING", "Rewatching");

    companion object {
        fun of(code: String?): ListStatus? = entries.firstOrNull { it.code == code }
        /** The lists the library screen shows as its tabs, in order. */
        val tabs = listOf(WATCHING, PLANNED, COMPLETED, DROPPED, PAUSED)
    }
}

/**
 * One anime of the library: its list, the stars given it (0 = none), the episodes watched, and
 * where the last one stopped. [dirty] marks an entry the account has not heard about yet.
 */
data class LibraryEntry(
    val animeId: Int,
    val title: String,
    val cover: String? = null,
    val status: ListStatus = ListStatus.WATCHING,
    /** The stars, 0 to 5; 0 is "not rated". AniList takes 0-100, so a star is 20 points. */
    val score: Int = 0,
    val progress: Int = 0,
    val totalEpisodes: Int? = null,
    val source: String? = null,
    val audio: Audio = Audio.SUB,
    val notes: String? = null,
    val updatedAt: Long = 0L,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val remoteId: Int? = null,
    val dirty: Boolean = false,
) {
    /** The stars as AniList counts them (1-100), or 0 for "no score". */
    val remoteScore: Int get() = if (score <= 0) 0 else (score * 20).coerceIn(0, 100)

    /** Stars from an AniList score (1-100 or 1-10, both used by the site). */
    companion object {
        fun stars(remoteScore: Int): Int = when {
            remoteScore <= 0 -> 0
            remoteScore <= 5 -> remoteScore
            else -> (remoteScore / 20).coerceIn(1, 5)
        }
    }
}

/** How far one episode got, so the app can resume it and the list can count it watched. */
data class WatchProgress(
    val animeId: Int,
    val title: String,
    val cover: String? = null,
    val episodeImage: String? = null,
    val episodeNumber: Double,
    val episodeTitle: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val source: String = "",
    val audio: Audio = Audio.SUB,
    val updatedAt: Long = 0L,
    /** True once almost all of the episode has been seen (the list counts it as watched). */
    val completed: Boolean = false,
) {
    /** 0f to 1f, for the thin bar under a card. */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** "12:34 / 24:10". */
    val positionLabel: String get() = "${clock(positionMs)} / ${clock(durationMs)}"

    val episodeLabel: String
        get() = "Episode ${if (episodeNumber % 1.0 == 0.0) episodeNumber.toInt().toString() else episodeNumber.toString()}"
}

/** mm:ss, or h:mm:ss past an hour. */
fun clock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val mm = if (h > 0) m.toString().padStart(2, '0') else m.toString()
    return if (h > 0) "$h:$mm:${s.toString().padStart(2, '0')}" else "$mm:${s.toString().padStart(2, '0')}"
}

/** An anime marked as a favourite (the heart on its page). */
data class Favourite(
    val animeId: Int,
    val title: String,
    val cover: String? = null,
    val addedAt: Long = 0L,
    /** True when the account knows it too (a local heart is kept even signed out). */
    val remote: Boolean = false,
)

/** Where a download is in its life. */
enum class DownloadState {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, REMOVING;

    val isActive: Boolean get() = this == QUEUED || this == DOWNLOADING
}

/**
 * One episode kept on the phone. The stream is downloaded through Media3's offline support, so
 * an HLS playlist becomes the segments of one file under [id]; [localUri] is the exported copy in
 * the device's own Movies folder when the user asked for one.
 */
data class DownloadItem(
    val id: String,
    val animeId: Int,
    val episodeNumber: Double,
    val audio: Audio = Audio.SUB,
    val title: String,
    val episodeTitle: String? = null,
    val cover: String? = null,
    val episodeImage: String? = null,
    val uri: String,
    val source: String = "",
    val server: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val failure: String? = null,
    val addedAt: Long = 0L,
    val completedAt: Long? = null,
    val localUri: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    /** 0f to 1f, for the bar of a download in progress. */
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)

    /** "312 MB of 1.2 GB", or "312 MB" when the size is not known yet. */
    val sizeLabel: String
        get() = if (bytesTotal > 0L) "${size(bytesDone)} of ${size(bytesTotal)}" else size(bytesDone)

    val episodeLabel: String
        get() = "Episode ${if (episodeNumber % 1.0 == 0.0) episodeNumber.toInt().toString() else episodeNumber.toString()}"

    companion object {
        fun size(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
