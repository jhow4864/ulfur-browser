package com.jamhowman.beastbrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Roadmap 10: on-device weekly tracker tally (own DB file, like the reading list). One row per local day,
 * site (top-level page host, see [TrackerTally.siteOf]) and [TrackerCategory] with a count. No URLs, no
 * paths, no timestamps finer than a day. Excluded from backups (BackupManager doesn't read it) and from
 * Android backup / device transfer (data_extraction_rules excludes every database), and never sent anywhere.
 *
 * Callers must check [TrackerTally.siteToRecord] first so private tabs are never recorded.
 * [record] is cheap (main thread): blocks are buffered in memory and written in one transaction a moment
 * later on [io]; [flush] forces the write (onPause, before reading the summary).
 */
class TrackerTallyDb private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private data class Key(val day: Long, val site: String, val category: TrackerCategory)

    private val io = Executors.newSingleThreadScheduledExecutor()
    /** Unwritten counts; guarded by itself. */
    private val pending = HashMap<Key, Long>()
    private var flushScheduled = false
    /** Serialises DB writes so [clear] can't be undone by a batch that was already taken. */
    private val writeLock = Any()
    @Volatile private var lastPruned = Long.MIN_VALUE

    init {
        // Drop days past the retention window even if nothing is blocked today.
        io.execute { runCatching { flush() } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tally(day INTEGER NOT NULL, site TEXT NOT NULL, category TEXT NOT NULL, " +
                "count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, site, category))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Counts [count] blocks of [category] on [site] for [day]. Written on [io] within [FLUSH_DELAY_MS]. */
    fun record(site: String, category: TrackerCategory, count: Long = 1, day: Long = TrackerTally.today()) {
        if (site.isEmpty() || count <= 0) return
        synchronized(pending) {
            pending.merge(Key(day, site, category), count, Long::plus)
            if (!flushScheduled) {
                flushScheduled = true
                io.schedule(Runnable { runCatching { flush() } }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Writes buffered counts now and prunes old days (once per day). Safe from any thread. */
    fun flush(today: Long = TrackerTally.today()) {
        synchronized(writeLock) {
            writePending()
            if (lastPruned != today) prune(today)
        }
    }

    private fun writePending() {
        val batch = synchronized(pending) {
            flushScheduled = false
            HashMap(pending).also { pending.clear() }
        }
        if (batch.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("UPDATE tally SET count = count + ? WHERE day = ? AND site = ? AND category = ?").use { update ->
                for ((k, n) in batch) {
                    update.clearBindings()
                    update.bindLong(1, n); update.bindLong(2, k.day); update.bindString(3, k.site); update.bindString(4, k.category.key)
                    // SQLite on API 26-29 predates UPSERT, so update first and insert when the row is new.
                    if (update.executeUpdateDelete() == 0) {
                        db.insert("tally", null, ContentValues().apply {
                            put("day", k.day); put("site", k.site); put("category", k.category.key); put("count", n)
                        })
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Flush on the background thread (e.g. from onPause). */
    fun flushAsync() { io.execute { runCatching { flush() } } }

    /** Deletes rows older than [TrackerTally.RETENTION_DAYS] days. Returns the number of rows removed. */
    fun prune(today: Long = TrackerTally.today()): Int = synchronized(writeLock) {
        lastPruned = today
        writableDatabase.delete("tally", "day <= ?", arrayOf(TrackerTally.pruneBefore(today).toString()))
    }

    /** Rows from [fromDay] to [toDay] inclusive (buffered counts included). */
    fun rows(fromDay: Long, toDay: Long): List<TallyRow> {
        synchronized(writeLock) { writePending() }
        return readableDatabase.rawQuery(
            "SELECT day, site, category, count FROM tally WHERE day >= ? AND day <= ? ORDER BY day",
            arrayOf(fromDay.toString(), toDay.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(TallyRow(c.getLong(0), c.getString(1), TrackerCategory.fromKey(c.getString(2)), c.getLong(3))) }
        }
    }

    /** Last 7 days, today included. */
    fun weekly(today: Long = TrackerTally.today()): WeeklySummary =
        TrackerTally.weekly(rows(TrackerTally.weekStart(today), today), today)

    /** "Clear tally" (also part of clearing history / clear on exit). Drops buffered counts too. */
    fun clear() {
        synchronized(writeLock) {
            synchronized(pending) { pending.clear() }
            writableDatabase.delete("tally", null, null)
        }
    }

    companion object {
        const val NAME = "tracker_tally.db"
        const val VERSION = 1
        /** Blocks arrive in bursts while a page loads; batch them into one write. */
        const val FLUSH_DELAY_MS = 3_000L
        @Volatile private var instance: TrackerTallyDb? = null
        fun get(context: Context): TrackerTallyDb =
            instance ?: synchronized(this) { instance ?: TrackerTallyDb(context.applicationContext).also { instance = it } }
    }
}
