@file:OptIn(UnstableApi::class)

package app.kuroka.host.downloads

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import java.io.File
import java.util.concurrent.Executors

/*
 * The offline side of the app: one cache folder the downloads live in, one data source factory
 * that reads it (and the network behind it) for both the player and the downloads, and Media3's
 * download manager with its service and its notification.
 *
 * Nothing is evicted from the cache by itself: a download is the user's, and it goes when they
 * remove it (a budget in Settings, Downloads says when a new one does not fit).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

object Downloads {

    const val CHANNEL_ID = "kuroka.downloads"
    const val FOREGROUND_SERVICE_ID = 4201
    const val JOB_ID = 4202

    private var cache: SimpleCache? = null
    private var databaseProvider: DatabaseProvider? = null
    private var manager: DownloadManager? = null
    private var notificationHelper: DownloadNotificationHelper? = null
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "kuroka-download").apply { isDaemon = true }
    }

    /** The folder the downloads live in (the app's own, so no permission is needed). */
    fun folder(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }

    fun databaseProvider(context: Context): DatabaseProvider =
        databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also { databaseProvider = it }

    /** The cache of streams and downloads, opened once. */
    @Synchronized
    fun cache(context: Context): Cache {
        cache?.let { return it }
        val created = SimpleCache(folder(context), NoOpCacheEvictor(), databaseProvider(context))
        cache = created
        return created
    }

    /** The network, with the headers of every host. */
    fun network(): DataSource.Factory = HeaderDataSource.Factory()

    /**
     * What the player reads: the cache first, the network behind it. A stream that has been
     * downloaded plays from the phone; one that has not is fetched, and a failure of the cache
     * (a full disk) falls back to the network.
     */
    fun playback(context: Context): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(network())
        .setCacheReadDataSourceFactory(FileDataSource.Factory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** What a download writes with: the network, into the cache. */
    private fun downloadSink(context: Context) = CacheDataSink.Factory().setCache(cache(context))

    /** The download manager of the app: one for the whole process, as Media3 asks for. */
    @Synchronized
    fun downloadManager(context: Context): DownloadManager {
        manager?.let { return it }
        val app = context.applicationContext
        val created = DownloadManager(
            app,
            databaseProvider(app),
            downloadFactory(app),
            FileDataSource.Factory(),
            executor,
        )
        created.maxParallelDownloads = 2
        manager = created
        return created
    }

    /** The network-and-cache factory a download is written through. */
    private fun downloadFactory(context: Context): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(network())
        .setCacheWriteDataSinkFactory(downloadSink(context))
        .setCacheReadDataSourceFactory(FileDataSource.Factory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Synchronized
    fun notificationHelper(context: Context): DownloadNotificationHelper =
        notificationHelper ?: DownloadNotificationHelper(context.applicationContext, CHANNEL_ID).also {
            notificationHelper = it
        }

    /** How many bytes the downloads take, for the budget in Settings. */
    fun bytesOnDisk(context: Context): Long {
        val dir = folder(context)
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /**
     * Builds the cache and the download manager now, at the start of the app: a download that was
     * in flight when the process ended is picked up again by the service, and the first download a
     * user asks for does not pay for the cache being opened.
     */
    fun warm(context: Context) {
        runCatching {
            cache(context)
            downloadManager(context)
        }
    }

    /** A DataSpec of one subtitle file, so a download's subtitles are fetched the same way. */
    fun subtitleSpec(url: String): DataSpec = DataSpec.Builder().setUri(url).build()

    /** Lets go of everything (a sign-out of the app's own data does not; only a process end). */
    @Synchronized
    fun release() {
        runCatching { manager?.release() }
        manager = null
        runCatching { cache?.release() }
        cache = null
        notificationHelper = null
    }
}
