package app.kuroka.sources.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/*
 * The HTTP the sources speak: one OkHttp client with a browser's User-Agent, the cookies a site
 * sets (kept in memory for the life of the process), retries on a network hiccup and answers as
 * JSON or text. Every provider and the catalog go through here, so a header, a timeout or a
 * retry is fixed once.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

/** A request that did not answer 2xx, with the body it did send (a provider's error page). */
class HttpStatusException(val code: Int, val url: String, val body: String = "") :
    IOException("HTTP $code for $url")

object Http {
    /** What a real Chrome on Windows sends: several providers refuse anything else. */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    /** One client for the whole app: connections and cookies are shared by the sources. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(MemoryCookieJar)
            .build()
    }

    /** The cookies the sites have set, by host. They die with the process, as a browser tab's do. */
    private object MemoryCookieJar : CookieJar {
        private val jar = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val list = jar.getOrPut(url.host) { java.util.Collections.synchronizedList(ArrayList()) }
            cookies.forEach { c ->
                list.removeAll { it.name == c.name }
                list.add(c)
            }
            // A host that keeps setting cookies must not grow the list for good.
            while (list.size > 24) list.removeAt(0)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val list = jar[url.host] ?: return emptyList()
            return list.filter { it.expiresAt > now }
        }

        /** Forgets what a host set (a sign-out, a session that went stale). */
        fun clear(host: String? = null) {
            if (host == null) jar.clear() else jar.remove(host)
        }
    }

    fun clearCookies(host: String? = null) = MemoryCookieJar.clear(host)

    /** The headers of a browser page request, which most scraped sites expect. */
    fun browserHeaders(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = UA
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        h["Accept-Language"] = "en-US,en;q=0.9"
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        h.putAll(extra)
        return h
    }

    /** The headers of a JSON API call. */
    fun jsonHeaders(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = UA
        h["Accept"] = "application/json, text/plain, */*"
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        h.putAll(extra)
        return h
    }

    private fun request(
        url: String,
        headers: Map<String, String>,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
    ): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return when (method) {
            "POST" -> b.post(body ?: ByteArray(0).toRequestBody(null)).build()
            "PUT" -> b.put(body ?: ByteArray(0).toRequestBody(null)).build()
            "DELETE" -> b.delete(body).build()
            "HEAD" -> b.head().build()
            else -> b.get().build()
        }
    }

    /**
     * One request, answered as text. [tries] attempts, the second one after a short wait: a
     * provider behind a busy CDN answers 503 once in a while and is fine a second later.
     */
    suspend fun text(
        url: String,
        headers: Map<String, String> = browserHeaders(),
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        tries: Int = 2,
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        var attempt = 0
        while (attempt < tries) {
            attempt++
            try {
                client.newCall(request(url, headers, method, body)).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) throw HttpStatusException(response.code, url, text.take(4000))
                    return@withContext text
                }
            } catch (e: HttpStatusException) {
                // A 4xx is an answer, not a hiccup: only a 5xx or a 429 is worth another try.
                if (e.code in 400..499 && e.code != 429) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < tries) Thread.sleep(350L * attempt)
        }
        throw last ?: IOException("no answer for $url")
    }

    /** One request, answered as a JSON object. */
    suspend fun json(
        url: String,
        headers: Map<String, String> = jsonHeaders(),
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        tries: Int = 2,
    ): JSONObject = JSONObject(text(url, headers, method, body, tries))

    /** An answer with its headers, for the sites that put a session in one of them. */
    data class Raw(val code: Int, val body: String, val headers: Map<String, List<String>>, val url: String) {
        val isOk: Boolean get() = code in 200..299
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        fun headers(name: String): List<String> =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
    }

    /** One request with the response headers kept (a `Set-Cookie`, a `Location`, a token). */
    suspend fun raw(
        url: String,
        headers: Map<String, String> = browserHeaders(),
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
    ): Raw = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers, method, body)).execute().use { response ->
            Raw(
                code = response.code,
                body = response.body?.string() ?: "",
                headers = response.headers.toMultimap(),
                url = response.request.url.toString(),
            )
        }
    }

    /** A JSON body for a POST (the GraphQL endpoints, the token exchanges). */
    fun jsonBody(json: JSONObject): okhttp3.RequestBody =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    fun formBody(form: Map<String, String>): okhttp3.RequestBody {
        val b = FormBody.Builder()
        form.forEach { (k, v) -> b.add(k, v) }
        return b.build()
    }

    /**
     * A GraphQL answer's data, or the error it sent instead (a rate limit, a bad variable), as an
     * exception the caller can show.
     */
    fun graphqlData(answer: JSONObject, what: String): JSONObject {
        val errors = answer.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val first = errors.optJSONObject(0)
            throw IOException("$what: ${first?.optString("message") ?: errors.optString(0)}")
        }
        return answer.optJSONObject("data") ?: throw IOException("$what: no data")
    }

    /** A POST of a GraphQL query, answered with its `data`. */
    suspend fun graphql(
        endpoint: String,
        query: String,
        variables: JSONObject = JSONObject(),
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        what: String = "graphql",
    ): JSONObject {
        val h = LinkedHashMap(jsonHeaders())
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json"
        if (!token.isNullOrBlank()) h["Authorization"] = "Bearer $token"
        h.putAll(headers)
        val body = JSONObject().put("query", query).put("variables", variables)
        return graphqlData(json(endpoint, h, "POST", jsonBody(body)), what)
    }
}

/** Reads a JSONArray as a list, skipping the holes (a provider's answer with a null in it). */
fun <T> JSONArray.mapList(f: (JSONObject) -> T?): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        f(o)?.let { out += it }
    }
    return out
}

/** Reads a JSONArray of strings. */
fun JSONArray.stringList(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let { out += it }
    return out
}

/** `optString` without the "null" string JSONObject makes of a JSON null. */
fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** `optInt` as a nullable, for the fields a catalog leaves out. */
fun JSONObject.int(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

fun JSONObject.long(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)

fun JSONObject.double(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key)

fun JSONObject.bool(key: String): Boolean = !isNull(key) && optBoolean(key)
