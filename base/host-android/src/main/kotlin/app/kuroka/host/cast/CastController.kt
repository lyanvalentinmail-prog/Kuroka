package app.kuroka.host.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/*
 * Casting an episode to a Chromecast, with no Google Play services and no closed library: the
 * protocol is coded by hand ([CastProtocol], [CastMessage], [CastChannel]), the devices are found
 * with Android's own mDNS ([CastDiscovery]), and the stream reaches the receiver through a little
 * HTTP server on the phone ([CastServer]) — which is what makes a provider's headers and its
 * playlists work on a TV at all.
 *
 * The shape of this class follows Orpheon's CastController (GPL-3.0, © kota-zo,
 * https://github.com/kota-zo/Orpheon): one state a screen collects, requests numbered, a status
 * asked for until the session answers, and everything on background threads.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

/** What to put on the TV. */
data class CastItem(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    val subtitle: String,
    val poster: String? = null,
    val subtitles: List<CastSubtitle> = emptyList(),
    val startPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** A subtitle track to offer the receiver; [CastServer] serves it as WebVTT. */
data class CastSubtitle(
    val url: String,
    val name: String,
    val language: String?,
    val headers: Map<String, String> = emptyMap(),
)

/** Where the casting stands, as one value a screen collects. */
data class CastState(
    val phase: CastPhase = CastPhase.IDLE,
    val device: CastDevice? = null,
    val item: CastItem? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Double = 0.7,
    val muted: Boolean = false,
    val activeTracks: List<Int> = emptyList(),
    val error: String? = null,
) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** Something is on the TV (the player hides its own picture then). */
    val casting: Boolean
        get() = phase == CastPhase.PLAYING || phase == CastPhase.PAUSED ||
            phase == CastPhase.LOADING || phase == CastPhase.CONNECTED
}

enum class CastPhase { IDLE, CONNECTING, CONNECTED, LOADING, PLAYING, PAUSED, STOPPED, ERROR }

class CastController(private val scope: CoroutineScope, client: OkHttpClient = OkHttpClient()) {

    private val server = CastServer(client)
    private var channel: CastChannel? = null
    private var sessionId: String? = null
    private var transportId: String? = null
    private var mediaSessionId = -1
    private val requestIds = AtomicInteger(1)
    private var statusJob: Job? = null
    private var connecting: Job? = null
    /** What to load once the session is up (an episode asked for while connecting). */
    private var pendingItem: CastItem? = null

    private val _state = MutableStateFlow(CastState())
    /** What the casting is doing. Collected by the player screen and by the mini player. */
    val state: StateFlow<CastState> get() = _state

    private fun nextId() = requestIds.getAndIncrement()

    /** Connects to a device and puts [item] on it. */
    fun cast(device: CastDevice, item: CastItem) {
        pendingItem = item
        connecting?.cancel()
        connecting = scope.launch {
            _state.value = CastState(phase = CastPhase.CONNECTING, device = device, item = item)
            val opened = withContext(Dispatchers.IO) { open(device) }
            if (!opened) return@launch
            pendingItem?.let { load(it) }
            startStatus()
        }
    }

    /** Opens the TLS connection, starts the media server on it and asks for the receiver app. */
    private suspend fun open(device: CastDevice): Boolean {
        val previous = channel
        channel = null
        runCatching { previous?.close(null) }
        sessionId = null
        transportId = null
        mediaSessionId = -1
        server.stop()

        val made = CastChannel(
            host = device.host,
            port = device.port,
            onMessage = { message, json -> handle(message.namespace, json) },
            onClosed = { why ->
                scope.launch {
                    val idle = _state.value.phase == CastPhase.IDLE
                    _state.value = _state.value.copy(
                        phase = if (idle) CastPhase.IDLE else CastPhase.STOPPED,
                        playing = false,
                        buffering = false,
                        error = if (idle) null else why?.message ?: "The Chromecast went",
                    )
                    stopStatus()
                    server.stop()
                    channel = null
                    sessionId = null
                    transportId = null
                    mediaSessionId = -1
                }
            },
        )
        val ok = withContext(Dispatchers.IO) { runCatching { made.open() }.isSuccess }
        if (!ok) {
            _state.value = _state.value.copy(
                phase = CastPhase.ERROR,
                error = "The Chromecast would not answer on ${device.host.hostAddress}",
            )
            return false
        }
        channel = made
        // The server listens on the address this connection uses, so the device can reach it.
        val served = withContext(Dispatchers.IO) { runCatching { server.start(made.localAddress) }.getOrDefault(false) }
        if (!served) {
            _state.value = _state.value.copy(
                phase = CastPhase.ERROR,
                error = "The phone could not open its media server",
            )
            runCatching { made.close(null) }
            return false
        }
        // The receiver app is asked for, then its status until the session answers.
        safeSend(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.launch(nextId()))
        safeSend(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.getStatus(nextId()))
        var waited = 0
        while (isActive && sessionId == null && waited < 20_000) {
            delay(400)
            waited += 400
            safeSend(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.getStatus(nextId()))
        }
        if (sessionId == null) {
            _state.value = _state.value.copy(
                phase = CastPhase.ERROR,
                error = "The Chromecast did not start its receiver",
            )
            runCatching { made.close(null) }
            return false
        }
        _state.value = _state.value.copy(phase = CastPhase.CONNECTED, error = null)
        return true
    }

    /** Puts an item on the connected device (the next episode goes through here too). */
    fun load(item: CastItem) {
        val session = sessionId
        if (session == null) {
            pendingItem = item
            return
        }
        pendingItem = item
        _state.value = _state.value.copy(phase = CastPhase.LOADING, item = item, error = null, playing = false)

        val served = server.route(item.url, item.headers) ?: item.url
        val tracks = ArrayList<JSONObject>()
        val active = ArrayList<Int>()
        item.subtitles.forEachIndexed { index, sub ->
            val id = index + 1
            val servedSub = server.route(sub.url, sub.headers.ifEmpty { item.headers }, CastServer.Kind.SUBTITLE)
            if (servedSub != null) {
                tracks += CastProtocol.track(id, servedSub, sub.name, sub.language, "text/vtt")
                if (index == 0) active += id
            }
        }
        val poster = item.poster?.let { server.route(it, emptyMap(), CastServer.Kind.IMAGE) ?: it }
        val message = CastProtocol.load(
            requestId = nextId(),
            sessionId = session,
            url = served,
            contentType = CastHttp.streamMime(item.url),
            title = item.title,
            subtitle = item.subtitle,
            poster = poster,
            startSeconds = item.startPositionMs / 1000.0,
            autoplay = true,
            tracks = tracks,
            activeTrackIds = active,
            style = CastProtocol.textStyle(),
            durationSeconds = item.durationMs.takeIf { it > 0 }?.let { it / 1000.0 },
        )
        sendMedia(message)
        _state.value = _state.value.copy(activeTracks = active)
    }

    fun play() {
        val session = sessionId ?: return
        if (mediaSessionId < 0) return
        sendMedia(CastProtocol.play(nextId(), session, mediaSessionId))
    }

    fun pause() {
        val session = sessionId ?: return
        if (mediaSessionId < 0) return
        sendMedia(CastProtocol.pause(nextId(), session, mediaSessionId))
    }

    fun toggle() {
        if (_state.value.playing) pause() else play()
    }

    fun seekTo(ms: Long) {
        val session = sessionId ?: return
        if (mediaSessionId < 0) return
        val resume = if (_state.value.playing) "PLAYBACK_START" else "PLAYBACK_PAUSE"
        sendMedia(CastProtocol.seek(nextId(), session, mediaSessionId, ms / 1000.0, resume))
        _state.value = _state.value.copy(positionMs = ms.coerceAtLeast(0L))
    }

    fun seekBy(ms: Long) = seekTo((_state.value.positionMs + ms).coerceAtLeast(0L))

    /** The device's own volume, 0.0 … 1.0. */
    fun setVolume(level: Double) =
        safeSend(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.setVolume(nextId(), level, null))

    fun setMuted(muted: Boolean) =
        safeSend(
            CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER,
            CastProtocol.setVolume(nextId(), _state.value.volume, muted),
        )

    /** Which subtitle tracks are on (empty: none). */
    fun setSubtitles(trackIds: List<Int>) {
        val session = sessionId ?: return
        if (mediaSessionId < 0) return
        sendMedia(CastProtocol.editTracks(nextId(), session, mediaSessionId, trackIds, CastProtocol.textStyle()))
        _state.value = _state.value.copy(activeTracks = trackIds)
    }

    /** Asks how the receiver stands, which keeps the bar and the TV in step. */
    fun refresh() {
        val session = sessionId ?: return
        if (mediaSessionId < 0) {
            safeSend(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.getStatus(nextId()))
            return
        }
        sendMedia(JSONObject().put("type", "GET_STATUS").put("requestId", nextId()).put("mediaSessionId", mediaSessionId))
    }

    /** Stops the receiver app and closes the connection; the server goes with it. */
    fun disconnect(stopApp: Boolean = true) {
        stopStatus()
        connecting?.cancel()
        connecting = null
        val session = sessionId
        if (stopApp && session != null) {
            safeSend(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.stopApp(nextId(), session))
        }
        runCatching { channel?.close(null) }
        channel = null
        sessionId = null
        transportId = null
        mediaSessionId = -1
        pendingItem = null
        server.stop()
        _state.value = CastState()
    }

    private fun sendMedia(message: JSONObject) {
        val destination = transportId ?: CastProtocol.RECEIVER
        safeSend(destination, CastProtocol.NS_MEDIA, message)
    }

    private fun safeSend(destination: String, namespace: String, message: JSONObject) {
        runCatching { channel?.send(destination, namespace, message) }
    }

    private fun handle(namespace: String, json: JSONObject) {
        when (namespace) {
            CastProtocol.NS_RECEIVER -> {
                val receiver = CastProtocol.receiver(json)
                val app = receiver.app
                if (app != null && sessionId == null) {
                    sessionId = app.sessionId
                    transportId = app.transportId
                    _state.value = _state.value.copy(phase = CastPhase.CONNECTED, error = null)
                } else if (app != null) {
                    transportId = app.transportId
                }
                receiver.volume?.let { _state.value = _state.value.copy(volume = it, muted = receiver.muted) }
            }

            CastProtocol.NS_MEDIA -> {
                CastProtocol.media(json)?.let { media -> onMedia(media) }
                CastProtocol.failure(json)?.let { message ->
                    _state.value = _state.value.copy(
                        phase = CastPhase.ERROR, error = message, playing = false, buffering = false,
                    )
                }
            }
        }
    }

    private fun onMedia(media: CastProtocol.Media) {
        if (media.mediaSessionId >= 0) mediaSessionId = media.mediaSessionId
        _state.value = _state.value.copy(
            phase = when {
                media.failed -> CastPhase.ERROR
                media.ended -> CastPhase.STOPPED
                media.isPlaying -> CastPhase.PLAYING
                media.isPaused -> CastPhase.PAUSED
                media.isBuffering || media.loading -> CastPhase.LOADING
                sessionId != null -> CastPhase.CONNECTED
                else -> CastPhase.IDLE
            },
            playing = media.isPlaying,
            buffering = media.isBuffering || media.loading,
            positionMs = media.positionMs,
            durationMs = if (media.durationMs > 0) media.durationMs else _state.value.durationMs,
            volume = media.volume ?: _state.value.volume,
            muted = media.muted,
            activeTracks = media.activeTrackIds,
            error = if (media.failed) "The Chromecast could not play the stream" else null,
        )
    }

    /** Asks the receiver how it stands once a second while something is on it. */
    private fun startStatus() {
        stopStatus()
        statusJob = scope.launch {
            while (isActive) {
                delay(1000)
                refresh()
            }
        }
    }

    private fun stopStatus() {
        statusJob?.cancel()
        statusJob = null
    }

    /** True when something is on a TV (the player hides its own surface then). */
    val isCasting: Boolean get() = _state.value.casting

    /** The address the phone serves the stream from, for a "casting" banner. */
    val servingFrom: String? get() = server.baseUrl
}
