package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.webapp.IconCandidate
import com.jamhowman.beastbrowser.webapp.ManifestIcon
import com.jamhowman.beastbrowser.webapp.PageIcon
import com.jamhowman.beastbrowser.webapp.PageIcons
import com.jamhowman.beastbrowser.webapp.WebAppSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageIconsTest {
    private val bbc = "https://www.bbc.co.uk/weather/2643743"
    private val bbcHtml = """<html><head><title>London - BBC Weather</title>
        <link rel="apple-touch-icon-precomposed" sizes="152x152" href="https://static.files.bbci.co.uk/weather/1.2/icons/apple-touch-icon-152.png">
        <link rel="icon" type="image/x-icon" href="/favicon.ico">
        <link rel="mask-icon" href="/safari.svg" color="#000">
        <link rel="stylesheet" href="/main.css"></head></html>"""

    @Test fun parsesBbcWeatherLinks() {
        val icons = PageIcons.parse(bbcHtml, bbc)
        assertEquals(listOf(
            PageIcon("https://static.files.bbci.co.uk/weather/1.2/icons/apple-touch-icon-152.png", 152),
            PageIcon("https://www.bbc.co.uk/favicon.ico", 0),
        ), icons)
    }

    @Test fun allIconRelsAndQuotingStyles() {
        val html = """<LINK REL='shortcut icon' HREF='/a.png'><link rel=apple-touch-icon href=b.png sizes=180x180>
            <link rel="icon" href="c.png?v=1&amp;x=2"><link rel="preload" href="/no.png">"""
        val hrefs = PageIcons.parse(html, "https://ex.com/dir/page").map { it.href }
        assertEquals(listOf("https://ex.com/a.png", "https://ex.com/dir/b.png", "https://ex.com/dir/c.png?v=1&x=2"), hrefs)
    }

    @Test fun relativeHrefUsesBaseElement() {
        val html = """<base href="https://ex.com/static/"><link rel=icon sizes=192x192 href="../img/i.png"><link rel=icon href="//cdn.ex.net/j.png">"""
        val hrefs = PageIcons.parse(html, "https://ex.com/a/b/c").map { it.href }
        assertEquals(listOf("https://ex.com/img/i.png", "https://cdn.ex.net/j.png"), hrefs)
    }

    @Test fun cdnHrefOnOtherDomainAllowed() {
        val i = PageIcons.parse("""<link rel=icon sizes=256x256 href="https://cdn.example-static.net/i/256.png">""", "https://site.org/")
        assertEquals("https://cdn.example-static.net/i/256.png", i.single().href)
    }

    @Test fun multipleSizesAndAny() {
        val html = """<link rel=icon sizes="16x16 32x32 192X192" href=/m.png><link rel=icon sizes=any href=/any.png>"""
        val i = PageIcons.parse(html, "https://ex.com/")
        assertEquals(192, i[0].size)
        assertEquals(ManifestIcon.ANY, i[1].size)
        assertEquals("https://ex.com/any.png", PageIcons.rank(i).first().href)
    }

    @Test fun noSizesTriedAfterSized() {
        val html = """<link rel=icon href=/nosize.png><link rel=icon sizes=32x32 href=/32.png><link rel=apple-touch-icon sizes=180x180 href=/180.png>"""
        assertEquals(listOf("/180.png", "/32.png", "/nosize.png"),
            PageIcons.rank(PageIcons.parse(html, "https://ex.com/")).map { it.href.removePrefix("https://ex.com") })
    }

    @Test fun svgSkippedSoOnlySvgFallsBackToFixedPaths() {
        val html = """<link rel=icon type="image/svg+xml" href=/i><link rel=icon href=/logo.svg?v=2>"""
        assertTrue(PageIcons.parse(html, "https://ex.com/").isEmpty())
        val c = PageIcons.candidates(null, "https://ex.com/p") { PageIcons.parse(html, "https://ex.com/") }.toList()
        assertEquals(listOf("fallback", "fallback", "fallback"), c.map { it.source })
        assertEquals("https://ex.com/apple-touch-icon.png", c.first().url)
    }

    @Test fun rankingOrder() {
        val spec = WebAppSpec("https://ex.com/", "https://ex.com/", icons = listOf(
            ManifestIcon("https://ex.com/small.png", 96), ManifestIcon("https://ex.com/any512.png", 512),
            ManifestIcon("https://ex.com/mask.png", 192, setOf("maskable")), ManifestIcon("https://ex.com/v.svg", ManifestIcon.ANY)))
        var fetchedPage = false
        val seq = PageIcons.candidates(spec, "https://ex.com/") { fetchedPage = true; listOf(PageIcon("https://cdn/x.png", 0), PageIcon("https://ex.com/l.png", 152)) }
        assertEquals(IconCandidate("https://ex.com/mask.png", true, "manifest-maskable"), seq.first())
        assertFalse("page HTML only fetched when needed", fetchedPage)
        val all = seq.toList()
        assertEquals(listOf("https://ex.com/mask.png", "https://ex.com/any512.png", "https://ex.com/l.png", "https://cdn/x.png",
            "https://ex.com/apple-touch-icon.png", "https://ex.com/apple-touch-icon-precomposed.png", "https://ex.com/favicon.ico"), all.map { it.url })
        assertEquals(listOf(true, false, false, false, false, false, false), all.map { it.fullBleed })
    }

    // Fake loader: url -> image size, absent = 404 / undecodable.
    private fun pick(sizes: Map<String, Int>, html: String, page: String = bbc) =
        PageIcons.firstUsable(PageIcons.candidates(null, page) { PageIcons.parse(html, page) }, { sizes[it] }) { it to it }

    @Test fun bbcGets152NotLetterTile() {
        val r = pick(mapOf("https://static.files.bbci.co.uk/weather/1.2/icons/apple-touch-icon-152.png" to 152,
            "https://www.bbc.co.uk/apple-touch-icon.png" to 57, "https://www.bbc.co.uk/favicon.ico" to 32), bbcHtml)!!
        assertEquals("link", r.first.source); assertEquals(152, r.second); assertFalse(r.first.fullBleed)
    }

    @Test fun notFoundOrBrokenFallsThrough() {
        // 152 link 404s (absent); loader throwing (redirect loop / bad decode) also skipped.
        val html = """<link rel=icon sizes=152x152 href=/gone.png><link rel=icon sizes=144x144 href=/boom.png>"""
        val r = PageIcons.firstUsable(PageIcons.candidates(null, "https://ex.com/") { PageIcons.parse(html, "https://ex.com/") },
            { u -> if (u.endsWith("boom.png")) error("decode") else mapOf("https://ex.com/favicon.ico" to 128)[u] }) { it to it }!!
        assertEquals("https://ex.com/favicon.ico", r.first.url)
    }

    @Test fun nothingUsableMeansLetterTileNotBlank() {
        assertNull(pick(emptyMap(), bbcHtml))
    }

    @Test fun tinyFaviconGivesLetterTile() {
        assertNull(pick(mapOf("https://www.bbc.co.uk/favicon.ico" to 32, "https://www.bbc.co.uk/apple-touch-icon.png" to 57), bbcHtml))
    }

    @Test fun redirectTargets() {
        assertEquals("https://ex.com/new.png", PageIcons.redirectTarget("http://ex.com/old.png", "https://ex.com/new.png"))
        assertEquals("https://ex.com/a/new.png", PageIcons.redirectTarget("https://ex.com/a/old.png", "new.png"))
        assertNull(PageIcons.redirectTarget("https://ex.com/", null))
        assertNull(PageIcons.redirectTarget("https://ex.com/", "javascript:alert(1)"))
    }

    @Test fun safeZoneInset() {
        val r = PageIcons.safeZoneRect(432, 152, 152)
        val side = 432 * 66f / 108f // 264
        assertArrayEquals(floatArrayOf((432 - side) / 2, (432 - side) / 2, (432 + side) / 2, (432 + side) / 2), r, 0.01f)
        assertEquals(21f / 108f, r[0] / 432, 0.001f) // (108-66)/2 = 21 dp per side, ≈19.4%
        val wide = PageIcons.safeZoneRect(432, 200, 100)
        assertEquals(side, wide[2] - wide[0], 0.01f); assertEquals(side / 2, wide[3] - wide[1], 0.01f)
    }

    @Test fun cornerColour() {
        val red = 0xFFCC0000.toInt()
        assertEquals(red, PageIcons.cornerColor(intArrayOf(red, red, red, red)))
        assertEquals(red, PageIcons.cornerColor(intArrayOf(red, 0xFF00FF00.toInt(), red, 0x00000000)))
        assertEquals(0xFFFFFFFF.toInt(), PageIcons.cornerColor(intArrayOf(0, 0x10FF0000, 0, 0)))
        assertEquals(0xFF123456.toInt(), PageIcons.cornerColor(intArrayOf(0xF0123456.toInt(), 0, 0, 0)))
    }
}
