package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.util.Domains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainsTest {
    @Test fun registrableDomain() {
        assertEquals("bbc.co.uk", Domains.registrable("www.bbc.co.uk"))
        assertEquals("bbc.co.uk", Domains.registrable("news.bbc.co.uk"))
        assertEquals("example.com", Domains.registrable("a.b.example.com"))
        assertEquals("example.com", Domains.registrable("EXAMPLE.com."))
        assertEquals("user.github.io", Domains.registrable("user.github.io"))
        assertEquals("192.168.1.1", Domains.registrable("192.168.1.1"))
    }

    @Test fun sameSite() {
        assertTrue(Domains.sameSite("cdn.example.com", "www.example.com"))
        assertFalse(Domains.sameSite("doubleclick.net", "www.example.com"))
        assertFalse(Domains.sameSite("a.co.uk", "b.co.uk"))
    }

    @Test fun display() {
        assertEquals("youtube.com", Domains.display("www.YouTube.com"))
    }
}
