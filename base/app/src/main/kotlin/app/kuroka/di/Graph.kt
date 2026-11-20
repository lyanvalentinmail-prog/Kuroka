package app.kuroka.di

import android.content.Context
import app.kuroka.api.Catalog
import app.kuroka.api.EpisodeResolver
import app.kuroka.host.KurokaHost
import app.kuroka.host.account.AccountRepo
import app.kuroka.host.cast.CastController
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.prefs.Prefs
import app.kuroka.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope

/*
 * What the screens reach for, in one place: the catalog, the sources, the library, the downloads,
 * the account, the player and the casting, all built once by the host at start. A view model takes
 * these as default arguments, so a screen asks for one without a factory and a test can hand it
 * its own.
 *
 * The shape follows Orpheon's di/Graph (GPL-3.0, © kota-zo): a locator object rather than a
 * dependency-injection framework, because the graph is small enough to read in one file.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

object Graph {

    lateinit var context: Context
        private set

    /** The scope the app's own long-lived work runs in (a screen's work uses its view model's). */
    val scope: CoroutineScope get() = KurokaHost.scope

    val prefs: Prefs get() = KurokaHost.prefs
    val library: LibraryStore get() = KurokaHost.library
    val downloads: DownloadRepo get() = KurokaHost.downloads
    val account: AccountRepo get() = KurokaHost.account
    val catalog: Catalog get() = KurokaHost.catalog
    val resolver: EpisodeResolver get() = KurokaHost.resolver
    val cast: CastController get() = KurokaHost.cast

    /** The update check against the app's own GitHub releases. */
    val updates: UpdateChecker by lazy { UpdateChecker(context, scope) }

    /** Called by the Application, once, before anything is drawn. */
    fun init(context: Context) {
        if (Graph::context.isInitialized) return
        Graph.context = context.applicationContext
    }
}
