@file:OptIn(UnstableApi::class)

package app.kuroka.host.downloads

import android.app.Notification
import android.app.NotificationManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.kuroka.host.R

/*
 * The service that carries the downloads: a foreground service with a notification that says what
 * is coming down and how far it is, started by Media3 when a download is added and stopped when
 * the queue is empty. The download manager, the cache and the notification helper are the ones of
 * [Downloads], so the service and the screens read the same thing.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class KurokaDownloadService : DownloadService(
    Downloads.FOREGROUND_SERVICE_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    Downloads.CHANNEL_ID,
    R.string.downloads_channel_name,
    NotificationManager.IMPORTANCE_LOW,
) {
    override fun getDownloadManager(): DownloadManager = Downloads.downloadManager(this)

    /** No job scheduler: the queue is short and the notification shows it anyway. */
    override fun getScheduler(): Scheduler? = null

    /** One notification for the whole queue, and what it is waiting for when it cannot run. */
    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = Downloads.notificationHelper(this).buildProgressNotification(
        this,
        android.R.drawable.stat_sys_download,
        /* contentIntent = */ null,
        /* message = */ null,
        downloads,
        notMetRequirements,
    )
}
