package app.kuroka.host

import android.content.Context
import app.kuroka.api.AccountService
import app.kuroka.api.AppSettings
import app.kuroka.api.EpisodeResolver
import app.kuroka.host.account.AccountRepo
import app.kuroka.host.cast.CastController
import app.kuroka.host.cast.CastDiscovery
import app.kuroka.host.db.KurokaDb
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.host.downloads.DownloadStore
import app.kuroka.host.downloads.StreamHeaders
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.playback.PlaybackPrefs
import app.kuroka.host.prefs.Prefs
import app.kuroka.host.secure.SecureStore
import app.kuroka.sources.SourceResolver
import app.kuroka.sources.Sources
import app.kuroka.sources.anilist.AniListApi
import app.kuroka.sources.anilist.AniListCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The one place the host is put together: the database, the library, the settings, the account, the
 * downloads, the catalog, the sources and the casting, built once when the app starts and reached
 * from the screens through a locator (no dependency-injection framework: the graph is small enough
 * to read, and every piece says what it is for).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../LICENSE).
 */

object KurokaHost {

    private lateinit var appContext: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once [init] has run. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> get() = _ready

    val db: KurokaDb by lazy { KurokaDb(appContext) }
    val prefs: Prefs by lazy { Prefs(appContext) }
    val secure: SecureStore by lazy { SecureStore(appContext) }
    val library: LibraryStore by lazy { LibraryStore(db) }
    val downloadStore: DownloadStore by lazy { DownloadStore(db) }
    val downloads: DownloadRepo by lazy { DownloadRepo(appContext, downloadStore, prefs, scope) }

    /** The AniList client. Its ids come from the app's BuildConfig, so a build can have its own. */
    val api: AniListApi by lazy { AniListApi(clientId, clientSecret, redirectUri) }
    val account: AccountRepo by lazy { AccountRepo(api, secure, library, scope) }

    /** The catalog: AniList, with the signed-in user's token and their adult-content setting. */
    val catalog: AniListCatalog by lazy {
        AniListCatalog(
            token = { account.cachedToken },
            allowAdult = { prefs.now.nsfw },
        )
    }

    /** The sources, in the user's order, with the ones they turned off left out. */
    val resolver: SourceResolver by lazy {
        SourceResolver(
            all = Sources.builtIns { prefs.now.kitsuneServer.takeIf { it.isNotBlank() } },
            order = { prefs.now.sourceOrder },
            disabled = { prefs.now.disabledSources.toSet() },
        )
    }

    /** One Cast controller for the whole app: a session outlives the screen that started it. */
    val cast: CastController by lazy { CastController(scope) }

    val accountService: AccountService get() = account
    val episodeResolver: EpisodeResolver get() = resolver

    /** The sign-in's own ids, set by the app from its BuildConfig before [init]. */
    var clientId: String = ""
    var clientSecret: String = ""
    var redirectUri: String = "kuroka://anilist/callback"

    /** What the settings are, for a screen that has not collected them yet. */
    val settings: StateFlow<AppSettings> get() = prefs.settings

    /**
     * Builds everything and reads what the phone has: the library, the settings, the account's
     * tokens, the downloads in flight. Called from the Application, once.
     */
    fun init(context: Context) {
        if (_ready.value) return
        appContext = context.applicationContext
        scope.launch {
            prefs.settings.collect { s ->
                // The playback service reads this without a dependency on the preferences.
                PlaybackPrefs.backgroundAudio = s.player.backgroundAudio
                // The sources the user turned off, and their order, apply at once.
                resolver.reorder(s.sourceOrder)
                s.disabledSources.forEach { resolver.setEnabled(it, false) }
                knownSourceIds.filterNot { it in s.disabledSources }.forEach { resolver.setEnabled(it, true) }
                knownSourceIds = resolver.sources.value.map { it.id }
            }
        }
        scope.launch { library.load() }
        scope.launch { downloads.start() }
        scope.launch {
            account.restore()
            if (account.phase.value == app.kuroka.api.AccountPhase.SIGNED_IN) account.pushDirty()
        }
        _ready.value = true
    }

    private var knownSourceIds: List<String> = emptyList()

    /** The headers a stream needs, as the player and the downloads see them. */
    val headers: StreamHeaders get() = StreamHeaders

    /** Looks for Chromecasts (the cast sheet calls this when it opens). */
    fun findDevices(context: Context) = CastDiscovery.start(context)

    /** Stops looking (the cast sheet calls this when it goes). */
    fun stopFinding() = CastDiscovery.stop()

    /** Lets go of the cache and the sources' memory (Settings, Storage). */
    fun forgetCaches() {
        app.kuroka.sources.Memo.forget()
        StreamHeaders.clear()
    }
}
