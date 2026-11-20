package app.kuroka.host.library

import app.kuroka.api.Audio
import app.kuroka.api.Favourite
import app.kuroka.api.LibraryEntry
import app.kuroka.api.ListStatus
import app.kuroka.api.WatchProgress
import app.kuroka.host.db.KurokaDb
import app.kuroka.host.db.flag
import app.kuroka.host.db.int
import app.kuroka.host.db.long
import app.kuroka.host.db.double
import app.kuroka.host.db.text
import app.kuroka.host.db.values
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/*
 * The library: what the phone keeps of what you watched, what you plan to watch, what you
 * finished and rated with stars, what you favourited, and how far every episode got. It is read
 * once at start and kept in memory as a flow, so every screen redraws when one row changes.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class LibraryStore(private val db: KurokaDb) {

    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    /** The whole list, newest change first. */
    val entries: StateFlow<List<LibraryEntry>> get() = _entries

    private val _favourites = MutableStateFlow<List<Favourite>>(emptyList())
    val favourites: StateFlow<List<Favourite>> get() = _favourites

    private val _progress = MutableStateFlow<List<WatchProgress>>(emptyList())
    /** Continue watching: one row per anime, the episode it stopped in, most recent first. */
    val progress: StateFlow<List<WatchProgress>> get() = _progress

    /** True once the three lists have been read from the file. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded

    suspend fun load() = withContext(Dispatchers.IO) {
        _entries.value = readEntries()
        _favourites.value = readFavourites()
        _progress.value = readProgress()
        _loaded.value = true
    }

    private fun readEntries(): List<LibraryEntry> = db.read(
        KurokaDb.TABLE_LIBRARY, orderBy = "updated_at DESC"
    ) { c ->
        LibraryEntry(
            animeId = c.int("anime_id") ?: 0,
            title = c.text("title") ?: "",
            cover = c.text("cover"),
            status = ListStatus.of(c.text("status")) ?: ListStatus.WATCHING,
            score = c.int("score") ?: 0,
            progress = c.int("progress") ?: 0,
            totalEpisodes = c.int("total_episodes"),
            source = c.text("source"),
            audio = Audio.of(c.text("audio")),
            notes = c.text("notes"),
            updatedAt = c.long("updated_at") ?: 0L,
            startedAt = c.long("started_at"),
            finishedAt = c.long("finished_at"),
            remoteId = c.int("remote_id"),
            dirty = c.flag("dirty"),
        )
    }

    private fun readFavourites(): List<Favourite> = db.read(
        KurokaDb.TABLE_FAVOURITES, orderBy = "added_at DESC"
    ) { c ->
        Favourite(
            animeId = c.int("anime_id") ?: 0,
            title = c.text("title") ?: "",
            cover = c.text("cover"),
            addedAt = c.long("added_at") ?: 0L,
            remote = c.flag("remote"),
        )
    }

    private fun readProgress(): List<WatchProgress> = db.read(
        KurokaDb.TABLE_PROGRESS, orderBy = "updated_at DESC"
    ) { c ->
        WatchProgress(
            animeId = c.int("anime_id") ?: 0,
            title = c.text("title") ?: "",
            cover = c.text("cover"),
            episodeImage = c.text("episode_image"),
            episodeNumber = c.double("episode_number") ?: 1.0,
            episodeTitle = c.text("episode_title"),
            positionMs = c.long("position_ms") ?: 0L,
            durationMs = c.long("duration_ms") ?: 0L,
            source = c.text("source") ?: "",
            audio = Audio.of(c.text("audio")),
            updatedAt = c.long("updated_at") ?: 0L,
            completed = c.flag("completed"),
        )
    }

    fun entryOf(animeId: Int): LibraryEntry? = _entries.value.firstOrNull { it.animeId == animeId }

    fun favouriteOf(animeId: Int): Favourite? = _favourites.value.firstOrNull { it.animeId == animeId }

    fun progressOf(animeId: Int): WatchProgress? = _progress.value.firstOrNull { it.animeId == animeId }

    fun isFavourite(animeId: Int): Boolean = favouriteOf(animeId) != null

    /** The entries the account has not heard about yet. */
    fun dirty(): List<LibraryEntry> = _entries.value.filter { it.dirty }

    /** One list, as the library screen shows it. */
    fun inStatus(status: ListStatus): List<LibraryEntry> = _entries.value.filter { it.status == status }

    /**
     * Saves an entry (its list, its stars, its episodes). [touch] puts it at the top of the list;
     * [dirty] marks it for the account sync.
     */
    suspend fun save(entry: LibraryEntry, touch: Boolean = true, dirty: Boolean = true) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val row = values {
                put("anime_id", entry.animeId)
                put("title", entry.title)
                entry.cover?.let { put("cover", it) }
                put("status", entry.status.code)
                put("score", entry.score.coerceIn(0, 5))
                put("progress", entry.progress.coerceAtLeast(0))
                entry.totalEpisodes?.let { put("total_episodes", it) }
                entry.source?.let { put("source", it) }
                put("audio", entry.audio.code)
                entry.notes?.let { put("notes", it) }
                put("updated_at", if (touch) now else entry.updatedAt)
                (entry.startedAt ?: if (touch) now else null)?.let { put("started_at", it) }
                (entry.finishedAt ?: if (entry.status == ListStatus.COMPLETED && touch) now else null)
                    ?.let { put("finished_at", it) }
                entry.remoteId?.let { put("remote_id", it) }
                put("dirty", if (dirty) 1 else 0)
            }
            db.insert(KurokaDb.TABLE_LIBRARY, row)
            reloadEntries()
        }

    /** Puts an anime on a list, keeping its stars and its progress. */
    suspend fun setStatus(anime: LibraryEntry, status: ListStatus) {
        val current = entryOf(anime.animeId)
        save(
            anime.copy(
                status = status,
                progress = current?.progress ?: anime.progress,
                score = current?.score ?: anime.score,
                totalEpisodes = current?.totalEpisodes ?: anime.totalEpisodes,
                finishedAt = if (status == ListStatus.COMPLETED) System.currentTimeMillis() else null,
            )
        )
    }

    /** The stars of an anime, 0 to 5 (0 takes them off). */
    suspend fun setScore(animeId: Int, stars: Int, title: String, cover: String? = null) {
        val current = entryOf(animeId)
        save(
            (current ?: LibraryEntry(animeId = animeId, title = title, cover = cover))
                .copy(score = stars.coerceIn(0, 5), title = current?.title ?: title)
        )
    }

    /** How many episodes have been watched (a list's progress). */
    suspend fun setEpisodeProgress(animeId: Int, episodes: Int) {
        val current = entryOf(animeId) ?: return
        val total = current.totalEpisodes
        val status = if (total != null && total > 0 && episodes >= total) ListStatus.COMPLETED else current.status
        save(current.copy(progress = episodes.coerceAtLeast(0), status = status))
    }

    suspend fun remove(animeId: Int) = withContext(Dispatchers.IO) {
        db.delete(KurokaDb.TABLE_LIBRARY, "anime_id = ?", arrayOf(animeId.toString()))
        reloadEntries()
    }

    /** Marks an entry as one the account has (its own id, nothing to push). */
    suspend fun markSynced(animeId: Int, remoteId: Int?) = withContext(Dispatchers.IO) {
        db.update(
            KurokaDb.TABLE_LIBRARY,
            values {
                put("dirty", 0)
                remoteId?.let { put("remote_id", it) }
            },
            "anime_id = ?", arrayOf(animeId.toString())
        )
        reloadEntries()
    }

    /** Replaces the whole list with what the account has (a sign-in, or a pull to refresh). */
    suspend fun replaceAll(remote: List<LibraryEntry>) = withContext(Dispatchers.IO) {
        db.write { db ->
            // What the phone changed and has not pushed yet is kept: the sync pushes it after.
            val dirtyIds = _entries.value.filter { it.dirty }.map { it.animeId }.toSet()
            remote.forEach { e ->
                if (e.animeId in dirtyIds) return@forEach
                db.insert(
                    KurokaDb.TABLE_LIBRARY,
                    values {
                        put("anime_id", e.animeId)
                        put("title", e.title)
                        e.cover?.let { put("cover", it) }
                        put("status", e.status.code)
                        put("score", e.score)
                        put("progress", e.progress)
                        e.totalEpisodes?.let { put("total_episodes", it) }
                        e.source?.let { put("source", it) }
                        put("audio", e.audio.code)
                        e.notes?.let { put("notes", it) }
                        put("updated_at", e.updatedAt)
                        e.remoteId?.let { put("remote_id", it) }
                        put("dirty", 0)
                    }
                )
            }
        }
        reloadEntries()
    }

    /** The heart on an anime page: on, or off again. */
    suspend fun toggleFavourite(animeId: Int, title: String, cover: String?): Boolean =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val had = favouriteOf(animeId) != null
            if (had) {
                db.delete(KurokaDb.TABLE_FAVOURITES, "anime_id = ?", arrayOf(animeId.toString()))
            } else {
                db.insert(
                    KurokaDb.TABLE_FAVOURITES,
                    values {
                        put("anime_id", animeId)
                        put("title", title)
                        cover?.let { put("cover", it) }
                        put("added_at", now)
                        put("remote", 0)
                    }
                )
            }
            reloadFavourites()
            !had
        }

    suspend fun setFavourite(animeId: Int, title: String, cover: String?, favourite: Boolean, remote: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (favourite) {
                db.insert(
                    KurokaDb.TABLE_FAVOURITES,
                    values {
                        put("anime_id", animeId)
                        put("title", title)
                        cover?.let { put("cover", it) }
                        put("added_at", favouriteOf(animeId)?.addedAt ?: System.currentTimeMillis())
                        put("remote", if (remote) 1 else 0)
                    }
                )
            } else {
                db.delete(KurokaDb.TABLE_FAVOURITES, "anime_id = ?", arrayOf(animeId.toString()))
            }
            reloadFavourites()
        }

    /** How far an episode got; a row per anime, so "continue watching" shows the last one. */
    suspend fun saveProgress(p: WatchProgress) = withContext(Dispatchers.IO) {
        // An episode that has been seen to its end is not offered as "continue".
        val completed = p.completed || (p.durationMs > 0 && p.positionMs >= p.durationMs - 15_000L)
        db.insert(
            KurokaDb.TABLE_PROGRESS,
            values {
                put("anime_id", p.animeId)
                put("title", p.title)
                p.cover?.let { put("cover", it) }
                p.episodeImage?.let { put("episode_image", it) }
                put("episode_number", p.episodeNumber)
                p.episodeTitle?.let { put("episode_title", it) }
                put("position_ms", if (completed) 0L else p.positionMs)
                put("duration_ms", p.durationMs)
                put("source", p.source)
                put("audio", p.audio.code)
                put("updated_at", System.currentTimeMillis())
                put("completed", if (completed) 1 else 0)
            }
        )
        reloadProgress()
        // The list follows what has been watched: an episode finished counts as one more.
        if (completed) {
            val entry = entryOf(p.animeId)
            val episodes = p.episodeNumber.toInt()
            if (entry == null) {
                save(
                    LibraryEntry(
                        animeId = p.animeId, title = p.title, cover = p.cover,
                        status = ListStatus.WATCHING, progress = episodes,
                        source = p.source, audio = p.audio,
                    ),
                    dirty = true,
                )
            } else if (episodes > entry.progress) {
                save(entry.copy(progress = episodes, source = p.source, audio = p.audio), dirty = true)
            }
        }
    }

    /** Takes an anime off "continue watching" (its row is removed, the list entry stays). */
    suspend fun forgetProgress(animeId: Int) = withContext(Dispatchers.IO) {
        db.delete(KurokaDb.TABLE_PROGRESS, "anime_id = ?", arrayOf(animeId.toString()))
        reloadProgress()
    }

    suspend fun clearProgress() = withContext(Dispatchers.IO) {
        db.delete(KurokaDb.TABLE_PROGRESS, null, null)
        reloadProgress()
    }

    /** What the recap page counts: episodes seen, anime finished, the stars given. */
    fun stats(): Stats {
        val entries = _entries.value
        val watched = _progress.value
        val completed = entries.filter { it.status == ListStatus.COMPLETED }
        val rated = entries.filter { it.score > 0 }
        // An estimate: the time seen of the episodes still in progress, plus a quarter of an hour
        // for every episode a list counts as watched (the site does not say how long each ran).
        val minutes = watched.sumOf { it.positionMs / 60000L } + entries.sumOf { it.progress.toLong() * 22L }
        return Stats(
            anime = entries.size,
            completed = completed.size,
            watching = entries.count { it.status == ListStatus.WATCHING },
            planned = entries.count { it.status == ListStatus.PLANNED },
            dropped = entries.count { it.status == ListStatus.DROPPED },
            favourites = _favourites.value.size,
            episodes = entries.sumOf { it.progress },
            minutes = minutes,
            rated = rated.size,
            averageStars = if (rated.isEmpty()) 0.0 else rated.sumOf { it.score }.toDouble() / rated.size,
            topGenres = emptyList(),
        )
    }

    private fun reloadEntries() {
        _entries.value = readEntries()
    }

    private fun reloadFavourites() {
        _favourites.value = readFavourites()
    }

    private fun reloadProgress() {
        _progress.value = readProgress()
    }
}

/** What the recap page shows of your watching. */
data class Stats(
    val anime: Int,
    val completed: Int,
    val watching: Int,
    val planned: Int,
    val dropped: Int,
    val favourites: Int,
    val episodes: Int,
    val minutes: Long,
    val rated: Int,
    val averageStars: Double,
    val topGenres: List<Pair<String, Int>>,
) {
    val hours: Long get() = minutes / 60
    val days: Double get() = minutes / 60.0 / 24.0
}
