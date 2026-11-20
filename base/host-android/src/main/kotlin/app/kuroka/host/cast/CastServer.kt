package app.kuroka.host.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * The little HTTP server that feeds a Chromecast. A Cast device fetches the stream itself, from an
 * address it can reach — and a provider's stream needs headers it cannot send (a Referer, a
 * cookie), and its playlists point at addresses the device cannot either. So the phone stands in
 * between: the receiver is given an address of this server, which fetches from the provider with
 * the right headers, rewrites every playlist line to another address of its own, honours `Range`
 * (a video player seeks), and turns an `.srt` into the WebVTT a receiver draws.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

class CastServer(private val client: OkHttpClient) {

    /** One thing the device may ask for: a provider address and the headers that open it. */
    private class Route(
        val id: String,
        val url: String,
        val headers: Map<String, String>,
        val kind: Kind,
    )

    enum class Kind { VIDEO, PLAYLIST, SUBTITLE, IMAGE }

    /** The address of this server on the network the device is on, or null when it is not up. */
    @Volatile
    var baseUrl: String? = null
        private set

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "kuroka-cast-http").apply { isDaemon = true } }
    private val routes = ConcurrentHashMap<String, Route>()
    private val ids = AtomicInteger(1)
    private val random = SecureRandom()
    /** A token in every path: only a device that was given this address can ask for anything. */
    private val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Starts listening on [address] (the phone's address on the device's network). */
    fun start(address: InetAddress): Boolean {
        stop()
        return try {
            val socket = ServerSocket(0, 16, address)
            socket.soTimeout = 0
            server = socket
            baseUrl = "http://${address.hostAddress}:${socket.localPort}"
            val t = Thread({ acceptLoop(socket) }, "kuroka-cast-accept").apply { isDaemon = true }
            thread = t
            t.start()
            true
        } catch (e: Exception) {
            baseUrl = null
            false
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
        baseUrl = null
        routes.clear()
        runCatching { pool.shutdownNow() }
    }

    /** An address of this server for [url], which the device fetches with [headers] applied. */
    fun route(url: String, headers: Map<String, String>, kind: Kind = kindOf(url)): String? {
        val base = baseUrl ?: return null
        val existing = routes.values.firstOrNull { it.url == url && it.kind == kind }
        val route = existing ?: Route("r${ids.getAndIncrement()}", url, headers, kind)
            .also { routes[it.id] = it }
        return "$base/k/$token/${route.id}"
    }

    /** The provider address behind one of this server's addresses (so a session can be logged). */
    fun sourceOf(url: String): String? = routes.values.firstOrNull { "$url".endsWith("/${it.id}") }?.url

    private fun kindOf(url: String): Kind {
        val lower = url.lowercase().substringBefore('?')
        return when {
            lower.endsWith(".m3u8") || lower.endsWith(".m3u") -> Kind.PLAYLIST
            lower.endsWith(".vtt") || lower.endsWith(".srt") || lower.endsWith(".ass") ||
                lower.endsWith(".ssa") || lower.endsWith(".sub") -> Kind.SUBTITLE

            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") -> Kind.IMAGE

            else -> Kind.VIDEO
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (e: Exception) {
                if (socket.isClosed) return
                continue
            }
            pool.execute {
                try {
                    serve(connection)
                } catch (e: Exception) {
                    // A receiver that hung up mid-answer is normal; nothing to do.
                } finally {
                    runCatching { connection.close() }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1]
        val requestHeaders = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) requestHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val segments = CastHttp.head(path).split('/').filter { it.isNotEmpty() }
        if (segments.size < 3 || segments[0] != "k" || segments[1] != token) {
            write(output, CastHttp.status(404, "Not Found", "Content-Length" to "0", "Connection" to "close"))
            return
        }
        val route = routes[segments[2]]
        if (route == null) {
            write(output, CastHttp.status(404, "Not Found", "Content-Length" to "0", "Connection" to "close"))
            return
        }
        when {
            method != "GET" && method != "HEAD" ->
                write(output, CastHttp.status(405, "Method Not Allowed", "Allow" to "GET, HEAD", "Content-Length" to "0", "Connection" to "close"))

            route.kind == Kind.PLAYLIST -> servePlaylist(route, method == "HEAD", output)
            route.kind == Kind.SUBTITLE -> serveSubtitle(route, method == "HEAD", requestHeaders, output)
            else -> serveBytes(route, method == "HEAD", requestHeaders, output)
        }
    }

    /** A playlist: fetched, rewritten so every line is an address of this server, and served. */
    private fun servePlaylist(route: Route, headOnly: Boolean, output: OutputStream) {
        val body = fetchText(route) ?: run {
            write(output, CastHttp.status(502, "Bad Gateway", "Content-Length" to "0", "Connection" to "close"))
            return
        }
        val rewritten = rewrite(body, route)
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        val head = CastHttp.status(
            200, "OK",
            "Content-Type" to "application/x-mpegURL",
            "Content-Length" to bytes.size.toString(),
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store",
            "Connection" to "close",
        )
        write(output, head)
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    /** Every address in a playlist becomes one of this server's, of the right kind. */
    private fun rewrite(playlist: String, route: Route): String {
        val base = baseUrl ?: return playlist
        val out = StringBuilder()
        var pendingKey = false
        playlist.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") ||
                    line.startsWith("#EXT-X-BYTERANGE") -> {
                    pendingKey = line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")
                    out.appendLine(rewriteAttributes(line, route))
                }

                line.startsWith("#") -> out.appendLine(line)
                line.isEmpty() -> Unit
                else -> {
                    val absolute = resolve(route.url, line)
                    val kind = if (pendingKey) Kind.VIDEO else kindOf(absolute)
                    val mapped = route(absolute, route.headers, kind) ?: absolute
                    out.appendLine(mapped)
                    pendingKey = false
                }
            }
        }
        if (base.isBlank()) return playlist
        return out.toString()
    }

    /** The `URI="…"` of an `#EXT-X-KEY` or `#EXT-X-MAP` line, pointed at this server. */
    private fun rewriteAttributes(line: String, route: Route): String {
        val uriAt = line.indexOf("URI=\"")
        if (uriAt < 0) return line
        val from = uriAt + 5
        val to = line.indexOf('"', from)
        if (to < 0) return line
        val value = line.substring(from, to)
        val absolute = resolve(route.url, value)
        val mapped = route(absolute, route.headers, kindOf(absolute)) ?: absolute
        return line.substring(0, from) + mapped + line.substring(to)
    }

    /** A subtitle: fetched, and an `.srt` turned into the WebVTT a receiver can draw. */
    private fun serveSubtitle(route: Route, headOnly: Boolean, requestHeaders: Map<String, String>, output: OutputStream) {
        val text = fetchText(route) ?: run {
            write(output, CastHttp.status(404, "Not Found", "Content-Length" to "0", "Connection" to "close"))
            return
        }
        val vtt = if (text.startsWith("WEBVTT")) text else srtToVtt(text)
        val bytes = vtt.toByteArray(Charsets.UTF_8)
        write(
            output, CastHttp.status(
                200, "OK",
                "Content-Type" to "text/vtt; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
                "Connection" to "close",
            )
        )
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    /** `00:01:02,345 --> 00:01:04,567` becomes `00:01:02.345 --> 00:01:04.567`, under a WEBVTT head. */
    private fun srtToVtt(srt: String): String {
        val out = StringBuilder("WEBVTT\n\n")
        srt.lineSequence().forEach { line ->
            if (line.contains("-->")) out.appendLine(line.replace(',', '.'))
            else if (line.trim().all { it.isDigit() } && line.trim().length <= 5) Unit // the cue number
            else out.appendLine(line)
        }
        return out.toString()
    }

    /**
     * The bytes of a stream, with `Range`: the header is passed on to the provider, and when the
     * provider answers the whole thing anyway (some CDNs do) the unwanted head is skipped and a
     * 206 of the right size is written, which is what a seeking video player expects.
     */
    private fun serveBytes(route: Route, headOnly: Boolean, requestHeaders: Map<String, String>, output: OutputStream) {
        val range = CastHttp.parse(requestHeaders["range"])
        val builder = Request.Builder().url(route.url)
        route.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Connection", "close")
        if (range != null) builder.header("Range", if (range.start < 0) "bytes=${range.start}" else
            if (range.endInclusive == null) "bytes=${range.start}-" else "bytes=${range.start}-${range.endInclusive}")
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            write(output, CastHttp.status(502, "Bad Gateway", "Content-Length" to "0", "Connection" to "close"))
            return
        }
        response.use { r ->
            if (!r.isSuccessful && r.code != 206) {
                write(output, CastHttp.status(502, "Bad Gateway", "Content-Length" to "0", "Connection" to "close"))
                return
            }
            val body = r.body ?: run {
                write(output, CastHttp.status(502, "Bad Gateway", "Content-Length" to "0", "Connection" to "close"))
                return
            }
            val contentLength = body.contentLength()
            val contentType = r.header("Content-Type") ?: CastHttp.mimeOfName(route.url) ?: "video/mp4"
            val resolved = if (range != null && contentLength > 0) CastHttp.resolve(range, contentLength) else null
            val upstream206 = r.code == 206
            val statusLine = when {
                upstream206 -> CastHttp.status(
                    206, "Partial Content",
                    "Content-Type" to contentType,
                    "Content-Range" to (r.header("Content-Range") ?: resolved?.contentRange(contentLength) ?: ""),
                    "Content-Length" to (r.header("Content-Length") ?: contentLength.toString()),
                    "Accept-Ranges" to "bytes",
                    "Access-Control-Allow-Origin" to "*",
                    "Connection" to "close",
                )

                resolved != null -> {
                    if (!CastHttp.satisfiable(resolved, contentLength)) {
                        write(output, CastHttp.status(416, "Range Not Satisfiable",
                            "Content-Range" to "bytes */$contentLength", "Content-Length" to "0", "Connection" to "close"))
                        return
                    }
                    CastHttp.status(
                        206, "Partial Content",
                        "Content-Type" to contentType,
                        "Content-Range" to resolved.contentRange(contentLength),
                        "Content-Length" to resolved.size(contentLength).toString(),
                        "Accept-Ranges" to "bytes",
                        "Access-Control-Allow-Origin" to "*",
                        "Connection" to "close",
                    )
                }

                else -> CastHttp.status(
                    200, "OK",
                    "Content-Type" to contentType,
                    "Content-Length" to (if (contentLength >= 0) contentLength.toString() else ""),
                    "Accept-Ranges" to "bytes",
                    "Access-Control-Allow-Origin" to "*",
                    "Connection" to "close",
                )
            }
            write(output, statusLine)
            if (headOnly) return
            val input = body.byteStream()
            if (resolved != null && resolved.start > 0) skip(input, resolved.start)
            copy(input, output, resolved?.let { it.size(contentLength) })
        }
    }

    private fun skip(input: InputStream, bytes: Long) {
        var left = bytes
        val buffer = ByteArray(32 * 1024)
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) return
            left -= read
        }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long?) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val want = if (limit == null) buffer.size else {
                val left = limit - written
                if (left <= 0) return
                minOf(buffer.size.toLong(), left).toInt()
            }
            val read = input.read(buffer, 0, want)
            if (read < 0) return
            output.write(buffer, 0, read)
            written += read
        }
    }

    private fun fetchText(route: Route): String? = try {
        val builder = Request.Builder().url(route.url)
        route.headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    } catch (e: Exception) {
        null
    }

    private fun write(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        /** `b` against `a`, as a playlist's relative lines are. */
        fun resolve(a: String, b: String): String {
            if (b.startsWith("http://") || b.startsWith("https://")) return b
            return runCatching { URI(a).resolve(b).toString() }.getOrDefault(b)
        }
    }
}

/** Runs a block off the main thread (the server's fetches are blocking). */
internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
