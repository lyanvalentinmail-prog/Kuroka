package app.kuroka.sources.anilist

import app.kuroka.api.AccountTokens
import app.kuroka.api.AccountUser
import app.kuroka.api.Favourite
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.sources.net.Http
import app.kuroka.sources.net.int
import app.kuroka.sources.net.text
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * The AniList account over HTTP: the OAuth sign-in (PKCE, so no secret travels in a browser),
 * the viewer, and the mutations the library and the favourites sync with. The tokens themselves
 * are kept by the host, in the phone's keystore; this file only speaks to the site.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class AniListApi(
    /** The client id of your own from https://anilist.co/settings/developer. */
    private val clientId: String = "",
    private val clientSecret: String = "",
    private val redirectUri: String = "kuroka://anilist/callback",
) {
    /** True when an OAuth sign-in is possible: a client id was built in. */
    val canOAuth: Boolean get() = clientId.isNotBlank()

    /** A PKCE pair: the verifier kept by the app, the challenge sent to the site. */
    fun pkce(): Pair<String, String> {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return verifier to base64Url(digest)
    }

    /** The page to open in a browser for the sign-in, or null without a client id. */
    fun authorizeUrl(state: String, codeChallenge: String): String? {
        if (!canOAuth) return null
        return okhttp3.HttpUrl.Builder()
            .scheme("https").host("anilist.co")
            .addPathSegments("api/v2/oauth/authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build().toString()
    }

    /** The tokens of a sign-in the browser came back from, or null when the site refused. */
    suspend fun exchangeCode(code: String, codeVerifier: String): AccountTokens? {
        val form = LinkedHashMap<String, String>()
        form["grant_type"] = "authorization_code"
        form["client_id"] = clientId
        if (clientSecret.isNotBlank()) form["client_secret"] = clientSecret
        form["redirect_uri"] = redirectUri
        form["code"] = code
        form["code_verifier"] = codeVerifier
        val answer = Http.json(AniListQueries.TOKEN, Http.jsonHeaders(), "POST", Http.formBody(form))
        return tokens(answer)
    }

    /** A new access token from a refresh one, or null when the sign-in has to start again. */
    suspend fun refresh(refreshToken: String): AccountTokens? {
        if (!canOAuth || refreshToken.isBlank()) return null
        val form = LinkedHashMap<String, String>()
        form["grant_type"] = "refresh_token"
        form["client_id"] = clientId
        if (clientSecret.isNotBlank()) form["client_secret"] = clientSecret
        form["refresh_token"] = refreshToken
        val answer = runCatching {
            Http.json(AniListQueries.TOKEN, Http.jsonHeaders(), "POST", Http.formBody(form))
        }.getOrNull() ?: return null
        return tokens(answer)
    }

    private fun tokens(o: JSONObject): AccountTokens? {
        val access = o.text("access_token") ?: return null
        val expiresIn = o.int("expires_in") ?: 3600
        return AccountTokens(
            accessToken = access,
            refreshToken = o.text("refresh_token"),
            expiresAt = System.currentTimeMillis() + (expiresIn - 60L) * 1000L,
        )
    }

    /** Who a token belongs to, and what they have watched. */
    suspend fun viewer(token: String): AccountUser? {
        val data = Http.graphql(AniListQueries.ENDPOINT, AniListQueries.VIEWER, JSONObject(), token, what = "sign-in")
        return AniListParse.viewer(data.optJSONObject("Viewer"))
    }

    /** The id of a user's list, so their library can be pulled. */
    suspend fun userId(token: String): Int? = viewer(token)?.id

    /** Puts an entry in the user's list; the site's id of that entry, or null. */
    suspend fun saveEntry(token: String, mediaId: Int, status: ListStatus, stars: Int, progress: Int,
                          notes: String? = null): Int? {
        val v = JSONObject()
            .put("mediaId", mediaId)
            .put("status", status.code)
            .put("progress", progress.coerceAtLeast(0))
        // AniList counts a score in points (0-100 by default): a star is 20 of them.
        if (stars > 0) v.put("score", (stars * 20).coerceIn(1, 100))
        if (!notes.isNullOrBlank()) v.put("notes", notes.take(2000))
        val data = Http.graphql(AniListQueries.ENDPOINT, AniListQueries.SAVE_ENTRY, v, token, what = "list entry")
        return data.optJSONObject("SaveMediaListEntry")?.int("id")
    }

    suspend fun deleteEntry(token: String, entryId: Int): Boolean {
        val v = JSONObject().put("id", entryId)
        val data = runCatching {
            Http.graphql(AniListQueries.ENDPOINT, AniListQueries.DELETE_ENTRY, v, token, what = "list entry")
        }.getOrNull() ?: return false
        return data.optJSONObject("DeleteMediaListEntry")?.optBoolean("deleted") == true
    }

    /** The user's favourites after the toggle, so the caller knows which side it landed on. */
    suspend fun toggleFavourite(token: String, animeId: Int): List<Int> {
        val v = JSONObject().put("animeId", animeId)
        val data = Http.graphql(AniListQueries.ENDPOINT, AniListQueries.TOGGLE_FAVOURITE, v, token, what = "favourite")
        val nodes = data.optJSONObject("ToggleFavourite")?.optJSONObject("anime")?.optJSONArray("nodes")
            ?: return emptyList()
        val out = ArrayList<Int>(nodes.length())
        for (i in 0 until nodes.length()) nodes.optJSONObject(i)?.int("id")?.let { out += it }
        return out
    }

    /** Whether the account has this anime as a favourite (an anime page's first read). */
    suspend fun isFavourite(token: String, animeId: Int): Boolean {
        val v = JSONObject().put("mediaId", animeId)
        val data = runCatching {
            Http.graphql(AniListQueries.ENDPOINT, AniListQueries.IS_FAVOURITE, v, token, what = "favourite")
        }.getOrNull() ?: return false
        return data.optJSONObject("Media")?.optBoolean("isFavourite") == true
    }

    /** One entry of the user's list, as the phone keeps it. */
    suspend fun listEntry(token: String, userId: Int, animeId: Int): LibraryEntry? {
        val v = JSONObject().put("mediaId", animeId).put("userId", userId)
        val data = runCatching {
            Http.graphql(AniListQueries.ENDPOINT, AniListQueries.MY_STATE, v, token, what = "list entry")
        }.getOrNull() ?: return null
        return AniListParse.listEntry(data.optJSONObject("MediaList"))
    }

    /** The whole anime list of the account, status by status, page by page. */
    suspend fun library(token: String, userId: Int, statuses: List<ListStatus> = ListStatus.tabs): List<LibraryEntry> {
        val out = ArrayList<LibraryEntry>()
        for (status in statuses) {
            var page = 1
            var guard = 0
            while (guard++ < 20) {
                val v = JSONObject().put("userId", userId).put("status", status.code)
                    .put("perPage", 50).put("page", page)
                val data = runCatching {
                    Http.graphql(AniListQueries.ENDPOINT, AniListQueries.MEDIA_LIST, v, token, what = "library")
                }.getOrNull() ?: break
                val parsed = AniListParse.page(data, "mediaList") { AniListParse.listEntry(it) }
                out += parsed.items
                if (!parsed.hasNext) break
                page++
            }
        }
        return out.distinctBy { it.animeId }
    }

    /** The account's favourites. */
    suspend fun favourites(token: String, userId: Int): List<Favourite> {
        val out = ArrayList<Favourite>()
        var page = 1
        var guard = 0
        while (guard++ < 10) {
            val v = JSONObject().put("userId", userId).put("perPage", 50).put("page", page)
            val data = runCatching {
                Http.graphql(AniListQueries.ENDPOINT, AniListQueries.FAVOURITES, v, token, what = "favourites")
            }.getOrNull() ?: break
            // The favourites of a page come as one node holding the anime list.
            val pageNode = data.optJSONObject("Page")
            val nodes = pageNode?.optJSONObject("favourites")?.optJSONObject("anime")?.optJSONArray("nodes")
            if (nodes != null) out += AniListParse.favourites(nodes)
            if (pageNode?.optJSONObject("pageInfo")?.optBoolean("hasNextPage") != true) break
            page++
        }
        return out.distinctBy { it.animeId }
    }

    /** Base64 without the padding and with the URL alphabet, as PKCE asks for. */
    private fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
