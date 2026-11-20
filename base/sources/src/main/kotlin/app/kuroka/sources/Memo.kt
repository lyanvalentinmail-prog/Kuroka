package app.kuroka.sources

import java.util.concurrent.ConcurrentHashMap

/*
 * A small in-memory cache with a life of its own choosing: a page asks for the same anime's
 * seasons, relations and episode guide several times in a row, and a provider asks for the same
 * MAL id for every episode. What is fresh is given back without a call; what is stale is asked
 * again. It dies with the process, as a browser's memory cache does.
 *
 * After Kitsune's smartcache (MIT, © Dovakiin0, https://github.com/Dovakiin0/Kitsune,
 * src/lib/providers/core/smartcache.js).
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object Memo {
    /** An anime's identity (its MAL id, its episode guide): a day. */
    const val DAY = 24 * 60 * 60 * 1000L

    /** An episode list: an hour, so a new episode of an airing anime shows up soon. */
    const val HOUR = 60 * 60 * 1000L

    /** A stream: five minutes, since a provider's links go stale. */
    const val STREAM = 5 * 60 * 1000L

    /** A search: two minutes, so typing the rest of a word does not ask again. */
    const val SEARCH = 2 * 60 * 1000L

    private class Entry(val at: Long, val value: Any?)

    private val map = ConcurrentHashMap<String, Entry>()
    /** At most this many answers are kept; the oldest go first. */
    private const val MAX = 400

    /** The value under [key], or [load]'s, kept for [ttl] milliseconds. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, ttl: Long, load: suspend () -> T?): T? {
        val now = System.currentTimeMillis()
        val hit = map[key]
        if (hit != null && now - hit.at < ttl) return hit.value as T?
        val value = load()
        if (value != null) put(key, value, now)
        return value
    }

    private fun put(key: String, value: Any?, now: Long) {
        if (map.size >= MAX) {
            val oldest = map.entries.minByOrNull { it.value.at }?.key
            if (oldest != null) map.remove(oldest)
        }
        map[key] = Entry(now, value)
    }

    /** Forgets the answers under [prefix] ("" forgets them all). */
    fun forget(prefix: String = "") {
        if (prefix.isEmpty()) map.clear() else map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) }
    }
}
