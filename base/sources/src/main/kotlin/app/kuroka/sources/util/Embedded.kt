package app.kuroka.sources.util

import org.json.JSONArray
import org.json.JSONObject

/*
 * The JSON a page carries inside its HTML: a Next.js server component escapes its props into the
 * document ("\"episodes\": […]"). These find the key, walk the brackets to the end of the value
 * and unescape what is between, so a provider can read a page that has no API of its own.
 *
 * Ported from Kitsune (MIT, © Dovakiin0, https://github.com/Dovakiin0/Kitsune,
 * src/lib/providers/providers/animedunya.js).
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object Embedded {

    /** The array after `"key":`, escaped or not, or null when the page does not carry it. */
    fun array(html: String, key: String): JSONArray? {
        val raw = value(html, key, '[') ?: return null
        return tryParse(raw) { JSONArray(it) } as? JSONArray
    }

    /** The object after `"key":`, escaped or not, or null. */
    fun obj(html: String, key: String): JSONObject? {
        val raw = value(html, key, '{') ?: return null
        return tryParse(raw) { JSONObject(it) } as? JSONObject
    }

    /**
     * The raw text of the value of [key] in [html], from its opening [open] bracket to the
     * bracket that closes it. Strings are skipped over in a plain JSON page; in an escaped one
     * (every quote written `\"`) the brackets alone are counted, as Kitsune does.
     */
    fun value(html: String, key: String, open: Char): String? {
        val close = if (open == '[') ']' else '}'
        // `"episodes":`, `\"episodes\":` and `"episodes" :` all appear in the wild.
        val patterns = listOf(
            "\"$key\"\\s*:\\s*",
            "\\\\\"$key\\\\\"\\s*:\\s*",
        )
        var start = -1
        for (p in patterns) {
            val m = Regex(p).find(html) ?: continue
            start = m.range.last + 1
            break
        }
        if (start < 0) return null

        // Walk to the first opening bracket: a page may put spaces or an escape before it.
        var i = start
        while (i < html.length && html[i] != open) {
            if (!html[i].isWhitespace() && html[i] != '\\') return null
            i++
        }
        if (i >= html.length) return null

        var depth = 0
        var inString = false
        var escaped = false
        val out = StringBuilder()
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else if (c == '"' && delimits(html, i)) {
                inString = true
            } else if (c == open) {
                depth++
            } else if (c == close) {
                depth--
            }
            out.append(c)
            i++
            if (depth == 0 && out.isNotEmpty()) break
        }
        if (depth != 0) return null
        return out.toString()
    }

    /**
     * Whether the quote at [i] opens or closes a JSON string: an escaped one (`\"`, written that
     * way by a server component) does not, so its brackets are counted instead.
     */
    private fun delimits(s: String, i: Int): Boolean {
        var backslashes = 0
        var j = i - 1
        while (j >= 0 && s[j] == '\\') {
            backslashes++
            j--
        }
        return backslashes % 2 == 0
    }

    /** Parses the raw text, then the unescaped one: a page may carry either. */
    private fun tryParse(raw: String, parse: (String) -> Any): Any? {
        runCatching { parse(raw) }.getOrNull()?.let { return it }
        val un = unescape(raw)
        if (un != raw) runCatching { parse(un) }.getOrNull()?.let { return it }
        return null
    }

    /** `\"` → `"`, `\/` → `/`, `\u0026` → `&`, `\\` → `\`, in the order the escapes nest. */
    fun unescape(s: String): String =
        s.replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

    /** One string field of a page, quoted or escaped (`"source":"…"`, `\"source\":\"…\"`). */
    fun string(html: String, key: String): String? {
        val patterns = listOf(
            "\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            "\\\\\"$key\\\\\"\\s*:\\s*\\\\\"((?:[^\"\\\\]|\\\\.)*)\\\\\"",
        )
        for (p in patterns) {
            val m = Regex(p).find(html) ?: continue
            val v = unescape(m.groupValues[1])
            if (v.isNotBlank()) return v
        }
        return null
    }

    /** One number or boolean field of a page (`"episodeNumber":12`, `\"filler\":true`). */
    fun scalar(html: String, key: String): String? {
        val m = Regex("\\\\?\"$key\\\\?\"\\s*:\\s*(\\\\?\"[^\"\\\\]*\\\\?\"|[-\\d.]+|true|false|null)").find(html)
            ?: return null
        val v = m.groupValues[1].replace("\\\"", "").trim('"')
        return if (v.isBlank() || v == "null") null else v
    }

    /** The value of an attribute of an HTML tag (`<a class="name" href="/watch/x">`). */
    fun attr(tag: String, name: String): String? {
        val escaped = name.replace(Regex("[.*+?^\${}()|[\\]\\\\]"), "\\\\$0")
        val m = Regex("\\b$escaped\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.getOrNull(1)?.let { decodeEntities(it) }
    }

    /** The content of a `<meta name="…" content="…">`. */
    fun metaContent(html: String, name: String): String? {
        for (t in Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            if (attr(t.value, "name")?.equals(name, ignoreCase = true) == true) return attr(t.value, "content")
            if (attr(t.value, "property")?.equals(name, ignoreCase = true) == true) return attr(t.value, "content")
        }
        return null
    }

    /** The cookies a `Set-Cookie` list carries, as one `a=b; c=d` header. */
    fun cookieHeader(values: List<String>): String =
        values.map { it.substringBefore(';').trim() }.filter { it.isNotBlank() && it.contains('=') }
            .distinct().joinToString("; ")

    /** The value of one cookie in a `Set-Cookie` header. */
    fun cookieValue(values: List<String>, name: String): String? {
        for (v in values) {
            val m = Regex("(?:^|;\\s*)$name=([^;]+)").find(v) ?: continue
            return m.groupValues[1]
        }
        return null
    }
}
