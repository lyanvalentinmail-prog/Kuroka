package app.kuroka.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.kuroka.BuildConfig
import app.kuroka.sources.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * The update check: the app asks its own GitHub releases whether there is a newer version, says
 * what it brings (the release's notes), takes the APK of this phone's ABI down into its cache and
 * hands it to the installer. A beta install only ever installs a beta, and only ever over its own
 * package, so the two channels stay apart.
 *
 * The flow follows Orpheon's UpdateChecker (GPL-3.0, © kota-zo,
 * base/app/src/main/java/com/music/spotui/UpdateChecker.kt): the same release names, the same
 * "download then install" split, and no automatic download of anything the user did not ask for.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

/** What the update check has found, or is doing. */
sealed interface UpdateState {
    /** No check has been made, or the app is up to date. */
    data object None : UpdateState

    /** A check is being made. */
    data object Checking : UpdateState

    /** A newer version is there, with its notes and its APKs. */
    data class Available(
        val version: String,
        val notes: String,
        val url: String,
        val beta: Boolean,
        val assets: List<UpdateAsset>,
    ) : UpdateState

    /** The APK of this phone is coming down. */
    data class Downloading(val version: String, val fraction: Float, val bytes: Long, val total: Long) : UpdateState

    /** The APK is on the phone and can be installed. */
    data class Ready(val version: String, val file: File) : UpdateState

    /** Something went wrong, said as a person reads it. */
    data class Failed(val why: String) : UpdateState
}

/** One APK of a release. */
data class UpdateAsset(val name: String, val url: String, val bytes: Long, val abi: String)

class UpdateChecker(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    /** What the check has found; the settings screen and the update banner collect it. */
    val state: StateFlow<UpdateState> get() = _state

    /** True when a check may find anything: the build knows its own repository. */
    val canCheck: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** This install's own version, as the release names say it ("1.2.0-beta.3"). */
    val version: String get() = BuildConfig.VERSION_NAME

    /** Whether this install is a beta (its application id ends in ".beta"). */
    private val betaInstall: Boolean get() = BuildConfig.APPLICATION_ID.endsWith(".beta") ||
        BuildConfig.APPLICATION_ID.endsWith(".dev")

    /** The ABI of this phone, which decides which APK of a release is the right one. */
    private val abi: String
        get() = android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: "arm64-v8a"

    /**
     * Asks GitHub for the newest release. [allowBeta] lets a beta install see the next beta; a
     * stable install is only ever offered a stable one, so an update is never a downgrade in
     * channel.
     */
    fun check(allowBeta: Boolean = betaInstall, quiet: Boolean = false) {
        if (!canCheck) {
            if (!quiet) _state.value = UpdateState.Failed("This build does not know its release page")
            return
        }
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            val found = withContext(Dispatchers.IO) { fetch(allowBeta) }
            _state.value = found ?: if (quiet) UpdateState.None else UpdateState.None
        }
    }

    private suspend fun fetch(allowBeta: Boolean): UpdateState? {
        val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15"
        val json = runCatching {
            Http.client.newCall(
                Request.Builder().url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Kuroka/${BuildConfig.VERSION_NAME}")
                    .build()
            ).execute().use { r -> if (r.isSuccessful) JSONArray(r.body?.string() ?: "[]") else null }
        }.getOrNull() ?: return UpdateState.Failed("The release page did not answer")

        for (i in 0 until json.length()) {
            val release = json.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) continue
            val isBeta = tag.contains('-')
            // A stable install is not offered a beta; a beta install takes the newest of either.
            if (isBeta && !allowBeta) continue
            if (compare(tag, version) <= 0) continue
            val assets = assetsOf(release)
            val mine = assets.firstOrNull { it.abi == abi } ?: assets.firstOrNull { it.abi == "universal" }
            if (mine == null && assets.isNotEmpty()) continue
            return UpdateState.Available(
                version = tag,
                notes = release.optString("body").orEmpty(),
                url = release.optString("html_url"),
                beta = isBeta,
                assets = assets,
            )
        }
        return null
    }

    /** The APKs of a release, by the names the build gives them (Kuroka-1.2.0-ARMv8.apk). */
    private fun assetsOf(release: JSONObject): List<UpdateAsset> {
        val name = BuildConfig.APK_NAME
        val arr = release.optJSONArray("assets") ?: return emptyList()
        val out = ArrayList<UpdateAsset>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val assetName = a.optString("name")
            if (!assetName.endsWith(".apk") || !assetName.startsWith(name)) continue
            val abi = when {
                assetName.contains("ARMv8") -> "arm64-v8a"
                assetName.contains("ARMv7") -> "armeabi-v7a"
                assetName.contains("universal") || assetName.contains("TV") -> "universal"
                else -> continue
            }
            out += UpdateAsset(
                name = assetName,
                url = a.optString("browser_download_url"),
                bytes = a.optLong("size"),
                abi = abi,
            )
        }
        // The TV APK is the universal one; one entry per ABI is enough here.
        return out.distinctBy { it.abi }
    }

    /** Takes the APK of this phone down into the cache, then offers to install it. */
    fun download(asset: UpdateAsset) {
        scope.launch {
            _state.value = UpdateState.Downloading(asset.name, 0f, 0L, asset.bytes)
            val file = withContext(Dispatchers.IO) { take(asset) }
            _state.value = file?.let { UpdateState.Ready(asset.name, it) }
                ?: UpdateState.Failed("The APK did not come down")
        }
    }

    private suspend fun take(asset: UpdateAsset): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, asset.name)
        runCatching {
            Http.client.newCall(
                Request.Builder().url(asset.url)
                    .header("User-Agent", "Kuroka/${BuildConfig.VERSION_NAME}")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val total = body.contentLength().let { if (it > 0) it else asset.bytes }
                var done = 0L
                target.outputStream().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastPublish > 200) {
                            lastPublish = now
                            _state.value = UpdateState.Downloading(
                                asset.name,
                                if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                                done, total,
                            )
                        }
                    }
                    out.flush()
                }
                target
            }
        }.getOrNull()?.takeIf { it.exists() && it.length() > 1_000_000L }
    }

    /** Hands the APK to the installer (the phone asks before it installs from this app again). */
    fun install(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        }.getOrNull() ?: Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Opens the release's own page, for a phone that will not install from the app. */
    fun openPage(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        /**
         * Compares two release versions ("1.2.0-beta.3"): numbers first, then the pre-release part,
         * where a version with none is newer than one with one.
         */
        fun compare(a: String, b: String): Int {
            fun parts(v: String): Pair<List<Int>, String> {
                val dash = v.indexOf('-')
                val numbers = (if (dash >= 0) v.substring(0, dash) else v).split('.').map { it.toIntOrNull() ?: 0 }
                val pre = if (dash >= 0) v.substring(dash + 1) else ""
                return numbers to pre
            }

            val (an, ap) = parts(a)
            val (bn, bp) = parts(b)
            val length = maxOf(an.size, bn.size)
            for (i in 0 until length) {
                val x = an.getOrElse(i) { 0 }
                val y = bn.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return when {
                ap.isEmpty() && bp.isEmpty() -> 0
                ap.isEmpty() -> 1
                bp.isEmpty() -> -1
                else -> comparePre(ap, bp)
            }
        }

        private fun comparePre(a: String, b: String): Int {
            val as_ = a.split('.')
            val bs = b.split('.')
            val length = maxOf(as_.size, bs.size)
            for (i in 0 until length) {
                val x = as_.getOrNull(i) ?: return -1
                val y = bs.getOrNull(i) ?: return 1
                val xn = x.toIntOrNull()
                val yn = y.toIntOrNull()
                val c = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return 0
        }
    }
}
