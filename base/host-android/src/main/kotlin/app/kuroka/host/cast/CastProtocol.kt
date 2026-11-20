package app.kuroka.host.cast

import org.json.JSONArray
import org.json.JSONObject

/*
 * The JSON messages Kuroka sends a Chromecast and reads from it (the CastV2 namespaces), for
 * Google's Default Media Receiver: no registration, no Google Play services, no closed library —
 * a video, its subtitles and a poster, and the transport under them.
 *
 * The protocol layer is Orpheon's (GPL-3.0, © kota-zo, https://github.com/kota-zo/Orpheon,
 * base/app/src/main/java/com/music/spotui/cast/CastProtocol.kt), rewritten here for video: the
 * metadata of a movie, the subtitle tracks and their style, and the media status of a receiver
 * that is showing an episode.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

object CastProtocol {
    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    const val RECEIVER = "receiver-0"

    /** Google's Default Media Receiver: plays a video from an address, no account needed. */
    const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

    /** The metadata type of a movie or an episode (Cast's `MovieMetadata`). */
    const val METADATA_MOVIE = 4

    fun type(t: String) = JSONObject().put("type", t)

    fun connect() = type("CONNECT").put("userAgent", "Kuroka").put("origin", JSONObject())

    fun close() = type("CLOSE")

    fun launch(requestId: Int, appId: String = DEFAULT_MEDIA_RECEIVER) =
        type("LAUNCH").put("appId", appId).put("requestId", requestId)

    fun getStatus(requestId: Int) = type("GET_STATUS").put("requestId", requestId)

    fun stopApp(requestId: Int, sessionId: String) =
        type("STOP").put("sessionId", sessionId).put("requestId", requestId)

    fun setVolume(requestId: Int, level: Double, muted: Boolean? = null) =
        type("SET_VOLUME").put("requestId", requestId)
            .put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0)).apply {
                muted?.let { m -> put("muted", m) }
            })

    private fun media(t: String, requestId: Int, sessionId: String, mediaSessionId: Int) =
        type(t).put("requestId", requestId).put("sessionId", sessionId).put("mediaSessionId", mediaSessionId)

    fun play(requestId: Int, sessionId: String, mediaSessionId: Int) =
        media("PLAY", requestId, sessionId, mediaSessionId)

    fun pause(requestId: Int, sessionId: String, mediaSessionId: Int) =
        media("PAUSE", requestId, sessionId, mediaSessionId)

    fun stop(requestId: Int, sessionId: String, mediaSessionId: Int) =
        media("STOP", requestId, sessionId, mediaSessionId)

    fun seek(requestId: Int, sessionId: String, mediaSessionId: Int, seconds: Double, resume: String? = null) =
        media("SEEK", requestId, sessionId, mediaSessionId).put("currentTime", seconds).apply {
            // "PLAYBACK_START", "PLAYBACK_PAUSE": what the receiver does after the jump.
            resume?.let { put("resumeState", it) }
        }

    /** Which subtitle tracks are on, and how they look. */
    fun editTracks(requestId: Int, sessionId: String, mediaSessionId: Int, trackIds: List<Int>, style: JSONObject?) =
        media("EDIT_TRACKS_INFO", requestId, sessionId, mediaSessionId).apply {
            val ids = JSONArray()
            trackIds.forEach { ids.put(it) }
            put("activeTrackIds", ids)
            style?.let { put("textTrackStyle", it) }
        }

    /** One subtitle track of a load. */
    fun track(id: Int, url: String, name: String, language: String?, contentType: String) =
        JSONObject().put("trackId", id).put("trackContentId", url).put("trackContentType", contentType)
            .put("type", "TEXT").put("name", name).apply { language?.let { put("language", it) } }

    /** The look of the subtitles the receiver draws: white on a dark box, with an outline. */
    fun textStyle(scale: Double = 1.0, foreground: String = "#FFFFFFFF", background: String = "#96000000") =
        JSONObject()
            .put("foregroundColor", foreground)
            .put("backgroundColor", background)
            .put("fontScale", scale)
            .put("fontFamily", "sans-serif")
            .put("edgeType", "OUTLINE")
            .put("edgeColor", "#FF000000")
            .put("windowType", "NONE")

    /**
     * What to play: [url] of type [contentType] (an HLS playlist, an MP4, a WebM), with its
     * titles, its poster, its subtitle [tracks] and the one of them that starts on.
     */
    fun load(
        requestId: Int, sessionId: String, url: String, contentType: String,
        title: String, subtitle: String, poster: String?, startSeconds: Double,
        autoplay: Boolean, tracks: List<JSONObject>, activeTrackIds: List<Int>,
        style: JSONObject? = textStyle(), durationSeconds: Double? = null,
    ): JSONObject {
        val metadata = JSONObject()
            .put("metadataType", METADATA_MOVIE)
            .put("title", title)
            .put("subtitle", subtitle)
        if (!poster.isNullOrBlank()) {
            metadata.put("images", JSONArray().put(JSONObject().put("url", poster)))
        }
        val media = JSONObject()
            .put("contentId", url)
            .put("contentUrl", url)
            .put("streamType", "BUFFERED")
            .put("contentType", contentType)
            .put("metadata", metadata)
        if (durationSeconds != null && durationSeconds > 0) media.put("duration", durationSeconds)
        if (tracks.isNotEmpty()) {
            val a = JSONArray()
            tracks.forEach { a.put(it) }
            media.put("tracks", a)
            style?.let { media.put("textTrackStyle", it) }
        }
        val message = type("LOAD")
            .put("requestId", requestId)
            .put("sessionId", sessionId)
            .put("media", media)
            .put("autoplay", autoplay)
            .put("currentTime", startSeconds.coerceAtLeast(0.0))
        val ids = JSONArray()
        activeTrackIds.forEach { ids.put(it) }
        if (ids.length() > 0) message.put("activeTrackIds", ids)
        return message
    }

    /** The receiver app Kuroka asked for, from a RECEIVER_STATUS. */
    data class App(val sessionId: String, val transportId: String, val statusText: String, val idle: Boolean)

    data class Receiver(val app: App?, val volume: Double?, val muted: Boolean, val activeInput: Boolean)

    fun receiver(status: JSONObject, appId: String = DEFAULT_MEDIA_RECEIVER): Receiver {
        val s = status.optJSONObject("status") ?: JSONObject()
        val apps = s.optJSONArray("applications")
        var app: App? = null
        if (apps != null) for (k in 0 until apps.length()) {
            val a = apps.optJSONObject(k) ?: continue
            if (a.optString("appId") == appId) {
                app = App(
                    sessionId = a.optString("sessionId"),
                    transportId = a.optString("transportId"),
                    statusText = a.optString("statusText"),
                    idle = a.optBoolean("isIdleScreen"),
                )
            }
        }
        val v = s.optJSONObject("volume")
        return Receiver(
            app = app,
            volume = v?.optDouble("level")?.takeIf { !it.isNaN() },
            muted = v?.optBoolean("muted") == true,
            activeInput = s.optBoolean("isActiveInput"),
        )
    }

    /** One media session's state, from a MEDIA_STATUS. */
    data class Media(
        val mediaSessionId: Int,
        val playerState: String,
        val idleReason: String?,
        val positionSeconds: Double,
        val durationSeconds: Double,
        val contentId: String?,
        val playbackRate: Double,
        val volume: Double?,
        val muted: Boolean,
        val activeTrackIds: List<Int>,
        val loading: Boolean,
    ) {
        val positionMs: Long get() = (positionSeconds * 1000).toLong()
        val durationMs: Long get() = (durationSeconds * 1000).toLong()
        val isPlaying: Boolean get() = playerState == "PLAYING"
        val isPaused: Boolean get() = playerState == "PAUSED"
        val isIdle: Boolean get() = playerState == "IDLE"
        val isBuffering: Boolean get() = playerState == "BUFFERING"
        val ended: Boolean get() = isIdle && idleReason == "FINISHED"
        val failed: Boolean get() = isIdle && (idleReason == "ERROR" || idleReason == "CANCELLED")
    }

    /** The first media status of a MEDIA_STATUS, or null when the receiver has nothing loaded. */
    fun media(message: JSONObject): Media? {
        val s = message.optJSONArray("status")?.optJSONObject(0) ?: return null
        val media = s.optJSONObject("media")
        val volume = s.optJSONObject("volume")
        val trackIds = ArrayList<Int>()
        s.optJSONArray("activeTrackIds")?.let { a ->
            for (i in 0 until a.length()) trackIds += a.optInt(i)
        }
        return Media(
            mediaSessionId = s.optInt("mediaSessionId", -1),
            playerState = s.optString("playerState", "IDLE"),
            idleReason = s.optString("idleReason", "").ifBlank { null },
            positionSeconds = s.optDouble("currentTime", 0.0).let { if (it.isNaN()) 0.0 else it },
            durationSeconds = (media?.optDouble("duration", 0.0) ?: 0.0).let { if (it.isNaN()) 0.0 else it },
            contentId = media?.optString("contentId")?.ifBlank { null },
            playbackRate = s.optDouble("playbackRate", 1.0).let { if (it.isNaN()) 1.0 else it },
            volume = volume?.optDouble("level")?.takeIf { !it.isNaN() },
            muted = volume?.optBoolean("muted") == true,
            activeTrackIds = trackIds,
            loading = s.optBoolean("loading") || s.optString("playerState") == "BUFFERING",
        )
    }

    /** What a receiver said when a load did not work. */
    fun failure(message: JSONObject): String? = when (message.optString("type")) {
        "LOAD_FAILED" -> "The Chromecast could not open the stream"
        "LOAD_CANCELLED" -> "The Chromecast left the stream"
        "INVALID_PLAYER_STATE" -> "The Chromecast is not ready for that yet"
        "INVALID_REQUEST" -> message.optString("reason").ifBlank { "The Chromecast refused the request" }
        "LAUNCH_ERROR" -> "The Chromecast could not start its receiver"
        else -> null
    }
}
