package com.jamhowman.beastbrowser.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One saved article for offline reading. [html] is Readability's cleaned article body. */
data class SavedArticle(
    val id: Long,
    val url: String,
    val title: String,
    val byline: String,
    val site: String,
    val excerpt: String,
    val html: String,
    val lang: String,
    val dir: String,
    val published: String,
    val savedAt: Long,
    val words: Int,
    val read: Boolean,
)

/** Local-only reading list (separate DB file, not shared with BrowserDb). */
class ReadingListDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE articles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL DEFAULT '',
                byline TEXT NOT NULL DEFAULT '',
                site TEXT NOT NULL DEFAULT '',
                excerpt TEXT NOT NULL DEFAULT '',
                html TEXT NOT NULL DEFAULT '',
                lang TEXT NOT NULL DEFAULT '',
                dir TEXT NOT NULL DEFAULT '',
                published TEXT NOT NULL DEFAULT '',
                saved_at INTEGER NOT NULL,
                words INTEGER NOT NULL DEFAULT 0,
                read INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX articles_saved ON articles(saved_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Inserts or replaces (same URL) and returns the row id. */
    fun save(a: SavedArticle): Long {
        val v = ContentValues().apply {
            put("url", a.url); put("title", a.title); put("byline", a.byline); put("site", a.site)
            put("excerpt", a.excerpt); put("html", a.html); put("lang", a.lang); put("dir", a.dir)
            put("published", a.published); put("saved_at", a.savedAt); put("words", a.words); put("read", if (a.read) 1 else 0)
        }
        val db = writableDatabase
        val existing = idForUrl(a.url)
        return if (existing != null) { db.update("articles", v, "id=?", arrayOf(existing.toString())); existing }
        else db.insertOrThrow("articles", null, v)
    }

    fun idForUrl(url: String): Long? =
        readableDatabase.rawQuery("SELECT id FROM articles WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun get(id: Long): SavedArticle? =
        readableDatabase.rawQuery("SELECT * FROM articles WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) row(it) else null }

    /** Newest first. The list view doesn't need the article body, so [SavedArticle.html] is empty here. */
    fun list(): List<SavedArticle> =
        readableDatabase.rawQuery(
            "SELECT id,url,title,byline,site,excerpt,'' AS html,lang,dir,published,saved_at,words,read FROM articles ORDER BY saved_at DESC", null
        ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    fun setRead(id: Long, read: Boolean) {
        writableDatabase.update("articles", ContentValues().apply { put("read", if (read) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) { writableDatabase.delete("articles", "id=?", arrayOf(id.toString())) }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM articles", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun row(c: Cursor) = SavedArticle(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        url = c.getString(c.getColumnIndexOrThrow("url")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        byline = c.getString(c.getColumnIndexOrThrow("byline")),
        site = c.getString(c.getColumnIndexOrThrow("site")),
        excerpt = c.getString(c.getColumnIndexOrThrow("excerpt")),
        html = c.getString(c.getColumnIndexOrThrow("html")),
        lang = c.getString(c.getColumnIndexOrThrow("lang")),
        dir = c.getString(c.getColumnIndexOrThrow("dir")),
        published = c.getString(c.getColumnIndexOrThrow("published")),
        savedAt = c.getLong(c.getColumnIndexOrThrow("saved_at")),
        words = c.getInt(c.getColumnIndexOrThrow("words")),
        read = c.getInt(c.getColumnIndexOrThrow("read")) != 0,
    )

    companion object {
        const val NAME = "reading_list.db"
        @Volatile private var instance: ReadingListDb? = null
        fun get(ctx: Context): ReadingListDb =
            instance ?: synchronized(this) { instance ?: ReadingListDb(ctx.applicationContext).also { instance = it } }
    }
}
