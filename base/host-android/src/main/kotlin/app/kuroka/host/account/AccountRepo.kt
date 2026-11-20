package app.kuroka.host.account

import app.kuroka.api.AccountPhase
import app.kuroka.api.AccountService
import app.kuroka.api.AccountTokens
import app.kuroka.api.AccountUser
import app.kuroka.api.Favourite
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.host.library.LibraryStore
import app.kuroka.host.secure.SecureStore
import app.kuroka.sources.anilist.AniListApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The account: a sign-in to AniList (its OAuth page, or a token pasted from the site's developer
 * settings), the tokens in the phone's keystore, and the two ways the library meets it — pulled
 * when the sign-in happens, pushed when the phone changes something. Everything works signed out:
 * the account is an addition, not a door.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class AccountRepo(
    private val api: AniListApi,
    private val secure: SecureStore,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
) : AccountService {

    override val canOAuth: Boolean get() = api.canOAuth

    private val _phase = MutableStateFlow(AccountPhase.SIGNED_OUT)
    override val phase: StateFlow<AccountPhase> get() = _phase

    private val _user = MutableStateFlow<AccountUser?>(null)
    override val user: StateFlow<AccountUser?> get() = _user

    @Volatile private var tokens: AccountTokens? = null
    /**
     * The token as it is right now, for a caller that cannot suspend (the catalog puts it on its
     * requests so a page carries the signed-in user's own state).
     */
    @Volatile var cachedToken: String? = null
        private set
    private var syncJob: Job? = null

    /** Reads the tokens the last sign-in left, and who they belong to. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        val access = secure.get(KEY_TOKEN) ?: return@withContext
        tokens = AccountTokens(
            accessToken = access,
            refreshToken = secure.get(KEY_REFRESH),
            expiresAt = secure.get(KEY_EXPIRES)?.toLongOrNull() ?: 0L,
        )
        cachedToken = access
        val id = secure.getInt(KEY_USER_ID)
        val name = secure.get(KEY_USER_NAME)
        if (id != null && name != null) {
            _user.value = AccountUser(
                id = id, name = name,
                avatar = secure.get(KEY_USER_AVATAR),
                banner = secure.get(KEY_USER_BANNER),
                siteUrl = secure.get(KEY_USER_URL),
                episodesWatched = secure.getInt(KEY_USER_EPISODES) ?: 0,
                minutesWatched = secure.get(KEY_USER_MINUTES)?.toLongOrNull() ?: 0L,
                meanScore = secure.get(KEY_USER_SCORE)?.toDoubleOrNull() ?: 0.0,
            )
            _phase.value = AccountPhase.SIGNED_IN
        } else {
            // A token with no profile: ask the site whose it is.
            refreshUser()
        }
    }

    /**
     * The token to send, refreshed when it has expired. The catalog asks for it too, so a page can
     * carry the signed-in user's own state.
     */
    suspend fun token(): String? {
        val current = tokens ?: return null
        if (!current.expired) return current.accessToken
        val refreshed = current.refreshToken?.let { api.refresh(it) } ?: return current.accessToken
        saveTokens(refreshed)
        return refreshed.accessToken
    }

    private suspend fun saveTokens(t: AccountTokens) = withContext(Dispatchers.IO) {
        tokens = t
        cachedToken = t.accessToken
        secure.put(KEY_TOKEN, t.accessToken)
        t.refreshToken?.let { secure.put(KEY_REFRESH, it) } ?: secure.remove(KEY_REFRESH)
        secure.put(KEY_EXPIRES, t.expiresAt.toString())
    }

    private suspend fun remember(user: AccountUser) {
        _user.value = user
        _phase.value = AccountPhase.SIGNED_IN
        withContext(Dispatchers.IO) {
            secure.putInt(KEY_USER_ID, user.id)
            secure.put(KEY_USER_NAME, user.name)
            user.avatar?.let { secure.put(KEY_USER_AVATAR, it) }
            user.banner?.let { secure.put(KEY_USER_BANNER, it) }
            user.siteUrl?.let { secure.put(KEY_USER_URL, it) }
            secure.putInt(KEY_USER_EPISODES, user.episodesWatched)
            secure.put(KEY_USER_MINUTES, user.minutesWatched.toString())
            secure.put(KEY_USER_SCORE, user.meanScore.toString())
        }
    }

    override suspend fun signInWithToken(token: String): AccountUser? {
        val clean = token.trim()
        if (clean.length < 10) return null
        _phase.value = AccountPhase.SIGNING_IN
        val user = runCatching { api.viewer(clean) }.getOrNull()
        if (user == null) {
            _phase.value = AccountPhase.FAILED
            return null
        }
        // A personal access token does not expire the way an OAuth one does.
        saveTokens(AccountTokens(clean, null, 0L))
        remember(user)
        pullAll()
        return user
    }

    override fun authorizeUrl(state: String, codeChallenge: String): String? =
        api.authorizeUrl(state, codeChallenge)

    override suspend fun completeSignIn(code: String, codeVerifier: String): AccountUser? {
        _phase.value = AccountPhase.SIGNING_IN
        val t = runCatching { api.exchangeCode(code, codeVerifier) }.getOrNull()
        if (t == null) {
            _phase.value = AccountPhase.FAILED
            return null
        }
        saveTokens(t)
        val user = runCatching { api.viewer(t.accessToken) }.getOrNull()
        if (user == null) {
            _phase.value = AccountPhase.FAILED
            return null
        }
        remember(user)
        pullAll()
        return user
    }

    override suspend fun signOut() {
        cachedToken = null
        tokens = null
        _user.value = null
        _phase.value = AccountPhase.SIGNED_OUT
        withContext(Dispatchers.IO) {
            listOf(KEY_TOKEN, KEY_REFRESH, KEY_EXPIRES, KEY_USER_ID, KEY_USER_NAME, KEY_USER_AVATAR,
                KEY_USER_BANNER, KEY_USER_URL, KEY_USER_EPISODES, KEY_USER_MINUTES, KEY_USER_SCORE)
                .forEach { secure.remove(it) }
        }
        // What the phone has stays: signing out is not a delete.
    }

    override suspend fun refreshUser(): AccountUser? {
        val t = token() ?: return null
        val user = runCatching { api.viewer(t) }.getOrNull() ?: return null
        remember(user)
        return user
    }

    override suspend fun toggleFavourite(animeId: Int): Boolean? {
        val t = token() ?: return null
        val after = runCatching { api.toggleFavourite(t, animeId) }.getOrNull() ?: return null
        return after.contains(animeId)
    }

    override suspend fun saveEntry(animeId: Int, status: ListStatus, score: Int, progress: Int): Boolean {
        val t = token() ?: return false
        val id = runCatching { api.saveEntry(t, animeId, status, score, progress) }.getOrNull() ?: return false
        library.markSynced(animeId, id)
        return true
    }

    override suspend fun deleteEntry(animeId: Int): Boolean {
        val t = token() ?: return false
        val remoteId = library.entryOf(animeId)?.remoteId ?: return false
        val done = runCatching { api.deleteEntry(t, remoteId) }.getOrNull() ?: return false
        if (done) library.markSynced(animeId, null)
        return done
    }

    override suspend fun pullLibrary(): List<LibraryEntry> {
        val t = token() ?: return emptyList()
        val user = _user.value ?: refreshUser() ?: return emptyList()
        return runCatching { api.library(t, user.id) }.getOrDefault(emptyList())
    }

    override suspend fun pullFavourites(): List<Favourite> {
        val t = token() ?: return emptyList()
        val user = _user.value ?: refreshUser() ?: return emptyList()
        return runCatching { api.favourites(t, user.id) }.getOrDefault(emptyList())
    }

    override suspend fun saveProgress(animeId: Int, episode: Int, progressMs: Long): Boolean {
        val t = token() ?: return false
        val entry = library.entryOf(animeId) ?: return false
        val id = runCatching {
            api.saveEntry(t, animeId, entry.status, entry.score, episode.coerceAtLeast(entry.progress))
        }.getOrNull() ?: return false
        library.markSynced(animeId, id)
        return true
    }

    /** The account's list and favourites, into the phone's; then what the phone changed, up. */
    suspend fun pullAll() {
        val entries = pullLibrary()
        if (entries.isNotEmpty()) library.replaceAll(entries)
        val favourites = pullFavourites()
        favourites.forEach { f -> library.setFavourite(f.animeId, f.title, f.cover, true, remote = true) }
        pushDirty()
    }

    /** What the phone changed and the account has not heard about yet. */
    suspend fun pushDirty() {
        if (_phase.value != AccountPhase.SIGNED_IN) return
        library.dirty().forEach { entry ->
            saveEntry(entry.animeId, entry.status, entry.score, entry.progress)
        }
        library.favourites.value.filter { !it.remote }.forEach { f ->
            toggleFavourite(f.animeId)?.let { on -> library.setFavourite(f.animeId, f.title, f.cover, on, remote = true) }
        }
    }

    /**
     * Asks for a sync soon: a screen that changed the library calls it, and the changes go up in
     * one push a few seconds later (a swipe through a list does not send one request per row).
     */
    fun syncSoon(delayMs: Long = 4000L) {
        if (_phase.value != AccountPhase.SIGNED_IN) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            runCatching { pushDirty() }
        }
    }

    private companion object {
        const val KEY_TOKEN = "anilist.token"
        const val KEY_REFRESH = "anilist.refresh"
        const val KEY_EXPIRES = "anilist.expires"
        const val KEY_USER_ID = "anilist.user.id"
        const val KEY_USER_NAME = "anilist.user.name"
        const val KEY_USER_AVATAR = "anilist.user.avatar"
        const val KEY_USER_BANNER = "anilist.user.banner"
        const val KEY_USER_URL = "anilist.user.url"
        const val KEY_USER_EPISODES = "anilist.user.episodes"
        const val KEY_USER_MINUTES = "anilist.user.minutes"
        const val KEY_USER_SCORE = "anilist.user.score"
    }
}
