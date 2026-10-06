package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.ForcedDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForcedDarkTest {
    @Test fun addSiteNormalisesToRegistrableDomain() {
        assertEquals("example.com", ForcedDark.normalizeSite("example.com"))
        assertEquals("example.com", ForcedDark.normalizeSite("  https://www.Example.com/page?x=1  "))
        assertEquals("bbc.co.uk", ForcedDark.normalizeSite("m.bbc.co.uk"))
        assertEquals("wikipedia.org", ForcedDark.normalizeSite("en.wikipedia.org/wiki/Wolf"))
        assertEquals("example.com", ForcedDark.normalizeSite("example.com:8080"))
        assertEquals("example.com", ForcedDark.normalizeSite("example.com."))
    }

    @Test fun addSiteRejectsNonSites() {
        for (bad in listOf(null, "", "   ", "localhost", "exa mple.com", "ftp://example.com", "javascript:alert(1)",
                "-bad.com", "bad-.com", "192.168.1.1", "a..b", "https://", "über.de")) {
            assertNull(bad, ForcedDark.normalizeSite(bad))
        }
    }

    @Test fun menuTileOnlyWhileForcedDarkRunsOnWebPages() {
        assertTrue(ForcedDark.menuTileShown(true, "https://example.com/", true))
        assertTrue(ForcedDark.menuTileShown(true, "http://example.com/", true))
        assertFalse(ForcedDark.menuTileShown(false, "https://example.com/", true))
        assertFalse(ForcedDark.menuTileShown(true, "https://example.com/", false)) // home screen
        assertFalse(ForcedDark.menuTileShown(true, "about:reader?url=x", true))
        assertFalse(ForcedDark.menuTileShown(true, null, true))
    }

    /** SPEC: off by default, BETA, and it builds on "Prefer dark websites" (same key, moved to Site content). */
    @Test fun settingsXml() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val xml = File(main, "res/xml/preferences_site_content.xml").readText()
        assertTrue(Regex("""app:key="force_dark"[^>]*app:defaultValue="false"""").containsMatchIn(xml))
        assertTrue(Regex("""app:key="force_dark"[^>]*app:dependency="dark_pages"""").containsMatchIn(xml))
        assertTrue(Regex("""app:key="dark_pages"[^>]*app:defaultValue="true"""").containsMatchIn(xml))
        assertFalse(File(main, "res/xml/preferences.xml").readText().contains("\"dark_pages\""))
    }
}
