package app.kuroka.host.downloads

import app.kuroka.api.Audio
import app.kuroka.api.DownloadItem
import app.kuroka.api.DownloadState
import app.kuroka.api.SubtitleFormat
import app.kuroka.api.SubtitleTrack
import app.kuroka.host.db.KurokaDb
import app.kuroka.host.db.double
import app.kuroka.host.db.flag
import app.kuroka.host.db.int
import app.kuroka.host.db.long
import app.kuroka.host.db.text
import app.kuroka.host.db.values
import org.json.JSONArray
import org.json.JSONObject

/*
 * The downloads as the phone keeps them: what an episode is, where its stream came from, the
 * headers that stream needs, its subtitles (taken down with it, so an offline episode keeps them)
 * and how far the bytes are. Media3 keeps the bytes; this keeps the sense of them.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class DownloadStore(private val db: KurokaDb) {

    fun all(): List<DownloadItem> = db.read(KurokaDb.TABLE_DOWNLOADS, orderBy = "added_at DESC") { c ->
        DownloadItem(
            id = c.text("id") ?: "",
            animeId = c.int("anime_id") ?: 0,
            episodeNumber = c.double("episode_number") ?: 1.0,
            audio = Audio.of(c.text("audio")),
            title = c.text("title") ?: "",
            episodeTitle = c.text("episode_title"),
            cover = c.text("cover"),
            episodeImage = c.text("episode_image"),
            uri = c.text("uri") ?: "",
            source = c.text("source") ?: "",
            server = c.text("server") ?: "",
            headers = headersOf(c.text("headers")),
            subtitles = subtitlesOf(c.text("subtitles")),
            state = stateOf(c.text("state")),
            bytesDone = c.long("bytes_done") ?: 0L,
            bytesTotal = c.long("bytes_total") ?: 0L,
            failure = c.text("failure"),
            addedAt = c.long("added_at") ?: 0L,
            completedAt = c.long("completed_at"),
            localUri = c.text("local_uri"),
            positionMs = c.long("position_ms") ?: 0L,
            durationMs = c.long("duration_ms") ?: 0L,
        )
    }

    fun of(id: String): DownloadItem? = all().firstOrNull { it.id == id }

    fun save(item: DownloadItem) {
        db.insert(
            KurokaDb.TABLE_DOWNLOADS,
            values {
                put("id", item.id)
                put("anime_id", item.animeId)
                put("episode_number", item.episodeNumber)
                put("audio", item.audio.code)
                put("title", item.title)
                item.episodeTitle?.let { put("episode_title", it) }
                item.cover?.let { put("cover", it) }
                item.episodeImage?.let { put("episode_image", it) }
                put("uri", item.uri)
                put("source", item.source)
                put("server", item.server)
                put("headers", headersJson(item.headers))
                put("subtitles", subtitlesJson(item.subtitles))
                put("state", item.state.name)
                put("bytes_done", item.bytesDone)
                put("bytes_total", item.bytesTotal)
                item.failure?.let { put("failure", it) }
                put("added_at", item.addedAt)
                item.completedAt?.let { put("completed_at", it) }
                item.localUri?.let { put("local_uri", it) }
                put("position_ms", item.positionMs)
                put("duration_ms", item.durationMs)
            }
        )
    }

    /** The bytes and the state of a download, as Media3 reports them. */
    fun updateProgress(id: String, state: DownloadState, bytesDone: Long, bytesTotal: Long, failure: String?) {
        db.update(
            KurokaDb.TABLE_DOWNLOADS,
            values {
                put("state", state.name)
                put("bytes_done", bytesDone)
                if (bytesTotal > 0) put("bytes_total", bytesTotal)
                failure?.let { put("failure", it) }
                if (state == DownloadState.COMPLETED) put("completed_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(id),
        )
    }

    fun updateLocalUri(id: String, localUri: String?) {
        db.update(
            KurokaDb.TABLE_DOWNLOADS,
            values { localUri?.let { put("local_uri", it) } ?: putNull("local_uri") },
            "id = ?", arrayOf(id),
        )
    }

    fun updatePosition(id: String, positionMs: Long, durationMs: Long) {
        db.update(
            KurokaDb.TABLE_DOWNLOADS,
            values {
                put("position_ms", positionMs)
                put("duration_ms", durationMs)
            },
            "id = ?", arrayOf(id),
        )
    }

    fun remove(id: String) {
        db.delete(KurokaDb.TABLE_DOWNLOADS, "id = ?", arrayOf(id))
    }

    fun removeFinishedOf(animeId: Int) {
        db.delete(KurokaDb.TABLE_DOWNLOADS, "anime_id = ? AND state = ?", arrayOf(animeId.toString(), DownloadState.COMPLETED.name))
    }

    fun headersJson(headers: Map<String, String>): String {
        val o = JSONObject()
        headers.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun headersOf(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k)
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun subtitlesJson(tracks: List<SubtitleTrack>): String {
        val a = JSONArray()
        tracks.forEach { t ->
            a.put(
                JSONObject()
                    .put("url", t.url)
                    .put("label", t.label)
                    .put("language", t.language)
                    .put("default", t.isDefault)
                    .put("format", t.format.name)
            )
        }
        return a.toString()
    }

    fun subtitlesOf(json: String?): List<SubtitleTrack> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            val out = ArrayList<SubtitleTrack>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                out += SubtitleTrack(
                    url = url,
                    label = o.optString("label"),
                    language = o.optString("language"),
                    isDefault = o.optBoolean("default"),
                    format = runCatching { SubtitleFormat.valueOf(o.optString("format", "UNKNOWN")) }
                        .getOrDefault(SubtitleFormat.of(url)),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun stateOf(name: String?): DownloadState =
        runCatching { DownloadState.valueOf(name ?: "QUEUED") }.getOrDefault(DownloadState.QUEUED)
}
