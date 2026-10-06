package com.jamhowman.beastbrowser

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.BrowserDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.5: per-site autoplay override in site_prefs (beast.db v4), and the additive v3 -> v4 upgrade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteAutoplayDbTest {
    private lateinit var db: BrowserDb

    @Before fun setUp() {
        db = BrowserDb.get(ApplicationProvider.getApplicationContext<Application>())
        for (h in listOf("youtube.com", "example.org")) { db.setSitePrefs(h, false, 100); db.setAutoplay(h, null) }
    }

    @Test fun autoplayOverrideStoredPerRegistrableDomain() {
        db.setAutoplay("https://www.youtube.com/watch?v=1", "allow_all")
        assertEquals("allow_all", db.getSitePrefs("m.youtube.com").autoplay)
        assertEquals(mapOf("youtube.com" to "allow_all"), db.autoplaySites().filterKeys { it == "youtube.com" })
        db.setAutoplay("youtube.com", null)
        assertNull(db.getSitePrefs("youtube.com").autoplay)
        assertFalse(db.autoplaySites().containsKey("youtube.com"))
    }

    @Test fun autoplayKeepsZoomAndDesktopAndViceVersa() {
        db.setZoom("example.org", 150)
        db.setAutoplay("example.org", "block_all")
        db.setDesktop("example.org", true)
        val p = db.getSitePrefs("example.org")
        assertEquals(150, p.zoom); assertTrue(p.desktop); assertEquals("block_all", p.autoplay)
        db.setSitePrefs("example.org", false, 100)
        assertEquals("block_all", db.getSitePrefs("example.org").autoplay) // row kept for the override
        db.setAutoplay("example.org", null)
        assertTrue(db.getSitePrefs("example.org").isDefault)
    }

    @Test fun upgradeFromV3KeepsRowsAndAddsAColumn() {
        assertTrue(BrowserDb.VERSION >= 4)
        val raw = SQLiteDatabase.create(null)
        raw.execSQL("CREATE TABLE site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100)")
        raw.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, created INTEGER NOT NULL, folder_id TEXT)")
        raw.execSQL("INSERT INTO site_prefs(host, desktop, zoom) VALUES('bbc.co.uk', 1, 125)")
        db.onUpgrade(raw, 3, 4)
        raw.rawQuery("SELECT host, desktop, zoom, autoplay FROM site_prefs", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("bbc.co.uk", c.getString(0)); assertEquals(1, c.getInt(1)); assertEquals(125, c.getInt(2))
            assertTrue(c.isNull(3))
        }
        db.onUpgrade(raw, 3, 4) // re-running a half-applied upgrade must not throw
        raw.close()
    }
}
