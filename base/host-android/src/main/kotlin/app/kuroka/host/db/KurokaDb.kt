package app.kuroka.host.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/*
 * The phone's own copy of the library: the list an anime is on with its stars, the favourites,
 * how far every episode got, and the downloads. One SQLite file, no codegen, so the schema is
 * read here and nowhere else. An account syncs with it; without one it is all there is, and it
 * survives an update of the app.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class KurokaDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_LIBRARY (
              anime_id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              cover TEXT,
              status TEXT NOT NULL,
              score INTEGER NOT NULL DEFAULT 0,
              progress INTEGER NOT NULL DEFAULT 0,
              total_episodes INTEGER,
              source TEXT,
              audio TEXT NOT NULL DEFAULT 'sub',
              notes TEXT,
              updated_at INTEGER NOT NULL DEFAULT 0,
              started_at INTEGER,
              finished_at INTEGER,
              remote_id INTEGER,
              dirty INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX library_status ON $TABLE_LIBRARY (status)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_FAVOURITES (
              anime_id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              cover TEXT,
              added_at INTEGER NOT NULL DEFAULT 0,
              remote INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROGRESS (
              anime_id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              cover TEXT,
              episode_image TEXT,
              episode_number REAL NOT NULL,
              episode_title TEXT,
              position_ms INTEGER NOT NULL DEFAULT 0,
              duration_ms INTEGER NOT NULL DEFAULT 0,
              source TEXT,
              audio TEXT NOT NULL DEFAULT 'sub',
              updated_at INTEGER NOT NULL DEFAULT 0,
              completed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX progress_updated ON $TABLE_PROGRESS (updated_at DESC)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_DOWNLOADS (
              id TEXT PRIMARY KEY,
              anime_id INTEGER NOT NULL,
              episode_number REAL NOT NULL,
              audio TEXT NOT NULL DEFAULT 'sub',
              title TEXT NOT NULL,
              episode_title TEXT,
              cover TEXT,
              episode_image TEXT,
              uri TEXT NOT NULL,
              source TEXT,
              server TEXT,
              headers TEXT,
              subtitles TEXT,
              state TEXT NOT NULL,
              bytes_done INTEGER NOT NULL DEFAULT 0,
              bytes_total INTEGER NOT NULL DEFAULT 0,
              failure TEXT,
              added_at INTEGER NOT NULL DEFAULT 0,
              completed_at INTEGER,
              local_uri TEXT,
              position_ms INTEGER NOT NULL DEFAULT 0,
              duration_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX downloads_anime ON $TABLE_DOWNLOADS (anime_id)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_LOCAL (
              uri TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              added_at INTEGER NOT NULL DEFAULT 0,
              position_ms INTEGER NOT NULL DEFAULT 0,
              duration_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_CACHE (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // A new column of an existing table is added here; a table that changed shape is made anew
        // (the library is on the account, and the downloads can be taken again).
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_LIBRARY ADD COLUMN total_episodes INTEGER") }
        }
    }

    /** One write, so a screen that saves three things shows one change. */
    fun write(body: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            body(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insert(table: String, values: ContentValues) = writableDatabase.insertWithOnConflict(
        table, null, values, SQLiteDatabase.CONFLICT_REPLACE
    )

    fun update(table: String, values: ContentValues, where: String, args: Array<String>): Int =
        writableDatabase.update(table, values, where, args)

    fun delete(table: String, where: String?, args: Array<String>?): Int =
        writableDatabase.delete(table, where, args)

    /** Reads a table, closing the cursor whatever happens. */
    fun <T> read(table: String, where: String? = null, args: Array<String>? = null,
                 orderBy: String? = null, limit: Int? = null, map: (Cursor) -> T): List<T> {
        val cursor = readableDatabase.query(table, null, where, args, null, null, orderBy, limit?.toString())
        cursor.use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            return out
        }
    }

    fun <T> readQuery(sql: String, args: Array<String>?, map: (Cursor) -> T): List<T> {
        val cursor = readableDatabase.rawQuery(sql, args)
        cursor.use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            return out
        }
    }

    fun count(table: String, where: String? = null, args: Array<String>? = null): Long {
        val sql = StringBuilder("SELECT COUNT(*) FROM $table")
        if (where != null) sql.append(" WHERE ").append(where)
        val cursor = readableDatabase.rawQuery(sql.toString(), args)
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    companion object {
        const val NAME = "kuroka.db"
        const val VERSION = 2
        const val TABLE_LIBRARY = "library"
        const val TABLE_FAVOURITES = "favourites"
        const val TABLE_PROGRESS = "progress"
        const val TABLE_DOWNLOADS = "downloads"
        const val TABLE_LOCAL = "local_videos"
        const val TABLE_CACHE = "cache"
    }
}

/** The columns of a cursor, read without a crash when one is missing or null. */
fun Cursor.text(column: String): String? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getString(i)?.takeIf { it.isNotBlank() }
}

fun Cursor.int(column: String): Int? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getInt(i)
}

fun Cursor.long(column: String): Long? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getLong(i)
}

fun Cursor.double(column: String): Double? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getDouble(i)
}

fun Cursor.flag(column: String): Boolean {
    val i = getColumnIndex(column)
    return i >= 0 && getInt(i) != 0
}

/** A ContentValues a row is written with, without the nulls (a column keeps its default). */
fun values(body: ContentValues.() -> Unit): ContentValues = ContentValues().apply(body)
