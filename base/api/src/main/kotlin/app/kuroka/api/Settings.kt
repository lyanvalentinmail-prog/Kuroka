package app.kuroka.api

/*
 * The settings, as one tree the host stores and the UI edits. Plain data with defaults, so a
 * fresh install needs no file and an older one keeps what it does not know yet.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** The look of the app: Immersive (black, glass, Inter) or Classic (Spotify-like rows). */
enum class Look(val code: String, val label: String) {
    IMMERSIVE("immersive", "Immersive"),
    CLASSIC("classic", "Classic");

    companion object { fun of(code: String?): Look = entries.firstOrNull { it.code == code } ?: IMMERSIVE }
}

/** The accent colours Settings, Display offers, plus the wallpaper's own and a source's look. */
enum class Accent(val code: String, val argb: Long, val label: String) {
    CRIMSON("crimson", 0xFFF1316F, "Crimson"),
    EMBER("ember", 0xFFFF7A45, "Ember"),
    SUN("sun", 0xFFFFC53D, "Sun"),
    JADE("jade", 0xFF1DB954, "Jade"),
    LAGOON("lagoon", 0xFF22D3EE, "Lagoon"),
    INDIGO("indigo", 0xFF6C7BFF, "Indigo"),
    ORCHID("orchid", 0xFFB45CFF, "Orchid"),
    BLOSSOM("blossom", 0xFFFF4F7B, "Blossom"),
    MONO("mono", 0xFFE0E0E0, "Mono");

    companion object {
        val DEFAULT = CRIMSON
        fun of(code: String?): Accent = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** How a video fills the screen. */
enum class FitMode(val code: String, val label: String) {
    FIT("fit", "Fit"),
    FILL("fill", "Fill"),
    ZOOM("zoom", "Zoom"),
    STRETCH("stretch", "Stretch");

    companion object { fun of(code: String?): FitMode = entries.firstOrNull { it.code == code } ?: FIT }
}

/** What quality a stream starts at ("Auto", "1080", "720"…). */
enum class QualityPref(val code: String, val label: String, val maxHeight: Int) {
    AUTO("auto", "Auto", Int.MAX_VALUE),
    H2160("2160", "2160p", 2160),
    H1080("1080", "1080p", 1080),
    H720("720", "720p", 720),
    H480("480", "480p", 480),
    H360("360", "360p", 360),
    LOWEST("lowest", "Lowest", 0);

    companion object { fun of(code: String?): QualityPref = entries.firstOrNull { it.code == code } ?: AUTO }
}

/** Where downloads are kept. */
enum class DownloadLocation(val code: String, val label: String) {
    /** Inside the app's own folder: no permission, removed with the app. */
    APP("app", "In Kuroka"),
    /** The device's Movies/Kuroka folder, through MediaStore: kept when the app goes. */
    DEVICE("device", "On the device");

    companion object {
        fun of(code: String?): DownloadLocation = entries.firstOrNull { it.code == code } ?: APP
    }
}

/** The look of the subtitles the player draws. */
data class SubtitleStyle(
    /** Percent of the default size ExoPlayer uses. */
    val sizePercent: Int = 100,
    val textArgb: Long = 0xFFFFFFFF,
    /** 0 = no background box, 255 = opaque. */
    val backgroundAlpha: Int = 96,
    val backgroundArgb: Long = 0xFF000000,
    val edge: String = "outline",
    /** Seconds the subtitles are moved by: + delays them, - brings them forward. */
    val offsetSeconds: Double = 0.0,
    /** Whether the track's own styling (an .ass file's) wins over these settings. */
    val useEmbeddedStyles: Boolean = true,
    /** The language picked by itself when a stream offers several ("", "en", "es"…). */
    val preferredLanguage: String = "",
)

/** The player's own settings. */
data class PlayerSettings(
    val autoPlayNext: Boolean = true,
    val autoSkipIntro: Boolean = true,
    val showSkipButton: Boolean = true,
    val quality: QualityPref = QualityPref.AUTO,
    val speed: Float = 1.0f,
    val fit: FitMode = FitMode.FIT,
    val defaultAudio: Audio = Audio.SUB,
    val doubleTapSeekSeconds: Int = 10,
    val gestures: Boolean = true,
    val volumeGesture: Boolean = true,
    val brightnessGesture: Boolean = true,
    val ambilight: Boolean = true,
    val resume: Boolean = true,
    val backgroundAudio: Boolean = false,
    val keepScreenOn: Boolean = true,
    val autoRotate: Boolean = true,
    val pictureInPicture: Boolean = true,
    val skipRecap: Boolean = false,
    val subtitles: SubtitleStyle = SubtitleStyle(),
    /** Ask Wi-Fi only for streams (a phone on data is told before a stream starts). */
    val dataSaver: Boolean = false,
    /** The server a source answered with last, asked for again first. */
    val rememberServer: Boolean = true,
    val lastServer: String? = null,
)

/** The downloads' settings. */
data class DownloadSettings(
    val location: DownloadLocation = DownloadLocation.APP,
    val wifiOnly: Boolean = true,
    val maxConcurrent: Int = 2,
    /** Keep a finished download when its episode has been watched. */
    val keepAfterWatching: Boolean = true,
    /** How many days a finished download stays before the app offers to remove it (0 = forever). */
    val keepDays: Int = 0,
    /** Take the subtitles of an episode down with it. */
    val subtitles: Boolean = true,
    /** The quality a download takes: the variant of the stream closest to it, under it. */
    val quality: QualityPref = QualityPref.H1080,
    /** How much storage downloads may take, in MB; 0 asks for no limit. */
    val budgetMb: Int = 8192,
    /** Ask before a download starts on a phone connection (a metered network). */
    val askOnMetered: Boolean = true,
)

/** Everything the app remembers of how the user wants it. */
data class AppSettings(
    val look: Look = Look.IMMERSIVE,
    val accent: Accent = Accent.DEFAULT,
    /** The wallpaper's colour as the accent (Material You). */
    val dynamicColor: Boolean = false,
    /** Pure black pages, for an OLED phone. */
    val oledBlack: Boolean = true,
    /** The app's language: "system", or a tag such as "es", "en", "pt-BR". */
    val language: String = "system",
    /** Show the anime marked for adults in searches and pages. */
    val nsfw: Boolean = false,
    /** The setup has been walked through (the first-run screens do not come back). */
    val onboardingDone: Boolean = false,
    /** The order the resolver asks the sources in; the ones not listed come after, as shipped. */
    val sourceOrder: List<String> = emptyList(),
    /** The sources turned off. */
    val disabledSources: Set<String> = emptySet(),
    /** The address of the user's own Kitsune server (Settings, Sources); empty = off. */
    val kitsuneServer: String = "",
    /** The catalog's page size and the number of home sections (a slow connection asks less). */
    val homeSections: List<String> = listOf("continue", "trending", "seasonal", "popular", "airing", "top"),
    val player: PlayerSettings = PlayerSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    /** Ask a release every so often (Settings, About). */
    val updateCheck: Boolean = true,
    /** Send no crash reports: Kuroka has none, this only documents the choice. */
    val analytics: Boolean = false,
)
