package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.ui.HomeShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShortcutTest {
    @Test fun offeredOnlyForNormalWebPages() {
        assertTrue(HomeShortcut.eligible("https://example.com/a", isPrivate = false, onPage = true))
        assertTrue(HomeShortcut.eligible("http://example.com", isPrivate = false, onPage = true))
        assertFalse(HomeShortcut.eligible("https://example.com", isPrivate = true, onPage = true))
        assertFalse(HomeShortcut.eligible("https://example.com", isPrivate = false, onPage = false))
        assertFalse(HomeShortcut.eligible("about:home", isPrivate = false, onPage = true))
        assertFalse(HomeShortcut.eligible("moz-extension://x/reader.html", isPrivate = false, onPage = true))
        assertFalse(HomeShortcut.eligible("", isPrivate = false, onPage = true))
    }

    @Test fun labelFallsBackToHost() {
        assertEquals("Example Domain", HomeShortcut.label("Example Domain", "https://example.com"))
        assertEquals("bbc.co.uk", HomeShortcut.label("  ", "https://www.bbc.co.uk/news"))
        assertEquals(24, HomeShortcut.label("A".repeat(40), "https://x.com").length)
    }

    @Test fun letterFromHost() {
        assertEquals("B", HomeShortcut.letter("https://www.bbc.co.uk"))
        assertEquals("9", HomeShortcut.letter("https://9gag.com"))
        assertEquals("U", HomeShortcut.letter("https://"))
    }

    @Test fun idIsStablePerUrl() {
        assertEquals(HomeShortcut.id("https://a.com"), HomeShortcut.id("https://a.com"))
        assertNotEquals(HomeShortcut.id("https://a.com"), HomeShortcut.id("https://b.com"))
    }
}
