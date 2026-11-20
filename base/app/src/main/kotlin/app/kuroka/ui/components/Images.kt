package app.kuroka.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import app.kuroka.di.Graph
import app.kuroka.sources.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Posters, banners and avatars: one small loader of Kuroka's own, rather than an image library —
 * a poster here is a URL, sometimes behind a CDN that wants a referer, and the shapes the screens
 * need are three (a 2:3 poster, a wide banner, a square avatar).
 *
 * It keeps what it has: a memory cache sized to an eighth of the heap, an HTTP cache on disk, and
 * a decode that downsamples to the size the composable is drawn at, so a 1000 × 1400 poster in a
 * 120 dp card is never decoded at its full size.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

object Posters {

    private val memory = android.util.LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 8) / 1024).toInt().coerceAtLeast(1024)
    )

    /** A client of its own: posters must not wait behind a stream's connection. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(Graph.context.cacheDir, "posters"), 60L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher(Executors.newFixedThreadPool(4)).apply {
                maxRequests = 8
                maxRequestsPerHost = 4
            })
            .build()
    }

    /** A poster nobody has asked for yet is worth nothing: the key is the URL and its size. */
    private fun key(url: String, width: Int) = "$url@$width"

    fun cached(url: String, width: Int): Bitmap? = memory.get(key(url, width))

    /** Takes a poster down and decodes it at [target] pixels wide (or full size when 0). */
    suspend fun load(url: String, target: IntSize, referer: String? = null): Bitmap? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            memory.get(key(url, target.width))?.let { return@withContext it }
            val bytes = runCatching {
                val builder = Request.Builder().url(url).header("User-Agent", Http.UA)
                referer?.let { builder.header("Referer", it) }
                client.newCall(builder.build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                }
            }.getOrNull() ?: return@withContext null
            val bitmap = decode(bytes, target.width) ?: return@withContext null
            memory.put(key(url, target.width), bitmap)
            bitmap
        }

    /** Decodes at most [widthPx] across: an option read first, then the real decode. */
    private fun decode(bytes: ByteArray, widthPx: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        var sample = 1
        if (widthPx > 0 && options.outWidth > 0) {
            while (options.outWidth / (sample * 2) >= widthPx) sample *= 2
        }
        val full = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, full) }.getOrNull()
    }

    /** Lets go of the memory cache (Settings, Storage, "Free the caches"). */
    fun clear() {
        memory.evictAll()
        runCatching { client.cache?.evictAll() }
    }
}

/**
 * A poster, a banner or an avatar: [url] drawn into [modifier]'s box, cropped, fading in when it
 * arrives, over [placeholder]'s grey. A URL that does not answer leaves the grey, as a shelf of
 * posters with broken-image icons in it is worse than a shelf of greys.
 */
@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    referer: String? = null,
    widthPx: Int = 0,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = url, key2 = widthPx) {
        if (url.isNullOrBlank()) return@produceState
        val wanted = if (widthPx > 0) IntSize(widthPx, 0) else IntSize((480 * density).toInt(), 0)
        value = Posters.load(url, wanted, referer)?.asImageBitmap()
    }
    val bitmap = state.value
    val alpha by animateFloatAsState(
        targetValue = if (bitmap == null) 0f else 1f,
        animationSpec = tween(220),
        label = "poster",
    )
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().alpha(alpha),
            )
        } else {
            // The grey a poster that is coming (or never came) shows.
            Image(
                painter = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
