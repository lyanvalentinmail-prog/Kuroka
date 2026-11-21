package app.kuroka

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.kuroka.api.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.kuroka.di.Graph
import app.kuroka.host.KurokaHost
import app.kuroka.host.playback.PlayerHost
import app.kuroka.ui.look.KurokaTheme
import app.kuroka.ui.navigation.KurokaApp
import app.kuroka.ui.navigation.Routes

/*
 * The one activity: it draws the Compose tree in the look the settings ask for, hands the sign-in
 * coming back from the browser to the account, opens an AniList page as an anime page, keeps the
 * screen awake while an episode plays, and shrinks into picture-in-picture when the user leaves
 * mid-episode.
 *
 * The activity is `singleTask` and handles its own configuration changes, so a rotation in the
 * player does not rebuild the tree or lose the position.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../LICENSE).
 */

class MainActivity : ComponentActivity() {

    /** The deep link the activity was opened with, for the tree to act on once. */
    private var pendingLink by mutableStateOf<Intent?>(null)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Graph.init(applicationContext)
        KurokaHost.init(applicationContext)
        apply(KurokaHost.prefs.now.player)
        askForNotifications()
        pendingLink = intent

        setContent {
            val settings by KurokaHost.prefs.settings.collectAsStateWithLifecycle()
            // The player's settings, applied as they change (the subtitle offset, the awake flag).
            LaunchedEffect(settings.player) { apply(settings.player) }
            KurokaTheme {
                KurokaApp(
                    settings = settings,
                    link = pendingLink,
                    onLinkHandled = { pendingLink = null },
                    onController = { controller = it },
                )
            }
        }
    }

    private var controller: NavHostController? = null

    /** The player's settings, as window flags and player defaults. */
    private fun apply(player: app.kuroka.api.PlayerSettings) {
        PlayerHost.subtitleOffsetMs = (player.subtitles.offsetSeconds * 1000).toLong()
        PlayerHost.useEmbeddedStyles = player.subtitles.useEmbeddedStyles
        if (player.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink = intent
    }

    /** The player screen sets the picture's shape; PiP follows it. */
    fun setPictureShape(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (width <= 0 || height <= 0) return
        runCatching {
            val ratio = Rational(width, height).let {
                // Android wants a ratio between 1:2.39 and 2.39:1.
                val value = (width.toFloat() / height.toFloat()).coerceIn(1f / 2.39f, 2.39f)
                Rational((value * 100).toInt(), 100)
            }
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val settings = KurokaHost.prefs.now
        if (!settings.player.pictureInPicture) return
        if (!PlayerHost.isPlaying) return
        // Casting: the picture is on the TV, and the phone's PiP would show nothing.
        if (KurokaHost.cast.isCasting) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val onPlayer = controller?.currentDestination?.route == Routes.playerPattern
        if (!onPlayer) return
        runCatching { enterPictureInPictureMode(pictureInPictureParams ?: PictureInPictureParams.Builder().build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // The player screen reads this to drop its chrome: no bars, no sheets, just the picture.
        PipMode.set(isInPictureInPictureMode)
    }
}

/** Whether the activity is a picture-in-picture window right now (the player and the scaffold read it). */
object PipMode {
    private val _inPip = MutableStateFlow(false)
    /** Collected by the player screen, which drops its chrome, and by the scaffold. */
    val inPip: StateFlow<Boolean> get() = _inPip

    fun set(value: Boolean) {
        _inPip.value = value
    }
}
