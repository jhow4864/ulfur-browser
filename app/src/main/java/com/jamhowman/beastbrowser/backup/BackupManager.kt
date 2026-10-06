package com.jamhowman.beastbrowser.backup

import android.content.Context
import com.jamhowman.beastbrowser.BuildConfig
import com.jamhowman.beastbrowser.data.BookmarkFolder
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.CustomFolders
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.reader.ReadingListDb
import com.jamhowman.beastbrowser.reader.SavedArticle
import com.jamhowman.beastbrowser.ui.SpeedDialStore
import com.jamhowman.beastbrowser.ui.Tile
import com.jamhowman.beastbrowser.widget.SpeedDialWidget
import androidx.core.content.edit

/** Collects data for a backup and applies an imported one. Call off the main thread (DB / crypto work). */
object BackupManager {

    data class Sections(
        val logins: Boolean = true,
        val bookmarks: Boolean = true,
        val speedDial: Boolean = true,
        val readingList: Boolean = true,
        val settings: Boolean = true,
    )

    data class ImportSummary(
        val loginsAdded: Int = 0,
        val loginsUpdated: Int = 0,
        val loginsSkipped: Int = 0,
        /** Logins were selected but the vault couldn't be unlocked. */
        val loginsLocked: Boolean = false,
        val bookmarks: Int = 0,
        /** Custom bookmark folders created because they didn't exist on this device. */
        val folders: Int = 0,
        val speedDial: Int = 0,
        val articles: Int = 0,
        val settings: Int = 0,
        /** ui_theme was imported: call UiTheme.apply() on the main thread. */
        val themeChanged: Boolean = false,
    )

    /** User settings that travel in a backup (pref key → type). Session, realm and counter state never do. */
    val SETTINGS: Map<String, Class<*>> = mapOf(
        "block_ads" to Boolean::class.java, "ublock" to Boolean::class.java, "cosmetic" to Boolean::class.java,
        "dnt_gpc" to Boolean::class.java, "fingerprinting" to Boolean::class.java, "safe_browsing" to Boolean::class.java,
        "clear_on_exit" to Boolean::class.java, "search_suggestions" to Boolean::class.java,
        "dark_pages" to Boolean::class.java, "restore_tabs" to Boolean::class.java,
        "pip" to Boolean::class.java,
        "autoplay" to String::class.java,
        "doh_provider" to String::class.java, "doh_mode" to String::class.java, "doh_custom_url" to String::class.java,
        "doh_nextdns_id" to String::class.java,
        "https_mode" to String::class.java, "cookie_mode" to String::class.java, "search_engine" to String::class.java,
        "ui_theme" to String::class.java, "accent" to String::class.java, "accent_work" to String::class.java,
    )

    /** [Sections.logins] needs the vault unlocked; otherwise logins are left out (null). */
    fun collect(context: Context, s: Sections): BackupPayload {
        val db = BrowserDb.get(context)
        val reading = ReadingListDb.get(context)
        return BackupPayload(
            appVersion = BuildConfig.VERSION_NAME,
            logins = if (s.logins && PasswordVault.isUnlocked()) PasswordVault.fetchAll() else null,
            bookmarks = if (s.bookmarks) db.bookmarks().map { BackupBookmark(it.url, it.title, it.time, it.folderId) } else null,
            folders = if (s.bookmarks) CustomFolders.load() else null,
            speedDial = if (s.speedDial) SpeedDialStore.load().map { BackupTile(it.title, it.url) } else null,
            readingList = if (s.readingList) reading.list().mapNotNull { reading.get(it.id) }.map { a ->
                BackupArticle(a.url, a.title, a.byline, a.site, a.excerpt, a.html, a.lang, a.dir, a.published, a.savedAt, a.words, a.read)
            } else null,
            settings = if (s.settings) Prefs.sp.all.filter { (k, v) -> SETTINGS[k]?.isInstance(v) == true }.mapValues { it.value!! } else null,
        )
    }

    /**
     * Merges [p] (only the [s] sections) into this install; never deletes anything. Logins go into the vault
     * only if it is unlocked (the caller unlocks first and calls [PasswordVault.flushPending] afterwards).
     */
    fun apply(context: Context, p: BackupPayload, s: Sections): ImportSummary {
        var sum = ImportSummary()
        if (s.logins && !p.logins.isNullOrEmpty()) {
            val r = PasswordVault.importLogins(p.logins)
            sum = if (r == null) sum.copy(loginsLocked = true)
            else sum.copy(loginsAdded = r.added, loginsUpdated = r.updated, loginsSkipped = r.skipped)
        }
        if (s.bookmarks && !p.bookmarks.isNullOrEmpty()) {
            val db = BrowserDb.get(context)
            val fresh = BackupMerge.newBookmarks(db.bookmarks().mapTo(HashSet()) { it.url }, p.bookmarks) { it }
            // Folders missing on this device are created (with their nesting) instead of dropping to "All".
            val plan = BackupMerge.planFolders(
                existing = CustomFolders.load(),
                backupFolders = p.folders.orEmpty(),
                bookmarks = fresh,
                isBuiltIn = { BookmarkFolder.from(it) != null },
                newId = CustomFolders::newId,
            )
            CustomFolders.add(plan.create)
            val add = fresh.map { b -> b.folder?.let { b.copy(folder = plan.resolve(it)) } ?: b }
            add.forEach { db.addBookmark(it.url, it.title, it.folder, it.created.takeIf { c -> c > 0 } ?: System.currentTimeMillis()) }
            sum = sum.copy(bookmarks = add.size, folders = plan.create.size)
        }
        if (s.speedDial && !p.speedDial.isNullOrEmpty()) {
            val (merged, added) = BackupMerge.mergeSpeedDial(SpeedDialStore.load().map { BackupTile(it.title, it.url) }, p.speedDial)
            if (added > 0) {
                SpeedDialStore.save(merged.map { Tile(it.title, it.url) })
                SpeedDialWidget.refreshAll(context)
            }
            sum = sum.copy(speedDial = added)
        }
        if (s.readingList && !p.readingList.isNullOrEmpty()) {
            val db = ReadingListDb.get(context)
            val add = BackupMerge.newArticles(db.list().mapTo(HashSet()) { it.url }, p.readingList)
            add.forEach { a ->
                db.save(SavedArticle(0, a.url, a.title, a.byline, a.site, a.excerpt, a.html, a.lang, a.dir, a.published,
                    a.savedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), a.words, a.read))
            }
            sum = sum.copy(articles = add.size)
        }
        if (s.settings && !p.settings.isNullOrEmpty()) {
            val valid = p.settings.filter { (k, v) -> SETTINGS[k]?.isInstance(v) == true }
            Prefs.sp.edit {
                valid.forEach { (k, v) -> if (v is Boolean) putBoolean(k, v) else putString(k, v as String) }
            }
            sum = sum.copy(settings = valid.size, themeChanged = "ui_theme" in valid)
        }
        return sum
    }
}
