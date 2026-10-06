package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    @Test fun hostDetection() {
        assertTrue(UrlUtils.looksLikeHost("bbc.co.uk"))
        assertTrue(UrlUtils.looksLikeHost("github.com/mozilla/gecko-dev"))
        assertTrue(UrlUtils.looksLikeHost("localhost:8080"))
        assertTrue(UrlUtils.looksLikeHost("192.168.0.1/admin"))
        assertFalse(UrlUtils.looksLikeHost("best pizza london"))
        assertFalse(UrlUtils.looksLikeHost("kotlin"))
        assertFalse(UrlUtils.looksLikeHost("999.1.1.1"))
    }

    @Test fun httpsVersion() {
        assertEquals("https://example.com/a?b=1", UrlUtils.httpsVersion("http://example.com/a?b=1"))
        assertNull(UrlUtils.httpsVersion("https://example.com"))
    }
}
