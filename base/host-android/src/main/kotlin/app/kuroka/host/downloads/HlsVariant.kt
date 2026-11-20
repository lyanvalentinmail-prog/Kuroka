package app.kuroka.host.downloads

import app.kuroka.api.QualityPref
import app.kuroka.sources.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * The variant of a stream a download takes. An HLS master playlist offers the same episode in
 * several sizes; downloading all of them would take three times the room for one picture. This
 * reads the master playlist, picks the variant whose height is the closest under the quality the
 * settings ask for, and gives the downloader that variant's own playlist, which has one video in
 * it.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object HlsVariant {

    /** One variant of a master playlist: its height, its bitrate and where its playlist is. */
    data class Variant(val url: String, val height: Int, val bandwidth: Long, val codecs: String?)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * The playlist to download for [url] at [quality]: the variant's own when the URL is a master
     * playlist, else the URL itself (a media playlist, an MP4).
     */
    fun pick(url: String, headers: Map<String, String>, quality: QualityPref): String {
        if (quality == QualityPref.AUTO || quality == QualityPref.LOWEST) {
            // "Auto" takes the best there is; "Lowest" the smallest.
            val variants = master(url, headers)
            if (variants.isEmpty()) return url
            val chosen = if (quality == QualityPref.LOWEST) variants.minByOrNull { it.height }
            else variants.maxByOrNull { it.height }
            return chosen?.url ?: url
        }
        val variants = master(url, headers)
        if (variants.isEmpty()) return url
        val wanted = quality.maxHeight
        // The closest height that is not above the one asked for; the smallest when none is under.
        val under = variants.filter { it.height in 1..wanted }
        val chosen = under.maxByOrNull { it.height } ?: variants.minByOrNull { it.height } ?: return url
        return chosen.url
    }

    /** The variants of a master playlist, empty when the URL is not one (or cannot be read). */
    fun master(url: String, headers: Map<String, String>): List<Variant> = runCatching {
        val builder = Request.Builder().url(url)
        (if (headers.isEmpty()) StreamHeaders.forUrl(url) else headers).forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", headers["User-Agent"] ?: Http.UA)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            parse(response.body?.string() ?: "", url)
        }
    }.getOrDefault(emptyList())

    /** Reads an m3u8: the `EXT-X-STREAM-INF` lines and the playlist each one names. */
    fun parse(playlist: String, baseUrl: String): List<Variant> {
        if (!playlist.contains("#EXTM3U")) return emptyList()
        val lines = playlist.lines().map { it.trim() }
        val out = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // The attributes may run over several lines; the URL is the next one that is not a tag.
                val attributes = StringBuilder(line.substringAfter(':'))
                var j = i + 1
                while (j < lines.size && lines[j].startsWith("#EXT-X-STREAM-INF")) {
                    attributes.append(',').append(lines[j].substringAfter(':'))
                    j++
                }
                var k = j
                while (k < lines.size && (lines[k].isEmpty() || lines[k].startsWith("#"))) k++
                if (k < lines.size) {
                    val attrs = attributes.toString()
                    out += Variant(
                        url = resolve(lines[k], baseUrl),
                        height = Regex("RESOLUTION=\\d+x(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        bandwidth = Regex("BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                        codecs = Regex("CODECS=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1),
                    )
                    i = k
                }
            }
            i++
        }
        return out
    }

    /** A relative playlist URL, as the master's own. */
    fun resolve(relative: String, baseUrl: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { java.net.URI(baseUrl).resolve(relative).toString() }.getOrDefault(relative)
    }
}
