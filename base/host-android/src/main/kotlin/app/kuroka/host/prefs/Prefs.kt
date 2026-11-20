package app.kuroka.host.prefs

import android.content.Context
import app.kuroka.api.Accent
import app.kuroka.api.AppSettings
import app.kuroka.api.Audio
import app.kuroka.api.DownloadLocation
import app.kuroka.api.DownloadSettings
import app.kuroka.api.FitMode
import app.kuroka.api.Look
import app.kuroka.api.PlayerSettings
import app.kuroka.api.QualityPref
import app.kuroka.api.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * The settings, as one JSON file in the app's own folder and one flow in memory: a screen reads
 * the flow and redraws when it changes, and writes through [update], which saves the file on a
 * worker. No preferences framework, so what is stored is readable and easy to move (a pass code,
 * a backup, a new phone).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class Prefs(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(AppSettings())

    /** What the app is set to; the default until the file has been read. */
    val settings: StateFlow<AppSettings> get() = _settings

    /** The settings as they are right now, for a caller that is not a composable. */
    val now: AppSettings get() = _settings.value

    init {
        scope.launch { _settings.value = read() }
    }

    /** Reads the file, keeping what it does not know (a setting of a newer version). */
    private fun read(): AppSettings = runCatching {
        if (!file.exists()) return@runCatching AppSettings()
        decode(JSONObject(file.readText()))
    }.getOrDefault(AppSettings())

    /** One change, saved as it is made. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        scope.launch { runCatching { write(next) } }
    }

    private fun write(s: AppSettings) {
        val tmp = File(file.parentFile, "settings.json.tmp")
        tmp.writeText(encode(s).toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    // ── The settings, one by one, so a screen says what it changes ──

    fun setLook(look: Look) = update { it.copy(look = look) }
    fun setAccent(accent: Accent) = update { it.copy(accent = accent, dynamicColor = false) }
    fun setDynamicColor(on: Boolean) = update { it.copy(dynamicColor = on) }
    fun setOledBlack(on: Boolean) = update { it.copy(oledBlack = on) }
    fun setLanguage(tag: String) = update { it.copy(language = tag) }
    fun setNsfw(on: Boolean) = update { it.copy(nsfw = on) }
    fun setOnboardingDone() = update { it.copy(onboardingDone = true) }
    fun setUpdateCheck(on: Boolean) = update { it.copy(updateCheck = on) }
    fun setHomeSections(ids: List<String>) = update { it.copy(homeSections = ids) }

    fun setSourceOrder(ids: List<String>) = update { it.copy(sourceOrder = ids) }
    fun setSourceEnabled(id: String, enabled: Boolean) = update {
        val off = it.disabledSources.toMutableSet()
        if (enabled) off.remove(id) else off.add(id)
        it.copy(disabledSources = off)
    }

    fun setKitsuneServer(url: String) = update { it.copy(kitsuneServer = url.trim()) }

    fun player(transform: (PlayerSettings) -> PlayerSettings) = update { it.copy(player = transform(it.player)) }
    fun subtitles(transform: (SubtitleStyle) -> SubtitleStyle) =
        update { it.copy(player = it.player.copy(subtitles = transform(it.player.subtitles))) }

    fun downloads(transform: (DownloadSettings) -> DownloadSettings) =
        update { it.copy(downloads = transform(it.downloads)) }

    fun rememberServer(server: String) = update {
        it.copy(player = it.player.copy(lastServer = server))
    }

    // ── The file ──

    private fun encode(s: AppSettings): JSONObject = JSONObject().apply {
        put("look", s.look.code)
        put("accent", s.accent.code)
        put("dynamicColor", s.dynamicColor)
        put("oledBlack", s.oledBlack)
        put("language", s.language)
        put("nsfw", s.nsfw)
        put("onboardingDone", s.onboardingDone)
        put("updateCheck", s.updateCheck)
        put("analytics", s.analytics)
        put("sourceOrder", JSONArray(s.sourceOrder as Collection<*>))
        put("disabledSources", JSONArray(s.disabledSources.toList() as Collection<*>))
        put("homeSections", JSONArray(s.homeSections as Collection<*>))
        put("kitsuneServer", s.kitsuneServer)
        put("player", JSONObject().apply {
            val p = s.player
            put("autoPlayNext", p.autoPlayNext)
            put("autoSkipIntro", p.autoSkipIntro)
            put("showSkipButton", p.showSkipButton)
            put("quality", p.quality.code)
            put("speed", p.speed.toDouble())
            put("fit", p.fit.code)
            put("defaultAudio", p.defaultAudio.code)
            put("doubleTapSeekSeconds", p.doubleTapSeekSeconds)
            put("gestures", p.gestures)
            put("volumeGesture", p.volumeGesture)
            put("brightnessGesture", p.brightnessGesture)
            put("ambilight", p.ambilight)
            put("resume", p.resume)
            put("backgroundAudio", p.backgroundAudio)
            put("keepScreenOn", p.keepScreenOn)
            put("autoRotate", p.autoRotate)
            put("pictureInPicture", p.pictureInPicture)
            put("skipRecap", p.skipRecap)
            put("dataSaver", p.dataSaver)
            put("rememberServer", p.rememberServer)
            p.lastServer?.let { put("lastServer", it) }
            put("subtitles", JSONObject().apply {
                val st = p.subtitles
                put("sizePercent", st.sizePercent)
                put("textArgb", st.textArgb)
                put("backgroundAlpha", st.backgroundAlpha)
                put("backgroundArgb", st.backgroundArgb)
                put("edge", st.edge)
                put("offsetSeconds", st.offsetSeconds)
                put("useEmbeddedStyles", st.useEmbeddedStyles)
                put("preferredLanguage", st.preferredLanguage)
            })
        })
        put("downloads", JSONObject().apply {
            val d = s.downloads
            put("location", d.location.code)
            put("wifiOnly", d.wifiOnly)
            put("maxConcurrent", d.maxConcurrent)
            put("keepAfterWatching", d.keepAfterWatching)
            put("keepDays", d.keepDays)
            put("subtitles", d.subtitles)
            put("quality", d.quality.code)
            put("budgetMb", d.budgetMb)
            put("askOnMetered", d.askOnMetered)
        })
    }

    private fun decode(o: JSONObject): AppSettings {
        val d = AppSettings()
        val player = o.optJSONObject("player")
        val subtitles = player?.optJSONObject("subtitles")
        val downloads = o.optJSONObject("downloads")
        return AppSettings(
            look = Look.of(o.optString("look", d.look.code)),
            accent = Accent.of(o.optString("accent", d.accent.code)),
            dynamicColor = o.optBoolean("dynamicColor", d.dynamicColor),
            oledBlack = o.optBoolean("oledBlack", d.oledBlack),
            language = o.optString("language", d.language),
            nsfw = o.optBoolean("nsfw", d.nsfw),
            onboardingDone = o.optBoolean("onboardingDone", d.onboardingDone),
            updateCheck = o.optBoolean("updateCheck", d.updateCheck),
            analytics = o.optBoolean("analytics", d.analytics),
            sourceOrder = o.optJSONArray("sourceOrder")?.let { strings(it) } ?: d.sourceOrder,
            disabledSources = o.optJSONArray("disabledSources")?.let { strings(it).toSet() } ?: d.disabledSources,
            homeSections = o.optJSONArray("homeSections")?.let { strings(it) }?.ifEmpty { null } ?: d.homeSections,
            kitsuneServer = o.optString("kitsuneServer", ""),
            player = if (player == null) d.player else PlayerSettings(
                autoPlayNext = player.optBoolean("autoPlayNext", d.player.autoPlayNext),
                autoSkipIntro = player.optBoolean("autoSkipIntro", d.player.autoSkipIntro),
                showSkipButton = player.optBoolean("showSkipButton", d.player.showSkipButton),
                quality = QualityPref.of(player.optString("quality", d.player.quality.code)),
                speed = player.optDouble("speed", d.player.speed.toDouble()).toFloat(),
                fit = FitMode.of(player.optString("fit", d.player.fit.code)),
                defaultAudio = Audio.of(player.optString("defaultAudio", d.player.defaultAudio.code)),
                doubleTapSeekSeconds = player.optInt("doubleTapSeekSeconds", d.player.doubleTapSeekSeconds),
                gestures = player.optBoolean("gestures", d.player.gestures),
                volumeGesture = player.optBoolean("volumeGesture", d.player.volumeGesture),
                brightnessGesture = player.optBoolean("brightnessGesture", d.player.brightnessGesture),
                ambilight = player.optBoolean("ambilight", d.player.ambilight),
                resume = player.optBoolean("resume", d.player.resume),
                backgroundAudio = player.optBoolean("backgroundAudio", d.player.backgroundAudio),
                keepScreenOn = player.optBoolean("keepScreenOn", d.player.keepScreenOn),
                autoRotate = player.optBoolean("autoRotate", d.player.autoRotate),
                pictureInPicture = player.optBoolean("pictureInPicture", d.player.pictureInPicture),
                skipRecap = player.optBoolean("skipRecap", d.player.skipRecap),
                dataSaver = player.optBoolean("dataSaver", d.player.dataSaver),
                rememberServer = player.optBoolean("rememberServer", d.player.rememberServer),
                lastServer = player.optString("lastServer", d.player.lastServer ?: "").ifBlank { null },
                subtitles = if (subtitles == null) d.player.subtitles else SubtitleStyle(
                    sizePercent = subtitles.optInt("sizePercent", d.player.subtitles.sizePercent),
                    textArgb = subtitles.optLong("textArgb", d.player.subtitles.textArgb),
                    backgroundAlpha = subtitles.optInt("backgroundAlpha", d.player.subtitles.backgroundAlpha),
                    backgroundArgb = subtitles.optLong("backgroundArgb", d.player.subtitles.backgroundArgb),
                    edge = subtitles.optString("edge", d.player.subtitles.edge),
                    offsetSeconds = subtitles.optDouble("offsetSeconds", d.player.subtitles.offsetSeconds),
                    useEmbeddedStyles = subtitles.optBoolean("useEmbeddedStyles", d.player.subtitles.useEmbeddedStyles),
                    preferredLanguage = subtitles.optString("preferredLanguage", d.player.subtitles.preferredLanguage),
                ),
            ),
            downloads = if (downloads == null) d.downloads else DownloadSettings(
                location = DownloadLocation.of(downloads.optString("location", d.downloads.location.code)),
                wifiOnly = downloads.optBoolean("wifiOnly", d.downloads.wifiOnly),
                maxConcurrent = downloads.optInt("maxConcurrent", d.downloads.maxConcurrent),
                keepAfterWatching = downloads.optBoolean("keepAfterWatching", d.downloads.keepAfterWatching),
                keepDays = downloads.optInt("keepDays", d.downloads.keepDays),
                subtitles = downloads.optBoolean("subtitles", d.downloads.subtitles),
                quality = QualityPref.of(downloads.optString("quality", d.downloads.quality.code)),
                budgetMb = downloads.optInt("budgetMb", d.downloads.budgetMb),
                askOnMetered = downloads.optBoolean("askOnMetered", d.downloads.askOnMetered),
            ),
        )
    }

    private fun strings(a: JSONArray): List<String> {
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }
}
