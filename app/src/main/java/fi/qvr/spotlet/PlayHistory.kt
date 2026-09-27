package fi.qvr.spotlet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/**
 * Play history in a small SQLite database. One row per counted play (see [PlayTracker]).
 * Writes happen on the service's background thread; reads on the stats screen's.
 */
class PlayHistory private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE plays (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                user TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                listened_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX plays_started_at ON plays(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(play: PlayTracker.Play): Long {
        val id = writableDatabase.insert("plays", null, ContentValues().apply {
            put("started_at", play.startedAt)
            put("user", play.user.orEmpty())
            put("title", play.title)
            put("artist", play.artist.orEmpty())
            put("album", play.album.orEmpty())
            put("duration_ms", play.durationMs)
            put("listened_ms", play.listenedMs)
        })
        // Keep the file bounded on tiny devices: tens of thousands of rows is years of listening.
        if (id > 0 && id % 500 == 0L) {
            writableDatabase.execSQL(
                "DELETE FROM plays WHERE id NOT IN (SELECT id FROM plays ORDER BY started_at DESC LIMIT $MAX_ROWS)"
            )
        }
        return id
    }

    fun updateListened(rowId: Long, listenedMs: Long) {
        if (rowId <= 0) return
        writableDatabase.update(
            "plays", ContentValues().apply { put("listened_ms", listenedMs) },
            "id = ?", arrayOf(rowId.toString()),
        )
    }

    fun clear() {
        writableDatabase.delete("plays", null, null)
    }

    data class Entry(
        val startedAt: Long,
        val user: String,
        val title: String,
        val artist: String,
        val album: String,
        val listenedMs: Long,
    )

    data class Listener(val user: String, val plays: Int, val listenedMs: Long, val lastPlayedAt: Long)
    data class Ranked(val label: String, val detail: String?, val plays: Int)

    data class Stats(
        val plays: Int,
        val listenedMs: Long,
        val uniqueTracks: Int,
        val uniqueArtists: Int,
        val since: Long?,
        val playsToday: Int,
        val plays7d: Int,
        val plays30d: Int,
        val busiestHour: Int?,
        val listeners: List<Listener>,
        val topArtists: List<Ranked>,
        val topTracks: List<Ranked>,
        val recent: List<Entry>,
    )

    fun stats(now: Long = System.currentTimeMillis(), recentLimit: Int = 50): Stats {
        val db = readableDatabase
        val startOfToday = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24L * 60 * 60 * 1000

        var plays = 0; var listened = 0L; var uniqueTracks = 0; var uniqueArtists = 0
        var since: Long? = null; var today = 0; var week = 0; var month = 0
        db.rawQuery(
            """SELECT COUNT(*), COALESCE(SUM(listened_ms), 0),
                   COUNT(DISTINCT title || char(31) || artist), COUNT(DISTINCT artist), MIN(started_at),
                   COALESCE(SUM(started_at >= ?), 0), COALESCE(SUM(started_at >= ?), 0), COALESCE(SUM(started_at >= ?), 0)
               FROM plays""",
            arrayOf(startOfToday.toString(), (now - 7 * day).toString(), (now - 30 * day).toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                plays = c.getInt(0); listened = c.getLong(1)
                uniqueTracks = c.getInt(2); uniqueArtists = c.getInt(3)
                since = if (c.isNull(4)) null else c.getLong(4)
                today = c.getInt(5); week = c.getInt(6); month = c.getInt(7)
            }
        }

        val busiestHour = db.rawQuery(
            """SELECT CAST(strftime('%H', started_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS h, COUNT(*) AS n
               FROM plays GROUP BY h ORDER BY n DESC LIMIT 1""", null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

        val listeners = db.rawQuery(
            """SELECT user, COUNT(*), SUM(listened_ms), MAX(started_at) FROM plays
               GROUP BY user ORDER BY COUNT(*) DESC""", null,
        ).use { c -> buildList { while (c.moveToNext()) add(Listener(c.getString(0), c.getInt(1), c.getLong(2), c.getLong(3))) } }

        val topArtists = db.rawQuery(
            """SELECT artist, COUNT(*) AS n FROM plays WHERE artist != ''
               GROUP BY artist ORDER BY n DESC, MAX(started_at) DESC LIMIT 5""", null,
        ).use { c -> buildList { while (c.moveToNext()) add(Ranked(c.getString(0), null, c.getInt(1))) } }

        val topTracks = db.rawQuery(
            """SELECT title, artist, COUNT(*) AS n FROM plays
               GROUP BY title, artist ORDER BY n DESC, MAX(started_at) DESC LIMIT 5""", null,
        ).use { c -> buildList { while (c.moveToNext()) add(Ranked(c.getString(0), c.getString(1), c.getInt(2))) } }

        val recent = db.rawQuery(
            """SELECT started_at, user, title, artist, album, listened_ms FROM plays
               ORDER BY started_at DESC LIMIT $recentLimit""", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5)))
            }
        }

        return Stats(plays, listened, uniqueTracks, uniqueArtists, since, today, week, month,
            busiestHour, listeners, topArtists, topTracks, recent)
    }

    companion object {
        private const val MAX_ROWS = 50_000

        @Volatile private var instance: PlayHistory? = null

        fun get(context: Context): PlayHistory =
            instance ?: synchronized(this) { instance ?: PlayHistory(context).also { instance = it } }
    }
}
