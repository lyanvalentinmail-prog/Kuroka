package app.kuroka

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.kuroka.di.Graph
import app.kuroka.host.KurokaHost
import app.kuroka.host.downloads.Downloads

/*
 * The application: it builds the host once (the database, the settings, the library, the account,
 * the downloads, the sources, the casting), opens the notification channels the player and the
 * downloads need, and starts the update check the settings ask for.
 *
 * Nothing here blocks the start: the channels are registered, the host reads its files on its own
 * threads, and the first frame does not wait for the catalog.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../LICENSE).
 */

class KurokaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        // The sign-in's own ids, from the build (a fork may have its own AniList client).
        KurokaHost.clientId = BuildConfig.ANILIST_CLIENT_ID
        KurokaHost.clientSecret = ""
        KurokaHost.redirectUri = BuildConfig.ANILIST_REDIRECT_URI
        KurokaHost.init(this)
        channels(this)
        // The downloads in flight are picked up again, and their notification channel is theirs.
        Downloads.warm(this)
        if (KurokaHost.prefs.now.updateCheck) Graph.updates.check(quiet = true)
    }

    companion object {
        const val CHANNEL_PLAYBACK = "kuroka.playback"
        const val CHANNEL_UPDATES = "kuroka.updates"

        /** The channels the app posts to: the player's (Media3 owns its own), updates, downloads. */
        fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            runCatching {
                manager.createNotificationChannelGroup(
                    NotificationChannelGroup("kuroka", context.getString(R.string.app_name))
                )
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_PLAYBACK,
                        context.getString(R.string.playback_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.playback_channel_description)
                        group = "kuroka"
                        setShowBadge(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_UPDATES,
                        context.getString(R.string.update_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.update_channel_description)
                        group = "kuroka"
                    }
                )
                manager.createNotificationChannel(
                    NotificationChannel(
                        Downloads.CHANNEL_ID,
                        context.getString(app.kuroka.host.R.string.downloads_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(app.kuroka.host.R.string.downloads_channel_description)
                        group = "kuroka"
                        setShowBadge(false)
                    }
                )
            }
        }
    }
}
