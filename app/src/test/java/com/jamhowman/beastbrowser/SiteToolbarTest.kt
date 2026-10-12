package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.PrivateLook
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.SiteToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.10 Ulfur #5: Match toolbar to site colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteToolbarTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun on(meta: String?, manifest: String? = null, private: Boolean = false, realm: Realm = Realm.PLAY) =
        SiteToolbar.resolve(true, private, realm, meta, manifest)

    private fun assertReadable(c: SiteToolbar.Colors) {
        assertTrue("ink on pill", SiteToolbar.contrast(c.ink, c.pill) >= 4.5)
        assertTrue("ink on bar", SiteToolbar.contrast(c.ink, c.bar) >= 4.5)
        assertEquals("opaque ink", 0xFF, c.ink ushr 24)
        assertEquals("opaque bar", 0xFF, c.bar ushr 24)
        assertEquals("opaque pill", 0xFF, c.pill ushr 24)
    }
    private fun assertFarFromPrivate(c: SiteToolbar.Colors) {
        for (p in SiteToolbar.PROTECTED) {
            assertTrue("bar ΔE vs ${Integer.toHexString(p)}", SiteToolbar.deltaE(c.bar, p) >= 10)
            assertTrue("pill ΔE vs ${Integer.toHexString(p)}", SiteToolbar.deltaE(c.pill, p) >= 10)
        }
    }

    @Test fun siteCopyingThePrivateColourExactlyIsNudged() {
        for (copy in listOf("#1B1328", "#1b1328", "#F3EDFA", "rgb(27, 19, 40)", "#1B1328FF")) {
            val c = on(copy)!!
            assertTrue(copy, c.nudged)
            assertFarFromPrivate(c); assertReadable(c)
        }
        // also via the manifest
        assertFarFromPrivate(on(null, "#F3EDFA")!!)
    }

    @Test fun nearPrivateColoursAreNudgedAndFarOnesAreNot() {
        for (near in listOf("#1D152B", "#F1EBF8", "#20182E", "#EFE8F7")) {
            val c = on(near)!!
            assertTrue(near, c.nudged); assertFarFromPrivate(c)
        }
        val red = on("#C62828")!!
        assertFalse(red.nudged); assertEquals(0xFFC62828.toInt(), red.bar)
    }

    @Test fun sweepNeverLandsNearPrivateAndAlwaysReads() {
        var n = 0
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val c = SiteToolbar.colorsFor((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            assertFarFromPrivate(c); assertReadable(c); n++
        }
        assertEquals(16 * 16 * 16, n)
    }

    @Test fun contrastFlip() {
        val dark = on("#0D47A1")!!
        assertEquals(SiteToolbar.INK_LIGHT, dark.ink)
        val light = on("#FFEB3B")!!
        assertEquals(SiteToolbar.INK_DARK, light.ink)
        assertReadable(dark); assertReadable(light)
        // mid-tones where neither is obvious still reach 4.5:1 on the pill
        for (mid in listOf("#808080", "#777777", "#2196F3", "#FF5722", "#4CAF50")) assertReadable(on(mid)!!)
    }

    @Test fun toggleOffMeansNoChange() {
        for (v in listOf("#C62828", "#1B1328", "#ffffff")) {
            assertNull(SiteToolbar.resolve(false, false, Realm.PLAY, v, v))
            assertNull(SiteToolbar.resolve(false, false, Realm.WORK, v, null))
        }
        val prefs = File(main, "res/xml/preferences.xml").readText()
        assertTrue(Regex("""app:key="site_toolbar_color" app:defaultValue="false"""").containsMatchIn(prefs))
        assertTrue(File(main, "java/com/jamhowman/beastbrowser/data/Prefs.kt").readText()
            .contains("""sp.getBoolean("site_toolbar_color", false)"""))
    }

    @Test fun privateAndGhostTabsIgnoreTheSiteColour() {
        for (v in listOf("#C62828", "#00FF00", "#1B1328")) {
            assertNull(on(v, v, private = true))
            assertNull(on(v, v, realm = Realm.GHOST))
            assertNull(on(v, v, private = true, realm = Realm.GHOST))
        }
        assertNotNull(on("#C62828", realm = Realm.WORK))
    }

    @Test fun invalidOrTransparentThemeColour() {
        for (bad in listOf(null, "", "  ", "transparent", "#00000000", "#FFF0", "#12345680", "rgba(255,0,0,0.5)",
                "rgba(0,0,0,0)", "rgb(255 0 0 / 50%)", "currentcolor", "var(--brand)", "#12", "#GGGGGG", "#1234567",
                "rgb(300,0,0)", "rgb(1,2)", "linear-gradient(red, blue)", "hsl(0 100% 50%)", "javascript:alert(1)",
                "#" + "a".repeat(100))) {
            assertNull("$bad", SiteToolbar.parseCss(bad))
            assertNull("$bad", on(bad))
        }
        // an invalid meta falls back to a valid manifest colour
        assertEquals(0xFF336699.toInt(), on("transparent", "#336699")!!.bar)
    }

    @Test fun validCssForms() {
        assertEquals(0xFFFF0000.toInt(), SiteToolbar.parseCss("#f00"))
        assertEquals(0xFFFF0000.toInt(), SiteToolbar.parseCss("#F00F"))
        assertEquals(0xFF336699.toInt(), SiteToolbar.parseCss(" #336699 "))
        assertEquals(0xFF336699.toInt(), SiteToolbar.parseCss("#336699ff"))
        assertEquals(0xFF0A141E.toInt(), SiteToolbar.parseCss("rgb(10, 20, 30)"))
        assertEquals(0xFF0A141E.toInt(), SiteToolbar.parseCss("rgba(10,20,30,1)"))
        assertEquals(0xFF0A141E.toInt(), SiteToolbar.parseCss("rgb(10 20 30 / 100%)"))
        assertEquals(0xFF663399.toInt(), SiteToolbar.parseCss("RebeccaPurple"))
        assertEquals(0xFF336699.toInt(), on("#336699", "#C62828")!!.bar) // meta beats manifest
    }

    @Test fun protectedSetIsThePrivateToolbar() {
        assertTrue(PrivateLook.BARS_NIGHT in SiteToolbar.PROTECTED && PrivateLook.BARS_DAY in SiteToolbar.PROTECTED)
        assertEquals(0.0, SiteToolbar.deltaE(PrivateLook.BARS_DAY, PrivateLook.BARS_DAY), 1e-9)
    }

    @Test fun helperSendsTopFrameMetaThemeColor() {
        val ext = File(main, "assets/extensions/beast-helper")
        val js = File(ext, "content.js").readText()
        assertTrue(js.contains("type: \"themeColor\"") && js.contains("window === window.top") && js.contains("matchMedia(media)"))
        assertTrue(File(ext, "manifest.json").readText().contains("\"version\": \"1.3.0\""))
    }
}
