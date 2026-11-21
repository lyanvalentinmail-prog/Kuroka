package app.kuroka.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kuroka.api.Anime
import app.kuroka.api.Catalog
import app.kuroka.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/*
 * The schedule: what airs next, grouped by the day it airs on, with the episode number and how long
 * it is until then. AniList gives the list in one page; the grouping into days is done here.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

/** One day of the schedule. */
data class ScheduleDay(
    val label: String,
    val startMs: Long,
    val anime: List<ScheduleEntry>,
)

/** One anime's next episode, and when it comes. */
data class ScheduleEntry(
    val anime: Anime,
    val episode: Int,
    val airingAt: Long,
    val inLabel: String,
)

class ScheduleViewModel(
    private val catalog: Catalog = Graph.catalog,
) : ViewModel() {

    private val _days = MutableStateFlow<List<ScheduleDay>>(emptyList())
    val days: StateFlow<List<ScheduleDay>> get() = _days

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> get() = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    private var page = 1
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        _loading.value = _days.value.isEmpty()
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { catalog.airingNext(page, 40) }.getOrNull() }
            _loading.value = false
            if (found == null) {
                _error.value = "The schedule did not load"
                loaded = false
                return@launch
            }
            _error.value = null
            _days.value = group(found.items)
        }
    }

    /** Asks for the next page of what airs (the list scrolled to its end). */
    fun more() {
        if (_loading.value) return
        page += 1
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { catalog.airingNext(page, 40) }.getOrNull() }
            if (found == null || !found.hasNext) {
                page -= 1
                return@launch
            }
            val known = _days.value.flatMap { day -> day.anime.map { it.anime.id } }.toSet()
            val fresh = found.items.filterNot { it.id in known }
            _days.value = group(_days.value.flatMap { day -> day.anime.map { it.anime } } + fresh)
        }
    }

    private fun group(anime: List<Anime>): List<ScheduleDay> {
        val now = System.currentTimeMillis()
        val entries = anime.mapNotNull { item ->
            val airing = item.nextAiring ?: return@mapNotNull null
            ScheduleEntry(
                anime = item,
                episode = airing.episode,
                airingAt = airing.airingAt * 1000L,
                inLabel = until(airing.airingAt * 1000L - now),
            )
        }.sortedBy { it.airingAt }
        val calendar = Calendar.getInstance()
        val out = LinkedHashMap<String, MutableList<ScheduleEntry>>()
        entries.forEach { entry ->
            calendar.timeInMillis = entry.airingAt
            val day = dayLabel(calendar, now)
            out.getOrPut(day) { mutableListOf() } += entry
        }
        return out.map { (label, list) ->
            ScheduleDay(label = label, startMs = list.first().airingAt, anime = list)
        }
    }

    private fun dayLabel(calendar: Calendar, now: Long): String {
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val tomorrow = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }
        return when {
            sameDay(calendar, today) -> "Today"
            sameDay(calendar, tomorrow) -> "Tomorrow"
            else -> "${DAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1]}, " +
                "${calendar.get(Calendar.DAY_OF_MONTH)} ${MONTHS[calendar.get(Calendar.MONTH)]}"
        }
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** "in 3 h", "in 2 d", "now". */
    private fun until(ms: Long): String {
        if (ms <= 0) return "now"
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "in $minutes min"
            minutes < 60 * 24 -> "in ${minutes / 60} h"
            else -> "in ${minutes / (60 * 24)} d"
        }
    }

    private companion object {
        val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}
