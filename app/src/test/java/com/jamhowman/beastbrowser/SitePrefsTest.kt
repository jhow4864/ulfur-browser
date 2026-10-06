package com.jamhowman.beastbrowser

import android.app.Application
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SitePrefsTest {
    private lateinit var db: BrowserDb

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = BrowserDb.get(app)
        // Reset hosts we touch (singleton DB across tests)
        for (h in listOf("example.com", "bbc.co.uk", "ycombinator.com", "a.com", "b.com")) {
            db.setSitePrefs(h, false, 100)
        }
    }

    @Test fun siteKeyUsesRegistrableDomain() {
        assertEquals("bbc.co.uk", db.siteKey("https://www.bbc.co.uk/news"))
        assertEquals("example.com", db.siteKey("m.example.com"))
        assertEquals("example.com", db.siteKey("https://a.b.example.com/x"))
        assertEquals("ycombinator.com", db.siteKey("https://news.ycombinator.com/item?id=1"))
    }

    @Test fun desktopRememberedPerHost() {
        db.setDesktop("https://www.example.com/foo", true)
        assertTrue(db.getSitePrefs("https://m.example.com").desktop)
        assertTrue(db.getSitePrefs("example.com").desktop)
        db.setDesktop("example.com", false)
        assertFalse(db.getSitePrefs("www.example.com").desktop)
        assertTrue(db.getSitePrefs("example.com").isDefault)
    }

    @Test fun zoomRememberedAndClamped() {
        db.setZoom("https://news.ycombinator.com/", 150)
        assertEquals(150, db.getSitePrefs("https://news.ycombinator.com/item?id=1").zoom)
        assertEquals(150, db.getSitePrefs("ycombinator.com").zoom)
        db.setZoom("ycombinator.com", 999)
        assertEquals(300, db.getSitePrefs("ycombinator.com").zoom)
        db.setZoom("ycombinator.com", 100)
        assertTrue(db.getSitePrefs("ycombinator.com").isDefault)
        assertFalse(db.allZoomPrefs().containsKey("ycombinator.com"))
    }

    @Test fun allZoomPrefsSkipsDefault() {
        db.setSitePrefs("a.com", true, 100) // desktop only
        db.setSitePrefs("b.com", false, 125)
        val map = db.allZoomPrefs()
        assertFalse(map.containsKey("a.com"))
        assertEquals(125, map["b.com"])
    }
}
