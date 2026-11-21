package app.kuroka.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.kuroka.api.FitMode
import app.kuroka.api.SubtitleStyle
import app.kuroka.host.playback.PlayerHost
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.clockOf
import kotlin.math.abs

/*
 * The picture: a SurfaceView with the app's one player behind it, the subtitles drawn over it in
 * Compose (which is what makes their size, colour, box and offset settings work at all), and the
 * gestures a phone player is expected to have — a tap for the controls, a double tap on either half
 * to jump ten seconds that way, a drag down the left half for the screen's brightness and down the
 * right half for the volume, and a drag across to scrub.
 *
 * The approach is Kitsune's player (MIT, © Dovakiin0): the video element and its controls are the
 * app's own, so a subtitle offset, a fit mode and a gesture do not depend on what a widget offers.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** What a gesture is doing right now, drawn over the picture while the finger is down. */
private sealed interface Gesture {
    data object None : Gesture
    data class Seek(val byMs: Long) : Gesture
    data class Brightness(val percent: Float) : Gesture
    data class Volume(val percent: Float) : Gesture
}

/**
 * The player's picture, its subtitles and its gestures.
 *
 * @param fit how the picture fills the space ([FitMode]).
 * @param subtitles the look the settings ask for.
 * @param gestures whether the gestures are on at all (Settings, Player).
 * @param onTap a single tap: the screen shows or hides its controls.
 * @param ambilight the poster drawn behind a letterboxed picture, as a glow.
 */
@Composable
fun PlayerSurface(
    modifier: Modifier = Modifier,
    fit: FitMode = FitMode.FIT,
    subtitles: SubtitleStyle = SubtitleStyle(),
    gestures: Boolean = true,
    volumeGesture: Boolean = true,
    brightnessGesture: Boolean = true,
    doubleTapSeconds: Int = 10,
    ambilight: Boolean = true,
    poster: String? = null,
    onTap: () -> Unit = {},
    onScrub: ((Long) -> Unit)? = null,
    controlsVisible: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val snapshot by PlayerHost.snapshot.collectAsState()

    var gesture by remember { mutableStateOf<Gesture>(Gesture.None) }
    var scrubTo by remember { mutableLongStateOf(-1L) }

    // The picture's own shape, which decides how much of the space it fills.
    val videoWidth = snapshot.videoWidth.takeIf { it > 0 } ?: 16
    val videoHeight = snapshot.videoHeight.takeIf { it > 0 } ?: 9
    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val boxAspect = if (boxHeight > 0) boxWidth / boxHeight else 16f / 9f

        // A glow behind a letterboxed picture: the poster, blurred by being drawn big and faint.
        if (ambilight && poster != null && abs(videoAspect - boxAspect) > 0.05f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.35f)
            ) {
                NetworkImage(
                    url = poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    widthPx = 120,
                )
            }
        }

        // The picture itself, sized by the fit mode.
        val (widthPx, heightPx) = fitSize(fit, videoAspect, boxAspect, boxWidth, boxHeight)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(with(density) { widthPx.toDp() })
                .height(with(density) { heightPx.toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { viewContext ->
                    SurfaceView(viewContext).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // The picture is drawn by the player, not by Compose: no z-fighting, and a
                        // secure stream (a DRM one) can draw here where a TextureView could not.
                        setZOrderMediaOverlay(true)
                        PlayerHost.attach(this)
                    }
                },
                onRelease = { view -> PlayerHost.detach(view) },
                modifier = Modifier.fillMaxSize(),
            )

            // The subtitles, drawn over the picture in the look the settings ask for.
            SubtitleLayer(
                cues = snapshot.cues,
                style = subtitles,
                boxWidthPx = boxWidth,
            )
        }

        // The gestures, over everything: a tap, a double tap, a vertical or a horizontal drag.
        if (gestures) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(doubleTapSeconds, controlsVisible) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { offset ->
                                val forward = offset.x > size.width / 2f
                                PlayerHost.seekBy((if (forward) 1 else -1) * doubleTapSeconds * 1000L)
                                gesture = Gesture.Seek((if (forward) 1 else -1) * doubleTapSeconds * 1000L)
                            },
                        )
                    }
                    .pointerInput(volumeGesture, brightnessGesture) {
                        var side = 0
                        var startY = 0f
                        var startValue = 0f
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                side = if (offset.x < size.width / 2f) -1 else 1
                                startY = offset.y
                                startValue = if (side < 0) screenBrightness(context) else streamVolume(context)
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val travelled = (startY - change.position.y) / size.height.toFloat()
                                val value = (startValue + travelled).coerceIn(0f, 1f)
                                if (side < 0 && brightnessGesture) {
                                    setScreenBrightness(context, value)
                                    gesture = Gesture.Brightness(value)
                                } else if (side > 0 && volumeGesture) {
                                    setStreamVolume(context, value)
                                    gesture = Gesture.Volume(value)
                                }
                            },
                            onDragEnd = { gesture = Gesture.None },
                            onDragCancel = { gesture = Gesture.None },
                        )
                    }
                    .pointerInput(snapshot.durationMs, onScrub) {
                        var from = 0L
                        detectHorizontalDragGestures(
                            onDragStart = { from = PlayerHost.positionMs },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val duration = snapshot.durationMs
                                if (duration <= 0L) return@detectHorizontalDragGestures
                                // The whole width is the whole episode, as a scrub bar would be.
                                val by = (dragAmount / size.width.toFloat() * duration).toLong()
                                val to = (from + by).coerceIn(0L, duration)
                                from = to
                                scrubTo = to
                                gesture = Gesture.Seek(to - PlayerHost.positionMs)
                                onScrub?.invoke(to)
                            },
                            onDragEnd = {
                                if (scrubTo >= 0L) PlayerHost.seekTo(scrubTo)
                                scrubTo = -1L
                                gesture = Gesture.None
                            },
                            onDragCancel = {
                                scrubTo = -1L
                                gesture = Gesture.None
                            },
                        )
                    }
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
            )
        }

        // What the gesture is doing, as a small pill in the middle of the picture.
        when (val current = gesture) {
            is Gesture.Seek -> GesturePill(
                text = (if (current.byMs >= 0) "+" else "−") + clockOf(abs(current.byMs)),
            )

            is Gesture.Brightness -> GesturePill(text = "Brightness ${(current.percent * 100).toInt()}%")
            is Gesture.Volume -> GesturePill(text = "Volume ${(current.percent * 100).toInt()}%")
            Gesture.None -> Unit
        }
    }
}

/**
 * A sample line, drawn as the settings say: Settings, Subtitles shows one over a dark box, so a
 * change of size, colour or offset can be seen before an episode is played.
 */
@Composable
fun SubtitlePreview(
    text: String,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.background(Color.Black)) {
        SubtitleLayer(
            cues = listOf(app.kuroka.host.playback.CueLine(text = text, startMs = 0L, endMs = 0L)),
            style = style,
            boxWidthPx = constraints.maxWidth.toFloat(),
        )
    }
}

/** The subtitles, drawn as the settings say: their size, colour, box and edge. */
@Composable
private fun SubtitleLayer(
    cues: List<app.kuroka.host.playback.CueLine>,
    style: SubtitleStyle,
    boxWidthPx: Float,
) {
    if (cues.isEmpty()) return
    // The size follows the picture's width, so a tablet and a phone read the same.
    val base = (boxWidthPx / 26f).coerceIn(12f, 34f)
    val size = base * (style.sizePercent / 100f)
    val textColor = Color(style.textArgb)
    val boxColor = Color(style.backgroundArgb).copy(alpha = style.backgroundAlpha / 255f)
    val edge = when (style.edge) {
        "shadow" -> Shadow(color = Color.Black.copy(alpha = 0.85f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 6f)
        "none" -> null
        // An outline Compose can draw without a canvas of its own: a hard shadow all round.
        else -> Shadow(color = Color.Black, offset = androidx.compose.ui.geometry.Offset(0f, 0f), blurRadius = 4f)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(bottom = 26.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        ) {
            cues.forEach { cue ->
                val percent = cue.textSizePercent?.takeIf { style.useEmbeddedStyles }
                Text(
                    text = cue.text,
                    style = TextStyle(
                        color = textColor,
                        fontSize = (size * (percent ?: 100f) / 100f).sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        shadow = edge,
                        lineHeight = (size * 1.25f).sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if ((cue.windowColor ?: style.backgroundAlpha) > 0)
                                Modifier.background(
                                    cue.windowColor?.let { Color(it) } ?: boxColor,
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                )
                            else Modifier
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The pill a gesture shows while the finger is down. */
@Composable
private fun GesturePill(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.72f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** How big the picture is drawn, in pixels, for a fit mode and the shapes of the video and the box. */
private fun fitSize(
    fit: FitMode,
    videoAspect: Float,
    boxAspect: Float,
    boxWidth: Float,
    boxHeight: Float,
): Pair<Float, Float> = when (fit) {
    // The whole picture, with bars where the shapes differ.
    FitMode.FIT -> if (videoAspect > boxAspect) boxWidth to boxWidth / videoAspect
    else boxHeight * videoAspect to boxHeight

    // The whole box, with the picture cropped at its sides or its top and bottom.
    FitMode.FILL, FitMode.ZOOM -> {
        val zoom = if (fit == FitMode.ZOOM) 1.15f else 1f
        val width = boxWidth * zoom
        val height = boxHeight * zoom
        width to height
    }

    // The whole box, with the picture stretched to it (the shapes are not kept).
    FitMode.STRETCH -> boxWidth to boxHeight
}

/** The screen's brightness, 0f to 1f (-1f is "the phone's own"). */
private fun screenBrightness(context: Context): Float {
    val activity = context as? Activity ?: return 0.5f
    val value = activity.window.attributes.screenBrightness
    return if (value < 0f) 0.5f else value
}

private fun setScreenBrightness(context: Context, value: Float) {
    val activity = context as? Activity ?: return
    val attributes = activity.window.attributes
    attributes.screenBrightness = value.coerceIn(0.01f, 1f)
    activity.window.attributes = attributes
}

/** The media volume, 0f to 1f. */
private fun streamVolume(context: Context): Float {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0.5f
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 15
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun setStreamVolume(context: Context, value: Float) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    audio.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        (value.coerceIn(0f, 1f) * max).toInt(),
        0,
    )
}

/** Leaves the brightness where it was when the player goes. */
@Composable
fun rememberBrightnessReset() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity ?: return@onDispose
            val attributes = activity.window.attributes
            attributes.screenBrightness = -1f
            activity.window.attributes = attributes
        }
    }
}

/** The screen stays awake while the player is up (the setting is applied by the activity). */
@Composable
fun rememberAwake(on: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(on) {
        val activity = context as? Activity ?: return@LaunchedEffect
        if (on) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
