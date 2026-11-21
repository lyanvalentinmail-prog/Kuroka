package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.BuildConfig
import app.kuroka.api.Accent
import app.kuroka.api.AppSettings
import app.kuroka.api.Audio
import app.kuroka.api.DownloadLocation
import app.kuroka.api.DownloadSettings
import app.kuroka.api.EpisodeResolver
import app.kuroka.api.FitMode
import app.kuroka.api.Look
import app.kuroka.api.PlayerSettings
import app.kuroka.api.QualityPref
import app.kuroka.api.SourceState
import app.kuroka.api.SubtitleStyle
import app.kuroka.di.Graph
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.host.prefs.Prefs
import app.kuroka.sources.Memo
import app.kuroka.update.UpdateChecker
import app.kuroka.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The settings pages: the look, the player, the subtitles, the downloads, the sources and their
 * order, the storage the caches take, the updates and the about page. Every setter writes the one
 * settings file the host keeps, and the screens collect it, so a change is on the next frame.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

class SettingsViewModel(
    private val prefs: Prefs = Graph.prefs,
    private val resolver: EpisodeResolver = Graph.resolver,
    private val downloads: DownloadRepo = Graph.downloads,
    private val updates: UpdateChecker = Graph.updates,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.now)

    /** The sources, in the order the user put them in, with the ones that are down marked. */
    val sources: StateFlow<List<SourceState>> = resolver.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), resolver.sources.value)

    val updateState: StateFlow<UpdateState> = updates.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), updates.state.value)

    private val _caches = MutableStateFlow(Caches())
    /** What the caches and the memories take, for Settings, Storage. */
    val caches: StateFlow<Caches> get() = _caches

    /** The room on the phone, and the room the downloads may take. */
    val version: String get() = BuildConfig.VERSION_NAME
    val updateRepo: String get() = BuildConfig.UPDATE_REPO
    val canCheckUpdates: Boolean get() = updates.canCheck

    init {
        viewModelScope.launch { readCaches() }
    }

    // --- the look -------------------------------------------------------------------------

    fun setLook(look: Look) = prefs.setLook(look)
    fun setAccent(accent: Accent) = prefs.setAccent(accent)
    fun setDynamicColor(on: Boolean) = prefs.setDynamicColor(on)
    fun setOledBlack(on: Boolean) = prefs.setOledBlack(on)
    fun setLanguage(tag: String) = prefs.setLanguage(tag)
    fun setNsfw(on: Boolean) = prefs.setNsfw(on)
    fun setUpdateCheck(on: Boolean) = prefs.setUpdateCheck(on)
    fun setHomeSections(ids: List<String>) = prefs.setHomeSections(ids)

    /** A home section moved up or down (Settings, Display). */
    fun moveSection(id: String, up: Boolean) {
        val ids = prefs.now.homeSections.toMutableList()
        val at = ids.indexOf(id)
        if (at < 0) return
        val to = if (up) at - 1 else at + 1
        if (to !in ids.indices) return
        ids.removeAt(at)
        ids.add(to, id)
        prefs.setHomeSections(ids)
    }

    // --- the player -------------------------------------------------------------------------

    fun player(transform: (PlayerSettings) -> PlayerSettings) = prefs.player(transform)
    fun subtitles(transform: (SubtitleStyle) -> SubtitleStyle) = prefs.subtitles(transform)
    fun downloadsSettings(transform: (DownloadSettings) -> DownloadSettings) = prefs.downloads(transform)

    fun setAutoPlayNext(on: Boolean) = player { it.copy(autoPlayNext = on) }
    fun setAutoSkipIntro(on: Boolean) = player { it.copy(autoSkipIntro = on) }
    fun setShowSkipButton(on: Boolean) = player { it.copy(showSkipButton = on) }
    fun setQuality(quality: QualityPref) = player { it.copy(quality = quality) }
    fun setFit(fit: FitMode) = player { it.copy(fit = fit) }
    fun setSpeed(speed: Float) = player { it.copy(speed = speed.coerceIn(0.25f, 4.0f)) }
    fun setDefaultAudio(audio: Audio) = player { it.copy(defaultAudio = audio) }
    fun setDoubleTapSeek(seconds: Int) = player { it.copy(doubleTapSeekSeconds = seconds) }
    fun setGestures(on: Boolean) = player { it.copy(gestures = on) }
    fun setVolumeGesture(on: Boolean) = player { it.copy(volumeGesture = on) }
    fun setBrightnessGesture(on: Boolean) = player { it.copy(brightnessGesture = on) }
    fun setAmbilight(on: Boolean) = player { it.copy(ambilight = on) }
    fun setResume(on: Boolean) = player { it.copy(resume = on) }
    fun setBackgroundAudio(on: Boolean) = player { it.copy(backgroundAudio = on) }
    fun setKeepScreenOn(on: Boolean) = player { it.copy(keepScreenOn = on) }
    fun setAutoRotate(on: Boolean) = player { it.copy(autoRotate = on) }
    fun setPictureInPicture(on: Boolean) = player { it.copy(pictureInPicture = on) }
    fun setSkipRecap(on: Boolean) = player { it.copy(skipRecap = on) }
    fun setDataSaver(on: Boolean) = player { it.copy(dataSaver = on) }
    fun setRememberServer(on: Boolean) = player { it.copy(rememberServer = on) }

    fun setSubtitleSize(percent: Int) = subtitles { it.copy(sizePercent = percent.coerceIn(50, 250)) }
    fun setSubtitleColor(argb: Long) = subtitles { it.copy(textArgb = argb) }
    fun setSubtitleBackground(argb: Long) = subtitles { it.copy(backgroundArgb = argb) }
    fun setSubtitleBackgroundAlpha(alpha: Int) = subtitles { it.copy(backgroundAlpha = alpha.coerceIn(0, 255)) }
    fun setSubtitleEdge(edge: String) = subtitles { it.copy(edge = edge) }
    fun setSubtitleOffset(seconds: Double) = subtitles { it.copy(offsetSeconds = seconds) }
    fun setUseEmbeddedStyles(on: Boolean) = subtitles { it.copy(useEmbeddedStyles = on) }
    fun setPreferredSubtitleLanguage(tag: String) = subtitles { it.copy(preferredLanguage = tag) }

    fun setDownloadLocation(location: DownloadLocation) = downloadsSettings { it.copy(location = location) }
    fun setWifiOnly(on: Boolean) = downloadsSettings { it.copy(wifiOnly = on) }
    fun setMaxConcurrent(count: Int) = downloadsSettings { it.copy(maxConcurrent = count.coerceIn(1, 4)) }
    fun setKeepAfterWatching(on: Boolean) = downloadsSettings { it.copy(keepAfterWatching = on) }
    fun setKeepDays(days: Int) = downloadsSettings { it.copy(keepDays = days.coerceAtLeast(0)) }
    fun setDownloadSubtitles(on: Boolean) = downloadsSettings { it.copy(subtitles = on) }
    fun setDownloadQuality(quality: QualityPref) = downloadsSettings { it.copy(quality = quality) }
    fun setBudget(mb: Int) = downloadsSettings { it.copy(budgetMb = mb.coerceAtLeast(0)) }
    fun setAskOnMetered(on: Boolean) = downloadsSettings { it.copy(askOnMetered = on) }

    // --- the sources ------------------------------------------------------------------------

    /** A source turned on or off (Settings, Sources). */
    fun setSourceEnabled(id: String, enabled: Boolean) {
        prefs.setSourceEnabled(id, enabled)
        resolver.setEnabled(id, enabled)
    }

    /** A source moved up or down in the order the resolver asks them in. */
    fun moveSource(id: String, up: Boolean) {
        val ids = resolver.sources.value.sortedBy { it.order }.map { it.id }.toMutableList()
        val at = ids.indexOf(id)
        if (at < 0) return
        val to = if (up) at - 1 else at + 1
        if (to !in ids.indices) return
        ids.removeAt(at)
        ids.add(to, id)
        prefs.setSourceOrder(ids)
        resolver.reorder(ids)
    }

    /** The address of the user's own Kitsune server (empty takes the source off). */
    fun setKitsuneServer(url: String) = prefs.setKitsuneServer(url)

    /** Forgets a source's failures, so it is asked again at once. */
    fun resetSource(id: String) = resolver.reset(id)

    // --- the storage ------------------------------------------------------------------------

    private suspend fun readCaches() {
        _caches.value = withContext(Dispatchers.IO) {
            val context = Graph.context
            val http = Memo.sizeLabel()
            val posters = folderSize(java.io.File(context.cacheDir, "posters"))
            val downloadsTaken = downloads.usedLabel()
            Caches(
                posters = posters,
                http = http,
                downloads = downloadsTaken,
                total = folderSize(context.cacheDir) + folderSize(context.filesDir),
            )
        }
    }

    private fun folderSize(dir: java.io.File): String {
        var bytes = 0L
        runCatching { dir.walkTopDown().forEach { if (it.isFile) bytes += it.length() } }
        return app.kuroka.api.DownloadItem.size(bytes)
    }

    /** Lets go of the posters and the answers the sources remembered. */
    fun clearCaches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                app.kuroka.ui.components.Posters.clear()
                Memo.forget()
                runCatching { java.io.File(Graph.context.cacheDir, "posters").deleteRecursively() }
            }
            readCaches()
        }
    }

    // --- the updates ------------------------------------------------------------------------

    fun checkForUpdates() = updates.check(allowBeta = true)

    fun downloadUpdate() {
        val found = updates.state.value as? app.kuroka.update.UpdateState.Available ?: return
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: "arm64-v8a"
        val asset = found.assets.firstOrNull { it.abi == abi } ?: found.assets.firstOrNull { it.abi == "universal" }
        if (asset == null) {
            updates.openPage(found.url)
            return
        }
        updates.download(asset)
    }

    fun installUpdate() {
        val ready = updates.state.value as? app.kuroka.update.UpdateState.Ready ?: return
        updates.install(ready.file)
    }

    fun openUpdatePage() {
        val found = updates.state.value as? app.kuroka.update.UpdateState.Available
        if (found != null) updates.openPage(found.url)
        else if (updateRepo.isNotBlank()) updates.openPage("https://github.com/$updateRepo/releases")
    }

    /** The setup has been walked through: the first-run screens do not come back. */
    fun onboardingDone() = prefs.setOnboardingDone()
}

/** What the caches take, as labels ("12 MB"). */
data class Caches(
    val posters: String = "0 B",
    val http: String = "0 B",
    val downloads: String = "0 B",
    val total: String = "0 B",
)
