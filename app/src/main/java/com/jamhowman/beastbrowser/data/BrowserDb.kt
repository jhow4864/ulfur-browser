package com.jamhowman.beastbrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jamhowman.beastbrowser.util.Domains
import java.util.concurrent.Executors

data class Entry(val id: Long, val url: String, val title: String, val time: Long)

/** Per-host browsing prefs: desktop site + page zoom (percent, 100 = default). */
data class SitePrefs(val host: String, val desktop: Boolean = false, val zoom: Int = 100) {
    val isDefault: Boolean get() = !desktop && zoom == 100
}

/** History + bookmarks + per-host site prefs. Private tabs never write history/bookmarks. */
class BrowserDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "beast.db", null, 2) {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT, visited INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_visited ON history(visited)")
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, created INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100)"
            )
        }
    }

    fun addHistory(url: String, title: String?) = io.execute {
        val db = writableDatabase
        // Collapse reloads / repeated visits within 30 minutes into one entry
        db.delete("history", "url = ? AND visited > ?", arrayOf(url, (System.currentTimeMillis() - 30 * 60_000).toString()))
        db.insert("history", null, ContentValues().apply {
            put("url", url); put("title", title ?: ""); put("visited", System.currentTimeMillis())
        })
    }

    fun updateHistoryTitle(url: String, title: String) = io.execute {
        writableDatabase.execSQL(
            "UPDATE history SET title = ? WHERE id = (SELECT id FROM history WHERE url = ? ORDER BY visited DESC LIMIT 1)",
            arrayOf(title, url)
        )
    }

    fun history(limit: Int = 500): List<Entry> = query("SELECT id, url, title, visited FROM history ORDER BY visited DESC LIMIT $limit")

    /** History + bookmarks matching [q] in url or title (case-insensitive). Bookmarks first. */
    fun searchLocal(q: String, limit: Int = 8): List<Entry> {
        val needle = q.trim()
        if (needle.isEmpty()) return emptyList()
        val like = "%${needle.replace("%", "").replace("_", "")}%"
        val out = LinkedHashMap<String, Entry>()
        readableDatabase.rawQuery(
            """SELECT id, url, title, created FROM bookmarks
               WHERE url LIKE ? COLLATE NOCASE OR title LIKE ? COLLATE NOCASE
               ORDER BY created DESC LIMIT ?""",
            arrayOf(like, like, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val e = Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3))
                out.putIfAbsent(e.url, e)
            }
        }
        readableDatabase.rawQuery(
            """SELECT id, url, title, visited FROM history
               WHERE url LIKE ? COLLATE NOCASE OR title LIKE ? COLLATE NOCASE
               ORDER BY visited DESC LIMIT ?""",
            arrayOf(like, like, limit.toString()),
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val e = Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3))
                out.putIfAbsent(e.url, e)
            }
        }
        return out.values.toList()
    }
    fun deleteHistory(id: Long) { writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) }
    fun clearHistory() { writableDatabase.delete("history", null, null) }

    fun bookmarks(): List<Entry> = query("SELECT id, url, title, created FROM bookmarks ORDER BY created DESC")
    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { it.moveToFirst() }
    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun removeBookmark(url: String) { writableDatabase.delete("bookmarks", "url = ?", arrayOf(url)) }
    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString())) }

    // ------------------------------------------------------------------ site prefs (desktop + zoom)

    /** Normalise to the registrable domain so www.example.com and m.example.com share prefs. */
    fun siteKey(hostOrUrl: String?): String {
        if (hostOrUrl.isNullOrBlank()) return ""
        val host = if (':' in hostOrUrl && '/' in hostOrUrl) {
            try { android.net.Uri.parse(hostOrUrl).host } catch (_: Exception) { null }
        } else hostOrUrl
        return Domains.registrable(host)
    }

    fun getSitePrefs(hostOrUrl: String?): SitePrefs {
        val host = siteKey(hostOrUrl)
        if (host.isEmpty()) return SitePrefs("")
        readableDatabase.rawQuery(
            "SELECT host, desktop, zoom FROM site_prefs WHERE host = ? LIMIT 1", arrayOf(host)
        ).use { c ->
            if (!c.moveToFirst()) return SitePrefs(host)
            return SitePrefs(c.getString(0), c.getInt(1) != 0, c.getInt(2).coerceIn(50, 300))
        }
    }

    fun setDesktop(hostOrUrl: String?, desktop: Boolean) {
        val host = siteKey(hostOrUrl)
        if (host.isEmpty()) return
        upsert(host, desktop = desktop, zoom = null)
    }

    fun setZoom(hostOrUrl: String?, zoom: Int) {
        val host = siteKey(hostOrUrl)
        if (host.isEmpty()) return
        upsert(host, desktop = null, zoom = zoom.coerceIn(50, 300))
    }

    fun setSitePrefs(hostOrUrl: String?, desktop: Boolean, zoom: Int) {
        val host = siteKey(hostOrUrl)
        if (host.isEmpty()) return
        upsert(host, desktop = desktop, zoom = zoom.coerceIn(50, 300))
    }

    /** All non-default zoom entries, for syncing into the siteprefs extension. */
    fun allZoomPrefs(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery("SELECT host, zoom FROM site_prefs WHERE zoom != 100", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1).coerceIn(50, 300)
        }
        return out
    }

    private fun upsert(host: String, desktop: Boolean?, zoom: Int?) {
        val current = getSitePrefs(host)
        val nextDesktop = desktop ?: current.desktop
        val nextZoom = zoom ?: current.zoom
        if (!nextDesktop && nextZoom == 100) {
            writableDatabase.delete("site_prefs", "host = ?", arrayOf(host))
            return
        }
        writableDatabase.insertWithOnConflict("site_prefs", null, ContentValues().apply {
            put("host", host)
            put("desktop", if (nextDesktop) 1 else 0)
            put("zoom", nextZoom)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun query(sql: String): List<Entry> = readableDatabase.rawQuery(sql, null).use { c ->
        val out = ArrayList<Entry>(c.count)
        while (c.moveToNext()) out += Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3))
        out
    }

    companion object {
        @Volatile private var instance: BrowserDb? = null
        fun get(context: Context): BrowserDb = instance ?: synchronized(this) {
            instance ?: BrowserDb(context).also { instance = it }
        }
    }
}
