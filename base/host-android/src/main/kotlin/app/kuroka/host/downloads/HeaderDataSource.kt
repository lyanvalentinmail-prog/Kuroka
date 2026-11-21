@file:OptIn(UnstableApi::class)

package app.kuroka.host.downloads

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import app.kuroka.sources.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/*
 * The data source every byte of a stream goes through, on the phone and on a Chromecast's behalf:
 * OkHttp with the headers of the stream's host (a Referer, a token, an Accept-Language), so a
 * playlist and its segments are fetched as the provider wants them. One class, so the player and
 * the downloads read exactly the same way.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class HeaderDataSource(private val client: OkHttpClient) : BaseDataSource(/* isNetwork = */ true) {

    private var response: Response? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var remaining = -1L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val wanted = StreamHeaders.forUrl(url)
        val builder = Request.Builder().url(url)
        wanted.forEach { (k, v) -> builder.header(k, v) }
        // One range per request, as a player and a downloader both ask for.
        val position = dataSpec.position
        val length = dataSpec.length
        when {
            length >= 0 -> builder.header("Range", "bytes=$position-${position + length - 1}")
            position > 0 -> builder.header("Range", "bytes=$position-")
        }
        val answer = client.newCall(builder.build()).execute()
        response = answer
        headers = answer.headers.toMultimap()
        val code = answer.code
        if (code != 200 && code != 206) {
            val body = runCatching { answer.body?.string() ?: "" }.getOrDefault("")
            answer.close()
            throw IOException("HTTP $code for $url ${body.take(200)}")
        }
        val stream = answer.body?.byteStream() ?: throw IOException("no body for $url")
        // A server that ignored the Range header answered from the start: skip what was asked for.
        var toSkip = if (code == 200 && position > 0) position else 0L
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped <= 0) {
                // A stream that will not skip is read and thrown away, so the offset is right.
                val read = stream.read(ByteArray(minOf(toSkip, 8192).toInt()))
                if (read < 0) break
                toSkip -= read
            } else {
                toSkip -= skipped
            }
        }
        input = stream
        val contentLength = answer.body?.contentLength() ?: -1L
        remaining = when {
            length >= 0 -> length
            code == 206 && contentLength >= 0 -> contentLength
            contentLength >= 0 -> contentLength
            else -> -1L
        }
        opened = true
        transferStarted(dataSpec)
        // What open answers is how many bytes will come: the range's own, or no promise at all.
        return if (remaining >= 0) remaining else C.LENGTH_UNSET
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val ask = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
        val read = stream.read(buffer, offset, ask)
        if (read == -1) {
            if (remaining > 0) throw IOException("stream ended early")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining > 0) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = if (opened) uri else null

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        if (!opened) return
        opened = false
        runCatching { input?.close() }
        runCatching { response?.close() }
        input = null
        response = null
        transferEnded()
    }

    /** The factory of it: one OkHttp client for every request the player and the downloads make. */
    class Factory : DataSource.Factory {
        override fun createDataSource(): HeaderDataSource = HeaderDataSource(client)

        companion object {
            val client: OkHttpClient by lazy {
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // A stream is fetched by one connection at a time; a playlist opens several.
                    .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 12; maxRequestsPerHost = 8 })
                    .build()
            }
        }
    }
}
