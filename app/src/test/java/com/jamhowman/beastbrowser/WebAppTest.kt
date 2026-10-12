package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.webapp.Display
import com.jamhowman.beastbrowser.webapp.ManifestIcon
import com.jamhowman.beastbrowser.webapp.WebAppJson
import com.jamhowman.beastbrowser.webapp.WebAppSpec
import com.jamhowman.beastbrowser.webapp.WebApps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #34: icon choice, letter-tile fallback, scope and stable shortcut ids. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebAppTest {
    private fun icon(src: String, size: Int, vararg p: String) = ManifestIcon("https://a.com/$src", size, p.toSet().ifEmpty { setOf("any") })

    @Test fun prefersMaskableIcon() {
        val icons = listOf(icon("big.png", 512), icon("mask.png", 192, "maskable"), icon("mask-small.png", 96, "maskable"))
        assertEquals("https://a.com/mask.png", WebApps.pickManifestIcon(icons)?.src)
    }

    @Test fun elseLargestAnyIconOfAtLeast144() {
        assertEquals("https://a.com/b.png", WebApps.pickManifestIcon(listOf(icon("a.png", 144), icon("b.png", 512)))?.src)
        assertEquals("https://a.com/svg", WebApps.pickManifestIcon(listOf(icon("svg", ManifestIcon.ANY)))?.src)
        assertNull(WebApps.pickManifestIcon(listOf(icon("small.png", 96), icon("tiny.png", 32))))
        assertNull(WebApps.pickManifestIcon(listOf(icon("logo.svg", ManifestIcon.ANY)))) // can't decode SVG
        assertNull(WebApps.pickManifestIcon(listOf(icon("mono.png", 512, "monochrome"))))
        assertNull(WebApps.pickManifestIcon(emptyList()))
    }

    @Test fun parsesSizes() {
        assertEquals(192, ManifestIcon.parseSizes("48x48 192x192"))
        assertEquals(ManifestIcon.ANY, ManifestIcon.parseSizes("any"))
        assertEquals(0, ManifestIcon.parseSizes(null))
        assertEquals(0, ManifestIcon.parseSizes("big"))
    }

    @Test fun tinyIconsAreNotUpscaled() {
        assertFalse(WebApps.bigEnough(16, 16))
        assertFalse(WebApps.bigEnough(64, 64))
        assertFalse(WebApps.bigEnough(180, 64))
        assertTrue(WebApps.bigEnough(96, 96))
        assertTrue(WebApps.bigEnough(180, 180))
    }

    @Test fun letterTileUsesThemeColourElseUlfurRed() {
        val t = WebApps.letterTile("https://www.bbc.co.uk/news", 0xFF112233.toInt())
        assertEquals("B", t.letter); assertEquals(0xFF112233.toInt(), t.color)
        val r = WebApps.letterTile("https://9gag.com", null)
        assertEquals("9", r.letter); assertEquals(0xFFFA1E4E.toInt(), r.color)
        assertEquals("U", WebApps.letterTile("https://", null).letter)
    }

    @Test fun scope() {
        assertTrue(WebApps.inScope("https://a.com/app/x?y=1", "https://a.com/app/"))
        assertTrue(WebApps.inScope("https://a.com/app", "https://a.com/app/"))
        assertTrue(WebApps.inScope("https://A.com:443/", "https://a.com/"))
        assertFalse(WebApps.inScope("https://a.com/other", "https://a.com/app/"))
        assertFalse(WebApps.inScope("https://evil.com/app/", "https://a.com/app/"))
        assertFalse(WebApps.inScope("http://a.com/app/", "https://a.com/app/"))
        assertFalse(WebApps.inScope("https://sub.a.com/", "https://a.com/"))
        assertEquals("https://a.com/app/", WebApps.defaultScope("https://a.com/app/index.html?src=pwa"))
        assertEquals("https://a.com/", WebApps.defaultScope("https://a.com"))
    }

    @Test fun stableIdPerSite() {
        val a = WebAppSpec("https://a.com/?pwa=1", "https://a.com/")
        assertEquals(WebApps.shortcutId(a, "https://a.com/x"), WebApps.shortcutId(a.copy(startUrl = "https://a.com/home"), "https://a.com/y"))
        assertEquals(WebApps.shortcutId(null, "https://a.com/1"), WebApps.shortcutId(null, "https://a.com/2"))
        assertEquals(WebApps.shortcutId(a, "https://a.com/"), WebApps.shortcutId(null, "https://a.com/zzz"))
        assertNotEquals(WebApps.shortcutId(null, "https://a.com/"), WebApps.shortcutId(null, "https://b.com/"))
        val withId = a.copy(id = "https://a.com/mail")
        assertNotEquals(WebApps.shortcutId(a, "https://a.com/"), WebApps.shortcutId(withId, "https://a.com/"))
        assertEquals(WebApps.shortcutId(withId, ""), WebApps.shortcutId(withId.copy(startUrl = "https://a.com/x"), ""))
        assertTrue(WebApps.shortcutId(a, "").matches(Regex("webapp-[0-9a-f]{16}")))
    }

    @Test fun label() {
        val s = WebAppSpec("https://a.com/", "https://a.com/", name = "Alpha Mail", shortName = "Mail")
        assertEquals("Mail", WebApps.label(s, "Inbox", "https://a.com/"))
        assertEquals("Alpha Mail", WebApps.label(s.copy(shortName = " "), "Inbox", "https://a.com/"))
        assertEquals("Inbox", WebApps.label(null, "Inbox", "https://a.com/"))
        assertEquals("bbc.co.uk", WebApps.label(null, "", "https://www.bbc.co.uk/"))
    }

    @Test fun colours() {
        assertEquals(0xFFFF0000.toInt(), WebApps.parseColor("#f00"))
        assertEquals(0xFF112233.toInt(), WebApps.parseColor("#112233ff"))
        assertEquals(0xFF0A141E.toInt(), WebApps.parseColor("rgb(10, 20, 30)"))
        assertNull(WebApps.parseColor("red"))
        assertTrue(WebApps.isLight(0xFFFFFFFF.toInt())); assertFalse(WebApps.isLight(0xFF000000.toInt()))
    }

    @Test fun parsesGeckoManifestAndRoundTrips() {
        val m = JSONObject("""{"start_url":"/app/?src=pwa","scope":"https://a.com/app/","name":"Alpha","short_name":"A",
            "display":"minimal-ui","theme_color":"#123456","background_color":"#ffffff","orientation":"portrait",
            "icons":[{"src":"/i/192.png","sizes":["192x192"],"purpose":["any"]},{"src":"https://a.com/i/m.png","sizes":"512x512","purpose":"maskable any"}]}""")
        val s = WebAppJson.fromManifest(m, "https://a.com/app/page")!!
        assertEquals("https://a.com/app/?src=pwa", s.startUrl)
        assertEquals(Display.MINIMAL_UI, s.display)
        assertEquals(0xFF123456.toInt(), s.themeColor)
        assertEquals("https://a.com/i/192.png", s.icons[0].src)
        assertEquals("https://a.com/i/m.png", WebApps.pickManifestIcon(s.icons)?.src)
        val back = WebAppJson.fromJson(WebAppJson.toJson(s))!!
        assertEquals(s.copy(icons = emptyList()), back)
    }

    @Test fun crossOriginStartUrlAndBadScopeFallBack() {
        val m = JSONObject("""{"start_url":"https://evil.com/","scope":"https://a.com/other/"}""")
        val s = WebAppJson.fromManifest(m, "https://a.com/app/x")!!
        assertEquals("https://a.com/app/x", s.startUrl)
        assertEquals("https://a.com/app/", s.scope)
        assertEquals(Display.STANDALONE, s.display)
        assertNotNull(WebAppJson.fromManifest(JSONObject("{}"), "https://a.com/"))
        assertNull(WebAppJson.fromJson("{\"start_url\":\"javascript:alert(1)\",\"scope\":\"x\"}"))
    }
}
