package app.kuroka.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.DownloadItem
import app.kuroka.api.DownloadState
import app.kuroka.di.Graph
import app.kuroka.host.downloads.DownloadRepo
import app.kuroka.host.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The downloads page: what is coming down, what is on the phone, what failed and why, and the room
 * all of it takes. From here an episode is paused, goes on again, is taken off, is copied to the
 * device's own Movies folder, or is played with no network at all.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

class DownloadsViewModel(
    private val downloads: DownloadRepo = Graph.downloads,
    private val prefs: Prefs = Graph.prefs,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> get() = _query

    private val _only = MutableStateFlow<DownloadState?>(null)
    /** A state the list is narrowed to (the chips at the top), or null for everything. */
    val only: StateFlow<DownloadState?> get() = _only

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice

    private val _busy = MutableStateFlow<String?>(null)
    /** The id of a download being copied to the device, so its row shows a spinner. */
    val busy: StateFlow<String?> get() = _busy

    val items: StateFlow<List<DownloadItem>> = downloads.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val active: StateFlow<Int> = downloads.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** What the page shows, narrowed by the field and the chip. */
    val rows: StateFlow<List<DownloadItem>> =
        combine(items, _query, _only) { all, query, only ->
            all.filter { item ->
                (only == null || item.state == only) &&
                    (query.isBlank() || item.title.contains(query, true) ||
                        (item.episodeTitle ?: "").contains(query, true))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The room the downloads take and the room they may still take ("1.2 GB of 8 GB"). */
    private val _storage = MutableStateFlow("")
    val storage: StateFlow<String> get() = _storage

    init {
        downloads.start()
        viewModelScope.launch { readStorage() }
        viewModelScope.launch {
            downloads.message.collect { message -> if (message != null) _notice.value = message }
        }
    }

    private suspend fun readStorage() {
        _storage.value = withContext(Dispatchers.IO) {
            val used = downloads.usedLabel()
            val left = DownloadItem.size(downloads.roomLeft().coerceAtLeast(0L))
            val budget = prefs.now.downloads.budgetMb
            if (budget > 0) "$used of ${DownloadItem.size(budget.toLong() * 1024 * 1024)} · $left free"
            else "$used taken · $left free on the phone"
        }
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setOnly(state: DownloadState?) {
        _only.value = state
    }

    fun pause(item: DownloadItem) {
        downloads.pause(item.id)
        _notice.value = "Episode ${item.episodeLabel} is waiting"
    }

    fun resume(item: DownloadItem) {
        downloads.resume(item.id)
        _notice.value = "Episode ${item.episodeLabel} is coming down again"
    }

    fun remove(item: DownloadItem) {
        downloads.remove(item.id)
        _notice.value = "Episode ${item.episodeLabel} is off the phone"
        viewModelScope.launch { readStorage() }
    }

    /** Every episode of one anime, off the phone. */
    fun removeOf(animeId: Int) {
        downloads.removeOf(animeId)
        _notice.value = "The downloads of that anime are gone"
        viewModelScope.launch { readStorage() }
    }

    fun pauseAll() {
        downloads.pauseAll()
        _notice.value = "The downloads are waiting"
    }

    fun resumeAll() {
        downloads.resumeAll()
        _notice.value = "The downloads are going again"
    }

    fun clearFinished() {
        downloads.clearFinished()
        _notice.value = "The finished downloads are gone"
        viewModelScope.launch { readStorage() }
    }

    /** Copies a finished download to the device's own Movies/Kuroka folder. */
    fun export(item: DownloadItem) {
        _busy.value = item.id
        viewModelScope.launch {
            val uri = downloads.exportToDevice(item)
            _busy.value = null
            _notice.value = if (uri != null) "Copied to Movies/Kuroka/${downloads.fileName(item)}"
            else "The phone would not take a copy"
            readStorage()
        }
    }

    /** Where an exported copy is, as a Uri a file manager or another player can open. */
    fun exportedUri(item: DownloadItem): Uri? = item.localUri?.let { Uri.parse(it) }

    fun clearNotice() {
        _notice.value = null
    }
}
