package app.kuroka.api

/*
 * The account: an AniList sign-in (or none). The library and the favourites live on the phone
 * either way; signing in adds the sync of them, and the profile page of the site.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

import kotlinx.coroutines.flow.StateFlow

/** The user of the account, as the site describes them. */
data class AccountUser(
    val id: Int,
    val name: String,
    val avatar: String? = null,
    val banner: String? = null,
    val siteUrl: String? = null,
    val episodesWatched: Int = 0,
    val minutesWatched: Long = 0L,
    val meanScore: Double = 0.0,
    val animeMeanScore: Double = 0.0,
) {
    val daysWatched: Double get() = minutesWatched / 60.0 / 24.0
}

/** Where the sign-in is at. */
enum class AccountPhase { SIGNED_OUT, SIGNING_IN, SIGNED_IN, FAILED }

/** The tokens of a sign-in; they live in the phone's keystore, never in the library. */
data class AccountTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** When [accessToken] stops working, in milliseconds since the epoch. */
    val expiresAt: Long = 0L,
) {
    val expired: Boolean get() = expiresAt in 1 until System.currentTimeMillis()
}

/**
 * The account service: sign in (a token pasted from the site, or its OAuth page), keep the
 * tokens, and push and pull the library and the favourites.
 */
interface AccountService {
    /** True when an OAuth sign-in is possible (a client id was built in). */
    val canOAuth: Boolean

    val phase: StateFlow<AccountPhase>
    val user: StateFlow<AccountUser?>

    /** Signs in with a personal access token of the site (Settings, Account). */
    suspend fun signInWithToken(token: String): AccountUser?

    /** The authorize URL to open in a browser, with the PKCE pair of this attempt. */
    fun authorizeUrl(state: String, codeChallenge: String): String?

    /** Finishes the OAuth sign-in with the code the browser came back with. */
    suspend fun completeSignIn(code: String, codeVerifier: String): AccountUser?

    suspend fun signOut()

    /** The user's own profile (episodes watched, mean score). */
    suspend fun refreshUser(): AccountUser?

    /** Marks an anime as a favourite of the account, or takes the mark off; the new state. */
    suspend fun toggleFavourite(animeId: Int): Boolean?

    /** Puts an entry in the account's list (status, stars, episodes watched). */
    suspend fun saveEntry(animeId: Int, status: ListStatus, score: Int, progress: Int): Boolean

    /** Takes an entry off the account's list. */
    suspend fun deleteEntry(animeId: Int): Boolean

    /** The account's whole anime list, as the phone keeps it. */
    suspend fun pullLibrary(): List<LibraryEntry>

    /** The account's favourites. */
    suspend fun pullFavourites(): List<Favourite>

    /** The progress of one episode, so the site resumes where the phone stopped. */
    suspend fun saveProgress(animeId: Int, episode: Int, progressMs: Long = 0L): Boolean
}
