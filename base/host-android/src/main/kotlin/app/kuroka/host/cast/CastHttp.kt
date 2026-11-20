package app.kuroka.host.cast

/**
 * What the little HTTP server that feeds a Chromecast needs of HTTP, written by hand: the `Range`
 * header a video player asks for, the `Content-Range` and 206 answer it wants, and the content
 * type that goes with a stream's bytes.
 *
 * The structure is Orpheon's (GPL-3.0, © kota-zo, https://github.com/kota-zo/Orpheon,
 * base/app/src/main/java/com/music/spotui/cast/CastHttp.kt); the mime table is Kuroka's, for the
 * video and subtitle formats the providers serve.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */
internal object CastHttp {

    /** A byte range, or null when the whole thing is wanted. */
    data class ByteRange(val start: Long, val endInclusive: Long?) {
        fun contentRange(length: Long): String {
            val end = endInclusive ?: (length - 1)
            return "bytes $start-$end/$length"
        }

        /** How many bytes this range covers, given the whole length (-1 when open-ended). */
        fun size(length: Long): Long {
            val end = endInclusive ?: (length - 1)
            return if (end < start) 0L else end - start + 1
        }

        companion object {
            /** Reads `bytes=0-1023`, `bytes=500-` and `bytes=-1024`. Anything else: null. */
            fun parse(header: String?): ByteRange? {
                if (header.isNullOrBlank()) return null
                val value = header.trim()
                if (!value.startsWith("bytes=", ignoreCase = true)) return null
                val spec = value.substring(6).trim().substringBefore(',')
                val dash = spec.indexOf('-')
                if (dash < 0) return null
                val first = spec.substring(0, dash).trim()
                val last = spec.substring(dash + 1).trim()
                return when {
                    first.isEmpty() && last.isEmpty() -> null
                    // `bytes=-N`: the last N bytes.
                    first.isEmpty() -> {
                        val n = last.toLongOrNull() ?: return null
                        if (n <= 0) null else ByteRange(-n, null)
                    }

                    else -> {
                        val start = first.toLongOrNull() ?: return null
                        ByteRange(start, last.toLongOrNull())
                    }
                }
            }
        }
    }

    /** A range whose start is counted from the end, made absolute against [length]. */
    fun resolve(range: ByteRange, length: Long): ByteRange =
        if (range.start < 0) ByteRange((length + range.start).coerceAtLeast(0L), length - 1) else range

    /** Whether a range fits a thing of [length] bytes. */
    fun satisfiable(range: ByteRange, length: Long): Boolean {
        val resolved = resolve(range, length)
        return resolved.start < length && (resolved.endInclusive ?: (length - 1)) >= resolved.start
    }

    fun headers(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("\r\n") { (k, v) -> "$k: $v" } + "\r\n"

    fun status(code: Int, reason: String, vararg pairs: Pair<String, String>): String =
        "HTTP/1.1 $code $reason\r\n" + headers(*pairs) + "\r\n"

    fun head(path: String): String {
        val query = path.indexOf('?')
        return if (query >= 0) path.substring(0, query) else path
    }

    /** The mime of a name, when the name says it. */
    fun mimeOfName(name: String): String? {
        val lower = name.lowercase().substringBefore('?')
        return when {
            lower.endsWith(".m3u8") -> "application/x-mpegURL"
            lower.endsWith(".m3u") -> "audio/x-mpegurl"
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".m4s") -> "video/iso.segment"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".vtt") -> "text/vtt"
            lower.endsWith(".srt") -> "application/x-subrip"
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> "text/x-ssa"
            lower.endsWith(".sub") -> "text/plain"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            else -> null
        }
    }

    /** The content type a Cast receiver wants for an HLS playlist, and its children. */
    fun streamMime(url: String): String = mimeOfName(url) ?: "video/mp4"

    fun isPlaylist(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?')
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u")
    }
}
