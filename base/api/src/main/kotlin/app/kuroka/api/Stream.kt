package app.kuroka.api

/*
 * What plays: the episodes a provider lists, and the streams, subtitles and skip times it gives
 * for one of them. This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** One episode, as a provider lists it (its own id, so the watch call can ask for it back). */
data class Episode(
    val id: String,
    val number: Double,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val duration: Int? = null,
    val audio: Audio = Audio.SUB,
    val filler: Boolean = false,
    val recap: Boolean = false,
    val source: String = "",
) {
    /** "12", or "13.5" for the half episodes some anime have. */
    val numberLabel: String
        get() = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

    /** What a list row says: the title when the provider has one, else "Episode 12". */
    fun label(fallback: String = "Episode"): String =
        title?.takeIf { it.isNotBlank() } ?: "$fallback $numberLabel"
}

/** The episodes of one anime from one provider, by audio. */
data class EpisodeBundle(
    val animeId: Int,
    val malId: Int? = null,
    val source: String,
    val title: String? = null,
    val sub: List<Episode> = emptyList(),
    val dub: List<Episode> = emptyList(),
) {
    /** The audios this provider has for this anime (the SUB/DUB switch shows these). */
    val audios: List<Audio>
        get() = listOf(Audio.SUB, Audio.DUB).filter { episodes(it).isNotEmpty() }

    fun episodes(audio: Audio): List<Episode> = if (audio == Audio.DUB) dub else sub

    fun episode(audio: Audio, number: Double): Episode? =
        episodes(audio).firstOrNull { it.number == number }

    /** The episode after [number] in this audio, or the first of the other audio when it ends. */
    fun next(number: Double, audio: Audio): Episode? {
        val list = episodes(audio).sortedBy { it.number }
        return list.firstOrNull { it.number > number }
    }

    fun previous(number: Double, audio: Audio): Episode? {
        val list = episodes(audio).sortedBy { it.number }
        return list.lastOrNull { it.number < number }
    }

    val isEmpty: Boolean get() = sub.isEmpty() && dub.isEmpty()
}

/** The container of a stream: an HLS playlist, a DASH manifest, one file, or a page to open. */
enum class StreamType(val code: String) {
    HLS("hls"), DASH("dash"), MP4("mp4"), EMBED("embed"), UNKNOWN("unknown");

    companion object {
        /** From a provider's own word, or from the URL when it says nothing. */
        fun of(code: String?, url: String): StreamType {
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { return it }
            val u = url.substringBefore('?').lowercase()
            return when {
                u.endsWith(".m3u8") || u.contains(".m3u8") -> HLS
                u.endsWith(".mpd") -> DASH
                u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".webm") -> MP4
                else -> UNKNOWN
            }
        }
    }
}

/** The format of a subtitle file: what ExoPlayer needs as its MIME type. */
enum class SubtitleFormat(val mime: String) {
    VTT("text/vtt"),
    SRT("application/x-subrip"),
    ASS("application/x-ssa"),
    UNKNOWN("text/vtt");

    companion object {
        /** From the file name (most providers serve .vtt, .srt or .ass). */
        fun of(url: String): SubtitleFormat {
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            return when (ext) {
                "vtt" -> VTT
                "srt" -> SRT
                "ass", "ssa" -> ASS
                else -> UNKNOWN
            }
        }
    }
}

/** One subtitle track: where it is, what it says and in which language. */
data class SubtitleTrack(
    val url: String,
    val label: String,
    val language: String = "",
    val isDefault: Boolean = false,
    val format: SubtitleFormat = SubtitleFormat.UNKNOWN,
    /** The headers the track's host wants (a referer as a rule). */
    val headers: Map<String, String> = emptyMap(),
) {
    /** What the sheet shows: "English", or the language code when there is no label. */
    val name: String get() = label.ifBlank { language.ifBlank { url.substringAfterLast('/') } }

    companion object {
        fun of(url: String, label: String?, language: String?, isDefault: Boolean = false,
               headers: Map<String, String> = emptyMap()) = SubtitleTrack(
            url = url,
            label = label ?: language ?: "",
            language = language ?: label ?: "",
            isDefault = isDefault,
            format = SubtitleFormat.of(url),
            headers = headers,
        )
    }
}

/** One way of playing an episode: a stream, its server's name and what comes with it. */
data class StreamSource(
    val url: String,
    val type: StreamType = StreamType.UNKNOWN,
    val server: String = "",
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val priority: Int = 0,
)

/** A direct file a provider offers for a download (some do, next to the stream). */
data class DownloadLink(val url: String, val label: String = "Download", val headers: Map<String, String> = emptyMap())

/** The intro and outro of an episode, in seconds (AniSkip, or the provider's own marks). */
data class SkipTimes(
    val introStart: Double = 0.0,
    val introEnd: Double = 0.0,
    val outroStart: Double = 0.0,
    val outroEnd: Double = 0.0,
) {
    val hasIntro: Boolean get() = introEnd > introStart && introEnd > 0.0
    val hasOutro: Boolean get() = outroEnd > outroStart && outroStart > 0.0
    val hasAny: Boolean get() = hasIntro || hasOutro

    companion object { val None = SkipTimes() }
}

/** Everything the player needs for one episode: its streams, subtitles and skip times. */
data class WatchResult(
    val animeId: Int,
    val malId: Int? = null,
    val episode: Double,
    val audio: Audio = Audio.SUB,
    val streams: List<StreamSource> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val downloads: List<DownloadLink> = emptyList(),
    val skip: SkipTimes = SkipTimes.None,
    val headers: Map<String, String> = emptyMap(),
) {
    /** The stream to start with: the first one, providers order them by how good they are. */
    val best: StreamSource? get() = streams.firstOrNull()

    /** Every server the player can switch to (the sheets list their names). */
    val servers: List<String> get() = streams.map { it.server }.distinct().filter { it.isNotBlank() }

    /** All the subtitles, the stream's own first, without duplicates. */
    val allSubtitles: List<SubtitleTrack>
        get() {
            val out = ArrayList<SubtitleTrack>()
            streams.forEach { s -> s.subtitles.forEach { t -> if (out.none { it.url == t.url }) out += t } }
            subtitles.forEach { t -> if (out.none { it.url == t.url }) out += t }
            return out
        }
}
