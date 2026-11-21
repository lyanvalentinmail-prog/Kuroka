@file:OptIn(UnstableApi::class)

package app.kuroka.host.playback

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Cue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.kuroka.api.SubtitleFormat
import app.kuroka.api.SubtitleTrack
import app.kuroka.host.downloads.Downloads
import app.kuroka.host.downloads.StreamHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The one player of the app, and everything the screens ask of it: an ExoPlayer built over the same
 * data source as the downloads (so an episode on the phone plays from the phone, and a stream from
 * the network is fetched with the headers it needs), its tracks read into plain lists a sheet can
 * offer, its subtitles published as cues the UI draws itself (which is what makes a subtitle offset
 * and a subtitle colour possible at all), and a snapshot of the playback a composable collects.
 *
 * The service (KurokaPlaybackService) puts a media session over this same player, so the
 * notification, the lock screen and a headset's button talk to what the screen shows — one player,
 * one set of tracks, one position.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** One track of the stream, as a sheet lists it. */
data class TrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val type: Int,
    val label: String,
    val language: String?,
    val height: Int,
    val bitrate: Int,
    val mimeType: String?,
    val selected: Boolean,
    val supported: Boolean,
) {
    val isVideo: Boolean get() = type == C.TRACK_TYPE_VIDEO
    val isAudio: Boolean get() = type == C.TRACK_TYPE_AUDIO
    val isText: Boolean get() = type == C.TRACK_TYPE_TEXT

    /** "1080p", "720p · 2.4 Mbps". */
    val qualityLabel: String
        get() = if (height > 0) {
            val mbps = if (bitrate > 0) " · ${String.format("%.1f", bitrate / 1_000_000.0)} Mbps" else ""
            "${height}p$mbps"
        } else if (bitrate > 0) "${bitrate / 1000} kbps" else label

    /** A subtitle's own name when the stream gave it none: its language, or "Subtitle 2". */
    fun subtitleLabel(index: Int): String =
        language?.let { it.uppercase() }?.takeIf { label.isBlank() || label == "Track $index" } ?: label
}

/** One line of subtitles, as the UI draws it. */
data class CueLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** The cue's own size, when the file asked for one and the settings let it win. */
    val textSizePercent: Float? = null,
    val windowColor: Int? = null,
)

/** What the player is doing, as one value a screen collects. */
data class PlayerSnapshot(
    val hasItem: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val ended: Boolean = false,
    val error: String? = null,
    val speed: Float = 1.0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoRotation: Int = 0,
    val mediaId: String? = null,
    val title: String? = null,
    val episode: String? = null,
    val artwork: String? = null,
    val tracks: List<TrackInfo> = emptyList(),
    val cues: List<CueLine> = emptyList(),
) {
    val textTracks: List<TrackInfo> get() = tracks.filter { it.isText && it.supported }
    val audioTracks: List<TrackInfo> get() = tracks.filter { it.isAudio && it.supported }
    val videoTracks: List<TrackInfo> get() = tracks.filter { it.isVideo && it.supported }

    /** The qualities a sheet offers, biggest first; "Auto" is the absence of a choice. */
    val qualities: List<TrackInfo> get() = videoTracks.sortedByDescending { it.height }
    val selectedText: TrackInfo? get() = textTracks.firstOrNull { it.selected }
    val selectedAudio: TrackInfo? get() = audioTracks.firstOrNull { it.selected }
    val selectedQuality: TrackInfo? get() = videoTracks.firstOrNull { it.selected }

    /** More than one audio language in the stream: the sheet that switches them is worth showing. */
    val hasSeveralAudios: Boolean get() = audioTracks.distinctBy { it.language ?: it.label }.size > 1
    val hasSeveralQualities: Boolean get() = qualities.distinctBy { it.height }.size > 1

    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** True when the picture has a size yet (a stream that has not started drawing). */
    val hasPicture: Boolean get() = videoWidth > 0 && videoHeight > 0
}

/** What to play: one episode, its stream, its subtitles, where to start and what to call it. */
data class PlaybackItem(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val title: String,
    val episode: String,
    val artwork: String? = null,
    val mediaId: String = uri,
    val startPositionMs: Long = 0L,
)

object PlayerHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var built: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var appContext: Context? = null

    /** How far the subtitles are moved by, in milliseconds (Settings, Subtitles). */
    @Volatile
    var subtitleOffsetMs: Long = 0L

    /** Whether a subtitle file's own styling wins over the settings. */
    @Volatile
    var useEmbeddedStyles: Boolean = true

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    /** What the player is doing. Collected by the player screen, the mini player and the service. */
    val snapshot: StateFlow<PlayerSnapshot> get() = _snapshot

    /** Called when an episode ends, so the screen can go on to the next one. */
    @Volatile
    var onEnded: (() -> Unit)? = null

    /** Called when the picture's size is known, so the screen can lay it out and enter PiP. */
    @Volatile
    var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

    /** Called when something went wrong in a way a screen should say out loud. */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /** The player, built once over the downloads' data source. Main thread, or [prepare]. */
    fun player(context: Context): ExoPlayer {
        val existing = built
        if (existing != null) return existing
        val app = context.applicationContext
        appContext = app
        val selector = DefaultTrackSelector(app).apply {
            setParameters(
                buildUponParameters()
                    // A phone screen does not need more than this, and a slow network starts low.
                    .setMaxVideoSize(1920, 1080)
                    .setForceLowestBitrate(false)
                    .setTunnelingEnabled(false)
                    // A stream's own text track is off until the user asks for one.
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        trackSelector = selector
        // The builder's second argument is a source factory and its third a renderers factory: a
        // track selector is a setter of its own.
        val created = ExoPlayer.Builder(app, DefaultMediaSourceFactory(Downloads.playback(app)))
            .setTrackSelector(selector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 120_000, 1_500, 4_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // A headset that is unplugged pauses the episode.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        created.addListener(listener)
        built = created
        return created
    }

    /** The player if it has been built, for a caller that must not build one (the service). */
    val existing: ExoPlayer? get() = built

    /** Builds the player off the main thread and reads it into the snapshot. */
    fun prepare(context: Context) {
        scope.launch {
            val p = player(context)
            read(p)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_PLAYER_ERROR,
                )
            ) read(player)
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) read(player)
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                read(player)
                onVideoSize?.invoke(player.videoSize.width, player.videoSize.height)
            }
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) read(player)
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED
            ) {
                _snapshot.value = _snapshot.value.copy(ended = true, playing = false, buffering = false, cues = emptyList())
                onEnded?.invoke()
            }
        }

        override fun onCues(cues: List<Cue>) {
            val out = ArrayList<CueLine>(cues.size)
            for (i in 0 until cues.size) {
                val cue = cues.get(i)
                val text = cue.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val start = cue.startTimeUs.takeIf { it != C.TIME_UNSET }?.let { it / 1000 } ?: -1L
                val end = cue.endTimeUs.takeIf { it != C.TIME_UNSET }?.let { it / 1000 } ?: -1L
                // The offset the settings ask for: + moves the words later.
                val shiftedStart = if (start >= 0) start - subtitleOffsetMs else start
                val shiftedEnd = if (end >= 0) end - subtitleOffsetMs else end
                val now = built?.currentPosition ?: 0L
                if (shiftedStart >= 0 && now < shiftedStart) continue
                if (shiftedEnd >= 0 && now > shiftedEnd) continue
                out += CueLine(
                    text = text,
                    startMs = shiftedStart,
                    endMs = shiftedEnd,
                    textSizePercent = if (useEmbeddedStyles) cue.textSize.takeIf { it > 0 }?.let { it * 100f } else null,
                    windowColor = if (useEmbeddedStyles && cue.windowColorSet) cue.windowColor else null,
                )
            }
            _snapshot.value = _snapshot.value.copy(cues = out)
        }

        override fun onPlayerError(error: PlaybackException) {
            val why = describe(error)
            _snapshot.value = _snapshot.value.copy(error = why, playing = false, buffering = false)
            onError?.invoke(why)
        }
    }

    /** Puts an episode on the player: its stream, its subtitles, its headers and where to start. */
    fun load(item: PlaybackItem, playWhenReady: Boolean = true) {
        val p = built ?: appContext?.let { player(it) } ?: return
        if (item.headers.isNotEmpty()) StreamHeaders.rememberCurrent(item.uri, item.headers)
        item.subtitles.forEach { track ->
            if (track.headers.isNotEmpty()) StreamHeaders.remember(track.url, track.headers)
        }
        val subtitles = item.subtitles.map { track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(mimeOf(track))
                .setLabel(track.name)
                .setLanguage(track.language.ifBlank { null })
                .setSelectionFlags(if (track.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(item.uri))
            .setMediaId(item.mediaId)
            .apply { if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.episode)
                    .setAlbumTitle(item.title)
                    .apply { item.artwork?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
            )
            .build()
        _snapshot.value = _snapshot.value.copy(
            hasItem = true, ended = false, error = null, cues = emptyList(),
            mediaId = item.mediaId, title = item.title, episode = item.episode, artwork = item.artwork,
        )
        runCatching {
            p.setMediaItem(mediaItem, item.startPositionMs.coerceAtLeast(0L))
            p.playbackParameters = PlaybackParameters(p.playbackParameters.speed)
            p.prepare()
            p.playWhenReady = playWhenReady
        }.onFailure { failure ->
            val why = failure.message ?: "The episode would not start"
            _snapshot.value = _snapshot.value.copy(error = why)
            onError?.invoke(why)
        }
    }

    private fun mimeOf(track: SubtitleTrack): String = when (track.format) {
        // A format the provider did not name is guessed from the file name; the rest carry one.
        SubtitleFormat.UNKNOWN -> when {
            track.url.contains(".srt", true) -> SubtitleFormat.SRT.mime
            track.url.contains(".ass", true) || track.url.contains(".ssa", true) -> SubtitleFormat.ASS.mime
            track.url.contains(".ttml", true) || track.url.contains(".xml", true) -> "application/ttml+xml"
            else -> SubtitleFormat.VTT.mime
        }

        else -> track.format.mime
    }

    /** The picture the screen draws on. */
    fun attach(view: SurfaceView) {
        built?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        built?.clearVideoSurfaceView(view)
    }

    fun play() {
        built?.let { it.playWhenReady = true; read(it) }
    }

    fun pause() {
        built?.let { it.playWhenReady = false; read(it) }
    }

    fun playPause() {
        built?.let { it.playWhenReady = !it.playWhenReady; read(it) }
    }

    fun seekTo(ms: Long) {
        built?.let { it.seekTo(ms.coerceAtLeast(0L)); read(it) }
    }

    fun seekBy(ms: Long) {
        built?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0L)); read(it) }
    }

    fun setSpeed(speed: Float) {
        built?.let { it.setPlaybackSpeed(speed.coerceIn(0.25f, 4.0f)); read(it) }
    }

    /** A subtitle track, or null to turn the subtitles off. */
    fun selectText(track: TrackInfo?) {
        val p = built ?: return
        val builder = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (track != null) groupOf(p, track)?.let {
            builder.setOverrideForType(TrackSelectionOverride(it, track.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
        if (track == null) _snapshot.value = _snapshot.value.copy(cues = emptyList())
        read(p)
    }

    /** An audio track: the dub, or the original with subtitles. */
    fun selectAudio(track: TrackInfo) {
        val p = built ?: return
        val position = p.currentPosition
        val wasPlaying = p.playWhenReady
        val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        groupOf(p, track)?.let { builder.setOverrideForType(TrackSelectionOverride(it, track.trackIndex)) }
        p.trackSelectionParameters = builder.build()
        // A stream that carries the languages as separate items restarts where it was.
        if (p.duration <= 0 || p.currentPosition == 0L) p.seekTo(position)
        p.playWhenReady = wasPlaying
        read(p)
    }

    /** A picture size, or null for "whatever fits the connection". */
    fun selectQuality(track: TrackInfo?) {
        val p = built ?: return
        val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (track == null) {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            groupOf(p, track)?.let { builder.setOverrideForType(TrackSelectionOverride(it, track.trackIndex)) }
            if (track.height > 0) builder.setMaxVideoSize(Int.MAX_VALUE, track.height)
        }
        p.trackSelectionParameters = builder.build()
        read(p)
    }

    /** Asks for a language by itself, when a stream offers several (Settings, Player). */
    fun preferTextLanguage(language: String?) {
        built?.trackSelectionParameters = built?.trackSelectionParameters?.buildUpon()
            ?.setPreferredTextLanguage(language?.ifBlank { null })
            ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, language.isNullOrBlank())
            ?.build() ?: return
    }

    fun preferAudioLanguage(language: String?) {
        built?.trackSelectionParameters = built?.trackSelectionParameters?.buildUpon()
            ?.setPreferredAudioLanguage(language?.ifBlank { null })
            ?.build() ?: return
    }

    /** Lets go of the picture but keeps the audio (the background-audio setting). */
    fun releaseVideo() {
        built?.clearVideoSurface()
    }

    /** Lets go of the player entirely (the task is gone and background audio is off). */
    fun release() {
        built?.let { p ->
            runCatching { p.removeListener(listener) }
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        built = null
        trackSelector = null
        _snapshot.value = PlayerSnapshot()
    }

    /** Stops the picture and the sound, keeping the player for the next episode. */
    fun stop() {
        built?.let { it.stop(); it.clearVideoSurface() }
        _snapshot.value = _snapshot.value.copy(playing = false, buffering = false, cues = emptyList(), ended = false)
    }

    private fun groupOf(player: Player, track: TrackInfo) =
        player.currentTracks.groups.getOrNull(track.groupIndex)?.mediaTrackGroup

    /** Reads everything the screens show into the snapshot. */
    fun read(player: Player? = built) {
        val p = player ?: return
        val tracks = ArrayList<TrackInfo>()
        runCatching {
            p.currentTracks.groups.forEachIndexed { groupIndex, group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    tracks += TrackInfo(
                        groupIndex = groupIndex,
                        trackIndex = i,
                        type = group.type,
                        label = format.label ?: defaultLabel(group.type, i),
                        language = format.language,
                        height = format.height,
                        bitrate = format.bitrate,
                        mimeType = format.sampleMimeType,
                        selected = group.isTrackSelected(i),
                        supported = group.isTrackSupported(i),
                    )
                }
            }
        }
        val size = runCatching { p.videoSize }.getOrNull()
        val metadata = runCatching { p.mediaMetadata }.getOrNull()
        _snapshot.value = _snapshot.value.copy(
            hasItem = p.mediaItemCount > 0,
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L,
            bufferedMs = p.bufferedPosition.coerceAtLeast(0L),
            ended = p.playbackState == Player.STATE_ENDED,
            error = p.playerError?.let { describe(it) },
            speed = p.playbackParameters.speed,
            videoWidth = size?.width ?: 0,
            videoHeight = size?.height ?: 0,
            videoRotation = runCatching { p.videoSize.unappliedRotationDegrees }.getOrDefault(0),
            mediaId = p.currentMediaItem?.mediaId ?: _snapshot.value.mediaId,
            title = metadata?.title?.toString() ?: _snapshot.value.title,
            episode = metadata?.artist?.toString() ?: _snapshot.value.episode,
            artwork = metadata?.artworkUri?.toString() ?: _snapshot.value.artwork,
            tracks = tracks,
        )
    }

    private fun defaultLabel(type: Int, index: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "Video ${index + 1}"
        C.TRACK_TYPE_AUDIO -> "Audio ${index + 1}"
        C.TRACK_TYPE_TEXT -> "Subtitle ${index + 1}"
        else -> "Track ${index + 1}"
    }

    /** What went wrong, as a person reads it. */
    fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "No connection to the stream's server"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The stream's server did not answer"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the request"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The stream is gone from the server"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "The stream could not be read"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "The stream is http://, which Android refuses"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This phone cannot read that container"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This phone cannot read that playlist"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This phone cannot decode that video"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "This phone cannot list its decoders"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "The stream went past; starting it again"
        else -> error.errorCodeName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The position and the duration, for a caller that is not collecting the snapshot. */
    val positionMs: Long get() = built?.currentPosition ?: 0L
    val durationMs: Long get() = built?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
    val isPlaying: Boolean get() = built?.isPlaying == true
}
