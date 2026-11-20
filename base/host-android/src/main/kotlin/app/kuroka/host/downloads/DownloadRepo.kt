@file:OptIn(UnstableApi::class)

package app.kuroka.host.downloads

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import app.kuroka.api.Anime
import app.kuroka.api.Audio
import app.kuroka.api.DownloadItem
import app.kuroka.api.DownloadState
import app.kuroka.api.Episode
import app.kuroka.api.StreamSource
import app.kuroka.api.SubtitleTrack
import app.kuroka.api.WatchResult
import app.kuroka.host.prefs.Prefs
import app.kuroka.sources.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/*
 * The downloads, as the screens see them: ask for an episode, and it is taken down (the variant
 * of the quality the settings ask for, its subtitles with it), it shows its progress, it pauses
 * and goes on, it is copied to the device's own Movies folder when the user wants it there, and
 * it plays from the phone with no network at all.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class DownloadRepo(
    private val context: Context,
    private val store: DownloadStore,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    /** Every download, newest first. */
    val items: StateFlow<List<DownloadItem>> get() = _items

    private val _active = MutableStateFlow(0)
    /** How many downloads are coming down or waiting to. */
    val active: StateFlow<Int> get() = _active

    private val _message = MutableStateFlow<String?>(null)
    /** What the last download attempt said ("Waiting for Wi-Fi", "Not enough room"). */
    val message: StateFlow<String?> get() = _message

    private var listening = false

    /** Reads the rows and starts hearing what Media3 says of them. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            reload()
            if (!listening) {
                listening = true
                runCatching { Downloads.downloadManager(context).addListener(listener) }
            }
        }
    }

    private fun reload() {
        val all = store.all()
        // Media3 knows the bytes better than the rows do: the active ones are read from it.
        val manager = runCatching { Downloads.downloadManager(context) }.getOrNull()
        val merged = if (manager == null) all else all.map { item ->
            val download = runCatching { manager.getDownload(item.id) }.getOrNull() ?: return@map item
            item.copy(
                state = stateOf(download.state),
                bytesDone = download.bytesDownloaded,
                bytesTotal = if (download.contentLength > 0) download.contentLength else item.bytesTotal,
            )
        }
        _items.value = merged
        _active.value = merged.count { it.state.isActive }
    }

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val id = download.request.id
            store.updateProgress(
                id = id,
                state = stateOf(download.state),
                bytesDone = download.bytesDownloaded,
                bytesTotal = if (download.contentLength > 0) download.contentLength else 0L,
                failure = failureOf(download, finalException),
            )
            reload()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            store.remove(download.request.id)
            deleteFiles(download.request.id)
            reload()
        }
    }

    private fun stateOf(state: Int): DownloadState = when (state) {
        Download.STATE_QUEUED -> DownloadState.QUEUED
        Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
        Download.STATE_COMPLETED -> DownloadState.COMPLETED
        Download.STATE_FAILED -> DownloadState.FAILED
        Download.STATE_REMOVING -> DownloadState.REMOVING
        Download.STATE_STOPPED -> DownloadState.PAUSED
        else -> DownloadState.QUEUED
    }

    private fun failureOf(download: Download, exception: Exception?): String? = when (download.failureReason) {
        Download.FAILURE_REASON_NONE -> null
        Download.FAILURE_REASON_UNKNOWN_CONTENT_TYPE -> "The server did not say what it serves"
        Download.FAILURE_REASON_FILE_NOT_FOUND -> "The stream is gone from the server"
        Download.FAILURE_REASON_NO_ACTIONABLE_VARIANTS -> "No variant of the quality asked for"
        Download.FAILURE_REASON_DATA_MANIFEST_NOT_FOUND -> "The playlist is gone"
        Download.FAILURE_REASON_BAD_MANIFEST -> "The playlist cannot be read"
        Download.FAILURE_REASON_STARTED_NOT_LOADED -> "Nothing came down"
        Download.FAILURE_REASON_IO_FILE -> "The phone could not write it"
        Download.FAILURE_REASON_IO_NETWORK -> "The network went"
        Download.FAILURE_REASON_IO_BAD_HTTP_STATUS -> "The server refused"
        Download.FAILURE_REASON_IO_INVALID_HTTP_CONTENT_TYPE -> "The server served something else"
        Download.FAILURE_REASON_IO_READ_POSITION_OUT_OF_RANGE -> "The stream ended early"
        Download.FAILURE_REASON_IO_CLEARTEXT_NOT_PERMITTED -> "The stream is http://, which Android refuses"
        Download.FAILURE_REASON_DECODER_INIT_FAILED -> "The phone cannot decode it"
        Download.FAILURE_REASON_DECODER_QUERY_FAILED -> "The phone cannot read its tracks"
        else -> exception?.message ?: "The download stopped"
    }

    /** What the app calls a download: one anime, one episode, one audio. */
    fun id(animeId: Int, audio: Audio, number: Double): String =
        "ep-$animeId-${audio.code}-${if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()}"

    fun itemOf(animeId: Int, audio: Audio, number: Double): DownloadItem? =
        _items.value.firstOrNull { it.id == id(animeId, audio, number) }

    fun itemsOf(animeId: Int): List<DownloadItem> = _items.value.filter { it.animeId == animeId }

    /** True when the phone is on a network the settings allow a download on. */
    fun networkAllows(): Boolean {
        if (!prefs.now.downloads.wifiOnly) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return true
        // A phone network the carrier does not meter counts as Wi-Fi for a download.
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The bytes the downloads take, and the room the settings leave them. */
    fun roomLeft(): Long {
        val budget = prefs.now.downloads.budgetMb.toLong() * 1024L * 1024L
        val used = Downloads.bytesOnDisk(context)
        val free = Downloads.folder(context).usableSpace
        return if (budget <= 0L) free else minOf(budget - used, free)
    }

    /**
     * Takes an episode down. The stream is asked for the variant of the quality in the settings,
     * its subtitles are fetched next to it, and Media3's service does the downloading.
     */
    suspend fun download(
        anime: Anime,
        episode: Episode,
        watch: WatchResult,
        stream: StreamSource? = null,
        audio: Audio = episode.audio,
    ): DownloadItem? = withContext(Dispatchers.IO) {
        val chosen = stream ?: watch.best ?: return@withContext null
        val id = id(anime.id, audio, episode.number)
        store.of(id)?.let {
            if (it.state == DownloadState.COMPLETED || it.state.isActive) return@withContext it
        }
        if (!networkAllows()) {
            _message.value = "Waiting for Wi-Fi (Settings, Downloads)"
            val queued = item(
                id = id, anime = anime, episode = episode, audio = audio, stream = chosen,
                subtitles = emptyList(), state = DownloadState.PAUSED, failure = "Waiting for Wi-Fi",
                uri = chosen.url,
            )
            store.save(queued)
            reload()
            return@withContext queued
        }
        if (roomLeft() < 60L * 1024 * 1024) {
            _message.value = "Not enough room for another episode"
            return@withContext null
        }

        val headers = if (chosen.headers.isEmpty()) ProviderHeaders.of(chosen) else chosen.headers
        StreamHeaders.rememberCurrent(chosen.url, headers)

        // The variant of the quality asked for: an HLS master playlist has several.
        val uri = if (chosen.type == app.kuroka.api.StreamType.HLS || chosen.url.contains(".m3u8")) {
            val picked = HlsVariant.pick(chosen.url, headers, prefs.now.downloads.quality)
            StreamHeaders.remember(picked, headers)
            picked
        } else {
            chosen.url
        }
        StreamHeaders.remember(uri, headers)

        val subtitles = if (prefs.now.downloads.subtitles) {
            val wanted = (chosen.subtitles + watch.subtitles).distinctBy { it.url }
            takeSubtitles(id, wanted, headers)
        } else emptyList()

        val item = item(
            id = id, anime = anime, episode = episode, audio = audio, stream = chosen,
            subtitles = subtitles, state = DownloadState.QUEUED, failure = null, uri = uri,
        )
        store.save(item)
        reload()
        _message.value = null

        val request = DownloadRequest.Builder(id, Uri.parse(uri))
            .setData(anime.title.display.toByteArray(Charsets.UTF_8))
            .build()
        runCatching {
            DownloadService.sendAddDownload(context, KurokaDownloadService::class.java, request, false)
        }.onFailure {
            store.updateProgress(id, DownloadState.FAILED, 0L, 0L, it.message ?: "The download could not start")
            reload()
        }
        store.of(id) ?: item
    }

    private fun item(
        id: String, anime: Anime, episode: Episode, audio: Audio, stream: StreamSource,
        subtitles: List<SubtitleTrack>, state: DownloadState, failure: String?, uri: String,
    ) = DownloadItem(
        id = id,
        animeId = anime.id,
        episodeNumber = episode.number,
        audio = audio,
        title = anime.title.display,
        episodeTitle = episode.title,
        cover = anime.cover,
        episodeImage = episode.image,
        uri = uri,
        source = episode.source.ifBlank { stream.server },
        server = stream.server,
        headers = stream.headers,
        subtitles = subtitles,
        state = state,
        failure = failure,
        addedAt = System.currentTimeMillis(),
    )

    /** Fetches the subtitle files of an episode into its own folder, so they are there offline. */
    private fun takeSubtitles(id: String, tracks: List<SubtitleTrack>, headers: Map<String, String>): List<SubtitleTrack> {
        if (tracks.isEmpty()) return emptyList()
        val dir = File(Downloads.folder(context), id).apply { mkdirs() }
        val out = ArrayList<SubtitleTrack>()
        tracks.forEachIndexed { index, track ->
            val name = "sub-$index.${track.format.name.lowercase().let { if (it == "unknown") "vtt" else it }}"
            val file = File(dir, name)
            val taken = runCatching {
                val builder = Request.Builder().url(track.url)
                (if (track.headers.isEmpty()) headers else track.headers).forEach { (k, v) -> builder.header(k, v) }
                builder.header("User-Agent", headers["User-Agent"] ?: Http.UA)
                Http.client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    file.writeBytes(response.body?.bytes() ?: return@use false)
                    true
                }
            }.getOrDefault(false)
            out += if (taken) track.copy(url = Uri.fromFile(file).toString()) else track
        }
        return out
    }

    /** The headers of a stream that came with none: a browser's, with its site as the referer. */
    private object ProviderHeaders {
        fun of(stream: StreamSource): Map<String, String> {
            val h = LinkedHashMap<String, String>()
            h["User-Agent"] = Http.UA
            stream.referer?.let { h["Referer"] = it }
            h["Accept"] = "*/*"
            return h
        }
    }

    fun pause(id: String) {
        runCatching { DownloadService.sendPauseDownload(context, KurokaDownloadService::class.java, id, false) }
        store.updateProgress(id, DownloadState.PAUSED, store.of(id)?.bytesDone ?: 0L, 0L, null)
        reload()
    }

    fun resume(id: String) {
        store.of(id)?.let { item ->
            StreamHeaders.rememberCurrent(item.uri, item.headers)
            item.subtitles.forEach { t -> StreamHeaders.remember(t.url, item.headers) }
        }
        runCatching { DownloadService.sendResumeDownload(context, KurokaDownloadService::class.java, id, false) }
        store.updateProgress(id, DownloadState.QUEUED, store.of(id)?.bytesDone ?: 0L, 0L, null)
        reload()
    }

    fun remove(id: String) {
        runCatching { DownloadService.sendRemoveDownload(context, KurokaDownloadService::class.java, id, false) }
        store.remove(id)
        deleteFiles(id)
        reload()
    }

    fun pauseAll() {
        runCatching { DownloadService.sendPauseAllDownloads(context, KurokaDownloadService::class.java, false) }
        _items.value.filter { it.state.isActive }.forEach {
            store.updateProgress(it.id, DownloadState.PAUSED, it.bytesDone, it.bytesTotal, null)
        }
        reload()
    }

    fun resumeAll() {
        _items.value.filter { it.state == DownloadState.PAUSED }.forEach { resume(it.id) }
        runCatching { DownloadService.sendResumeAllDownloads(context, KurokaDownloadService::class.java, false) }
        reload()
    }

    /** Everything that has been taken down, gone (the ones still coming stay). */
    fun clearFinished() {
        _items.value.filter { it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED }
            .forEach { remove(it.id) }
    }

    /** The episodes of one anime, gone (an anime page's "remove the downloads"). */
    fun removeOf(animeId: Int) {
        itemsOf(animeId).forEach { remove(it.id) }
    }

    private fun deleteFiles(id: String) {
        runCatching { File(Downloads.folder(context), id).deleteRecursively() }
    }

    /**
     * Copies a finished download to the device's own Movies/Kuroka folder, through MediaStore: the
     * file is then the user's, kept when the app goes, and playable by any other player.
     */
    suspend fun exportToDevice(item: DownloadItem): Uri? = withContext(Dispatchers.IO) {
        if (item.state != DownloadState.COMPLETED) return@withContext null
        item.localUri?.let { return@withContext Uri.parse(it) }
        val name = fileName(item)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kuroka")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = runCatching {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext null
        val written = runCatching {
            resolver.openOutputStream(target)?.use { out ->
                val source = Downloads.playback(context).createDataSource()
                source.open(DataSpec.Builder().setUri(Uri.parse(item.uri)).build())
                val buffer = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                } finally {
                    source.close()
                }
                out.flush()
                true
            } ?: false
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(target, null, null) }
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        runCatching { resolver.update(target, values, null, null) }
        store.updateLocalUri(item.id, target.toString())
        reload()
        target
    }

    /** "Kuroka - One Piece - E12 - Filler.mp4": a name a file manager can read. */
    fun fileName(item: DownloadItem): String {
        val episode = if (item.episodeNumber % 1.0 == 0.0) item.episodeNumber.toInt().toString() else item.episodeNumber.toString()
        val safe = { s: String -> s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(60) }
        val title = safe(item.title)
        val episodeTitle = item.episodeTitle?.let { safe(it) }?.takeIf { it.isNotBlank() }
        return listOfNotNull("Kuroka", title, "E$episode", episodeTitle).joinToString(" - ") + ".mp4"
    }

    /** The room the downloads take, as the settings screen says it. */
    fun usedLabel(): String = DownloadItem.size(Downloads.bytesOnDisk(context))

    /** Whether an episode is on the phone and can be played with no network. */
    fun isDownloaded(animeId: Int, audio: Audio, number: Double): Boolean =
        itemOf(animeId, audio, number)?.state == DownloadState.COMPLETED
}
