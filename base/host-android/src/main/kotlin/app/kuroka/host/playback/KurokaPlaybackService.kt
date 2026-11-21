package app.kuroka.host.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/*
 * The service that puts a media session over the app's one player: the episode keeps playing when
 * the screen goes, and the notification, the lock screen, a headset's button and Android Auto all
 * talk to what the player screen shows. The player itself is [PlayerHost]'s, so there is one
 * position, one set of tracks and one picture, whether it is on the screen or behind it.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class KurokaPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHost.player(this)
        session = MediaSession.Builder(this, player)
            // A session activity is not optional: it is set only when the launcher gave one.
            .apply { pendingIntent()?.let { setSessionActivity(it) } }
            .build()
    }

    /** Tapping the notification opens the app on the episode that is playing. */
    private fun pendingIntent(): PendingIntent? = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = PlayerHost.isPlaying
        // An episode goes on with the screen off only when the user asked for it (Settings,
        // Player, "Keep playing in the background"); otherwise the task going ends the playback.
        if (!playing || !PlaybackPrefs.backgroundAudio) {
            session?.run { release() }
            session = null
            PlayerHost.release()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.run { release() }
        session = null
        super.onDestroy()
    }
}

/** What the service reads of the settings without a dependency on the preferences (set at start). */
object PlaybackPrefs {
    @Volatile var backgroundAudio: Boolean = false
}
