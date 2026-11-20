package app.kuroka.host.downloads

import app.kuroka.sources.net.Http
import java.util.concurrent.ConcurrentHashMap

/*
 * The headers a stream wants, remembered for its whole host: a provider answers with a playlist
 * that needs a Referer, and every segment of that playlist needs the same one, though their URLs
 * are not the playlist's. Both the player and the downloads read the headers from here, so a
 * stream is fetched the same way whichever of the two asks for it.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object StreamHeaders {

    private val byUrl = ConcurrentHashMap<String, Map<String, String>>()
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    /** The headers of a page request: what a CDN expects of a player. */
    val default: Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    /**
     * Remembers the headers of [url] (and of its host, for the segments and the keys of a
     * playlist that live elsewhere on the same host).
     */
    fun remember(url: String, headers: Map<String, String>) {
        if (url.isBlank()) return
        val full = if (headers.containsKey("User-Agent")) headers else headers + ("User-Agent" to Http.UA)
        byUrl[url] = full
        hostOf(url)?.let { byHost[it] = full }
        // A playlist's own URL is asked for again with its query; keep the bare one too.
        val bare = url.substringBefore('?')
        if (bare != url) byUrl[bare] = full
        trim()
    }

    /** The headers to fetch [url] with: its own, else its host's, else a browser's. */
    fun forUrl(url: String): Map<String, String> {
        byUrl[url]?.let { return it }
        byUrl[url.substringBefore('?')]?.let { return it }
        hostOf(url)?.let { h -> byHost[h]?.let { return it } }
        // A relative or unusual URL: whatever was remembered last, since a player asks for one
        // stream at a time and its segments follow it.
        return byUrl.values.lastOrNull() ?: default
    }

    /** The headers of the stream the player resolved last (a fallback for a segment elsewhere). */
    @Volatile var last: Map<String, String> = default
        private set

    fun rememberCurrent(url: String, headers: Map<String, String>) {
        remember(url, headers)
        last = forUrl(url)
    }

    fun clear() {
        byUrl.clear()
        byHost.clear()
        last = default
    }

    private fun hostOf(url: String): String? = runCatching {
        val withoutScheme = url.substringAfter("://", url)
        withoutScheme.substringBefore('/').substringBefore('?').substringBeforeLast(':')
    }.getOrNull()?.takeIf { it.isNotBlank() && it.contains('.') }

    /** The maps stay small: a phone plays one stream at a time and downloads a handful. */
    private fun trim() {
        while (byUrl.size > 200) {
            val oldest = byUrl.keys.firstOrNull() ?: break
            byUrl.remove(oldest)
        }
        while (byHost.size > 60) {
            val oldest = byHost.keys.firstOrNull() ?: break
            byHost.remove(oldest)
        }
    }
}
