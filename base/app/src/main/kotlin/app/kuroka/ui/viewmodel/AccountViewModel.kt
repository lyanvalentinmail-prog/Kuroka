package app.kuroka.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.AccountPhase
import app.kuroka.api.AccountUser
import app.kuroka.di.Graph
import app.kuroka.host.account.AccountRepo
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.library.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/*
 * The account page: signing into AniList (through its own site when the build has a client id, or
 * with a token the user pastes), who is signed in, what the phone and the account each have, and
 * the sync between the two. Signing out leaves the phone's library where it is.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

class AccountViewModel(
    private val account: AccountRepo = Graph.account,
    private val library: LibraryStore = Graph.library,
) : ViewModel() {

    val phase: StateFlow<AccountPhase> = account.phase
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), account.phase.value)

    val user: StateFlow<AccountUser?> = account.user
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), account.user.value)

    /** True when this build can send the user to AniList's own sign-in page. */
    val canOAuth: Boolean get() = account.canOAuth

    private val _notice = MutableStateFlow<String?>(null)
    /** What the last sign-in or sync said. */
    val notice: StateFlow<String?> get() = _notice

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy

    private val _stats = MutableStateFlow(Stats())
    /** The phone's own numbers, shown next to the account's. */
    val stats: StateFlow<Stats> get() = _stats

    /** The PKCE pair of the sign-in being walked through; the browser comes back to it. */
    private var verifier: String? = null
    private var state: String? = null

    init {
        viewModelScope.launch {
            library.entries.collect { _stats.value = library.stats() }
        }
    }

    /**
     * Opens AniList's sign-in page in the browser. The page comes back to
     * `kuroka://anilist/callback`, which the activity hands to [completeFromLink].
     */
    fun signInWithBrowser(context: Context) {
        if (!account.canOAuth) {
            _notice.value = "This build has no AniList client id: paste a token instead"
            return
        }
        val (codeVerifier, challenge) = account.pkce()
        val random = SecureRandom().nextInt().toString(16)
        verifier = codeVerifier
        state = random
        val url = account.authorizeUrl(random, challenge)
        if (url == null) {
            _notice.value = "The sign-in page could not be built"
            return
        }
        _busy.value = true
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { _notice.value = "No browser to sign in with" }
    }

    /** The browser came back: the link's code and the verifier kept for it. */
    fun completeFromLink(uri: Uri?) {
        val link = uri ?: return
        val code = link.getQueryParameter("code")
        val returned = link.getQueryParameter("state")
        if (code == null) {
            _busy.value = false
            _notice.value = link.getQueryParameter("error")?.let { "AniList said: $it" }
                ?: "The sign-in came back without a code"
            return
        }
        if (state != null && returned != state) {
            _busy.value = false
            _notice.value = "The sign-in came back from somewhere else"
            return
        }
        val kept = verifier
        viewModelScope.launch {
            val signed = withContext(Dispatchers.IO) { account.completeSignIn(code, kept ?: "") }
            _busy.value = false
            verifier = null
            state = null
            _notice.value = if (signed != null) "Signed in as ${signed.name}" else "AniList refused the sign-in"
            if (signed != null) _stats.value = library.stats()
        }
    }

    /** A token pasted from anilist.co/settings/developer (no browser, no client id needed). */
    fun signInWithToken(token: String) {
        val clean = token.trim()
        if (clean.length < 20) {
            _notice.value = "That does not look like an AniList token"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val signed = withContext(Dispatchers.IO) { account.signInWithToken(clean) }
            _busy.value = false
            _notice.value = if (signed != null) "Signed in as ${signed.name}"
            else "AniList did not accept that token"
            if (signed != null) _stats.value = library.stats()
        }
    }

    /** The account's list and favourites down, then what the phone changed up. */
    fun sync() {
        if (phase.value != AccountPhase.SIGNED_IN) {
            _notice.value = "Sign in first"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { account.pullAll() } }
            _busy.value = false
            _stats.value = library.stats()
            _notice.value = "Your list is in step with AniList"
        }
    }

    /** Who is signed in, asked again (the profile's numbers change on the site). */
    fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { account.refreshUser() }
        }
    }

    /** Signs out. What the phone has stays: this is not a delete. */
    fun signOut() {
        _busy.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { account.signOut() }
            _busy.value = false
            _notice.value = "Signed out; your phone's library is still here"
        }
    }

    /** Opens the page where AniList hands out tokens. */
    fun openTokenPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://anilist.co/settings/developer"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
