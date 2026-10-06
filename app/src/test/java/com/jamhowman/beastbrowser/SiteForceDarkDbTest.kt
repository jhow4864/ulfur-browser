package com.jamhowman.beastbrowser

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.BrowserDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.5: forced dark exceptions live in site_prefs next to zoom (beast.db v5), and the additive v4 -> v5 upgrade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteForceDarkDbTest {
    private lateinit var db: BrowserDb

    @Before fun setUp() {
        db = BrowserDb.get(ApplicationProvider.getApplicationContext<Application>())
        for (h in listOf("github.com", "wikipedia.org")) {
            db.setSitePrefs(h, false, 100); db.setAutoplay(h, null); db.setForceDarkOff(h, false)
        }
    }

    @Test fun exceptionStoredPerRegistrableDomain() {
        db.setForceDarkOff("https://en.m.wikipedia.org/wiki/Wolf", true)
        assertTrue(db.getSitePrefs("de.wikipedia.org").forceDarkOff)
        assertTrue("wikipedia.org" in db.forceDarkOffSites())
        db.setForceDarkOff("wikipedia.org", false)
        assertFalse(db.getSitePrefs("wikipedia.org").forceDarkOff)
        assertFalse("wikipedia.org" in db.forceDarkOffSites())
        assertTrue(db.getSitePrefs("wikipedia.org").isDefault) // row removed again
    }

    @Test fun exceptionKeepsZoomAndAutoplay() {
        db.setZoom("github.com", 90)
        db.setAutoplay("github.com", "allow_all")
        db.setForceDarkOff("github.com", true)
        val p = db.getSitePrefs("github.com")
        assertEquals(90, p.zoom); assertEquals("allow_all", p.autoplay); assertTrue(p.forceDarkOff)
        assertEquals(90, db.allZoomPrefs()["github.com"])
        db.setSitePrefs("github.com", false, 100)
        db.setAutoplay("github.com", null)
        assertTrue(db.getSitePrefs("github.com").forceDarkOff) // row kept for the exception
    }

    @Test fun upgradeFromV4KeepsRowsAndDefaultsToForcing() {
        assertEquals(5, BrowserDb.VERSION)
        val raw = SQLiteDatabase.create(null)
        raw.execSQL("CREATE TABLE site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100, autoplay TEXT)")
        raw.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, created INTEGER NOT NULL, folder_id TEXT)")
        raw.execSQL("INSERT INTO site_prefs(host, desktop, zoom, autoplay) VALUES('bbc.co.uk', 0, 110, 'allow_all')")
        db.onUpgrade(raw, 4, 5)
        raw.rawQuery("SELECT host, zoom, autoplay, force_dark_off FROM site_prefs", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("bbc.co.uk", c.getString(0)); assertEquals(110, c.getInt(1)); assertEquals("allow_all", c.getString(2))
            assertEquals(0, c.getInt(3))
        }
        db.onUpgrade(raw, 4, 5) // a half-applied upgrade can be re-run
        raw.close()
    }

    @Test fun upgradeStraightFromV3() {
        val raw = SQLiteDatabase.create(null)
        raw.execSQL("CREATE TABLE site_prefs(host TEXT PRIMARY KEY NOT NULL, desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100)")
        raw.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, created INTEGER NOT NULL, folder_id TEXT)")
        raw.execSQL("INSERT INTO site_prefs(host, desktop, zoom) VALUES('bbc.co.uk', 1, 125)")
        db.onUpgrade(raw, 3, 5)
        raw.rawQuery("SELECT desktop, zoom, autoplay, force_dark_off FROM site_prefs", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0)); assertEquals(125, c.getInt(1)); assertTrue(c.isNull(2)); assertEquals(0, c.getInt(3))
        }
        raw.close()
    }
}
