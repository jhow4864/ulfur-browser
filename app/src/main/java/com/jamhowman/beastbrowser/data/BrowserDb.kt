package com.jamhowman.beastbrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jamhowman.beastbrowser.util.Domains
import java.util.concurrent.Executors

/** A history or bookmark row. [folderId] is a [BookmarkFolder] id (bookmarks only; null = no folder). */
data class Entry(val id: Long, val url: String, val title: String, val time: Long, val folderId: String? = null)

/**
 * Per-host browsing prefs: desktop site + page zoom (percent, 100 = default).
 * 2.5: [autoplay] = per-site autoplay override ([com.jamhowman.beastbrowser.browser.AutoplayPolicy.Mode] key), null = global.
 */
data class SitePrefs(val host: String, val desktop: Boolean = false, val zoom: Int = 100, val autoplay: String? = null) {
    val isDefault: Boolean get() = !desktop && zoom == 100 && autoplay == null
}

/** History + bookmarks + per-host site prefs. Private tabs never write history/bookmarks. */
class BrowserDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "beast.db", null, VERSION) {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT, visited INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_visited ON history(visited)")
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, created INTEGER NOT NULL, folder_id TEXT)")
        db.execSQL(
            "CREATE TABLE site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100, autoplay TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100)"
            )
        }
        if (oldVersion < 3) {
            // 2.3.4: bookmark folders. Guarded so a half-applied upgrade can't brick the DB.
            try { db.execSQL("ALTER TABLE bookmarks ADD COLUMN folder_id TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            // 2.5: per-site autoplay override (nullable: existing rows keep the global setting). Additive only.
            try { db.execSQL("ALTER TABLE site_prefs ADD COLUMN autoplay TEXT") } catch (_: Exception) {}
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

    /** All bookmarks, or only those in [folderId] when given. */
    fun bookmarks(folderId: String? = null): List<Entry> =
        if (folderId == null) queryBookmarks("SELECT id, url, title, created, folder_id FROM bookmarks ORDER BY created DESC")
        else queryBookmarks(
            "SELECT id, url, title, created, folder_id FROM bookmarks WHERE folder_id = ? ORDER BY created DESC",
            arrayOf(folderId),
        )
    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { it.moveToFirst() }
    fun addBookmark(url: String, title: String, folderId: String? = null, created: Long = System.currentTimeMillis()) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title); put("created", created)
            if (folderId != null) put("folder_id", folderId) else putNull("folder_id")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun setBookmarkFolder(id: Long, folderId: String?) {
        writableDatabase.update("bookmarks", ContentValues().apply {
            if (folderId != null) put("folder_id", folderId) else putNull("folder_id")
        }, "id = ?", arrayOf(id.toString()))
    }
    /** Moves every bookmark in folder [from] to [to] (null = no folder). Used when a custom folder is deleted. */
    fun moveFolderBookmarks(from: String, to: String?) {
        writableDatabase.update("bookmarks", ContentValues().apply {
            if (to != null) put("folder_id", to) else putNull("folder_id")
        }, "folder_id = ?", arrayOf(from))
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
            "SELECT host, desktop, zoom, autoplay FROM site_prefs WHERE host = ? LIMIT 1", arrayOf(host)
        ).use { c ->
            if (!c.moveToFirst()) return SitePrefs(host)
            return SitePrefs(c.getString(0), c.getInt(1) != 0, c.getInt(2).coerceIn(50, 300), if (c.isNull(3)) null else c.getString(3))
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

    /** 2.5: per-site autoplay override ([AutoplayPolicy.Mode] key), or null to follow the global setting. */
    fun setAutoplay(hostOrUrl: String?, mode: String?) {
        val host = siteKey(hostOrUrl)
        if (host.isEmpty()) return
        upsert(host, desktop = null, zoom = null, autoplay = mode ?: CLEAR)
    }

    /** Hosts with an autoplay override → override key (Settings > Site content > Allowed sites). */
    fun autoplaySites(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        readableDatabase.rawQuery("SELECT host, autoplay FROM site_prefs WHERE autoplay IS NOT NULL", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    /** All non-default zoom entries, for syncing into the siteprefs extension. */
    fun allZoomPrefs(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery("SELECT host, zoom FROM site_prefs WHERE zoom != 100", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1).coerceIn(50, 300)
        }
        return out
    }

    /** null = keep the current value; for [autoplay], [CLEAR] removes the override. */
    private fun upsert(host: String, desktop: Boolean?, zoom: Int?, autoplay: String? = null) {
        val current = getSitePrefs(host)
        val next = current.copy(
            desktop = desktop ?: current.desktop,
            zoom = zoom ?: current.zoom,
            autoplay = when (autoplay) { null -> current.autoplay; CLEAR -> null; else -> autoplay },
        )
        if (next.isDefault) {
            writableDatabase.delete("site_prefs", "host = ?", arrayOf(host))
            return
        }
        writableDatabase.insertWithOnConflict("site_prefs", null, ContentValues().apply {
            put("host", host)
            put("desktop", if (next.desktop) 1 else 0)
            put("zoom", next.zoom)
            if (next.autoplay == null) putNull("autoplay") else put("autoplay", next.autoplay)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun query(sql: String): List<Entry> = readableDatabase.rawQuery(sql, null).use { c ->
        val out = ArrayList<Entry>(c.count)
        while (c.moveToNext()) out += Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3))
        out
    }

    private fun queryBookmarks(sql: String, args: Array<String>? = null): List<Entry> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Entry>(c.count)
            while (c.moveToNext()) {
                out += Entry(
                    c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3),
                    if (c.isNull(4)) null else c.getString(4),
                )
            }
            out
        }

    companion object {
        /** 3 = 2.3.4 bookmark folders; 4 = 2.5 site_prefs.autoplay. Upgrades are additive only. */
        const val VERSION = 4
        private const val CLEAR = "\u0000clear"
        @Volatile private var instance: BrowserDb? = null
        fun get(context: Context): BrowserDb = instance ?: synchronized(this) {
            instance ?: BrowserDb(context).also { instance = it }
        }
    }
}
