package com.jamhowman.beastbrowser

import android.graphics.Bitmap
import com.jamhowman.beastbrowser.ui.QuickPickIconLoader
import com.jamhowman.beastbrowser.webapp.PageIcon
import com.jamhowman.beastbrowser.webapp.QuickPickIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** 2.12 home quick-pick icons + Apk Tester's icon gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real BitmapFactory decoding
class QuickPickIconsTest {
    private val now = 10_000_000_000L
    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    @Test fun httpsOnly() {
        assertEquals("https://github.com", QuickPickIcons.httpsOrigin("https://github.com/x"))
        assertNull(QuickPickIcons.httpsOrigin("http://example.com"))
        assertNull(QuickPickIcons.httpsOrigin("ftp://x"))
        assertFalse(QuickPickIcons.shouldFetch("http://example.com", false, false, 0, 0, now))
        assertNull(QuickPickIcons.download("http://127.0.0.1:9/favicon.ico", "image/*")) // refused before connecting
        val c = QuickPickIcons.candidates("https://a.com") {
            listOf(PageIcon("http://a.com/i.png", 180), PageIcon("https://cdn.a.com/i.svg", 0), PageIcon("https://cdn.a.com/big.png", 192))
        }
        assertEquals("https://cdn.a.com/big.png", c.first())
        assertTrue(c.all { it.startsWith("https://") && !it.endsWith(".svg") })
        assertTrue(c.contains("https://a.com/favicon.ico"))
    }

    @Test fun neverFetchInPrivateOrGhostOrForPrivateTiles() {
        assertFalse(QuickPickIcons.shouldFetch("https://a.com", privateState = true, tileFromPrivate = false, cachedAt = 0, failedAt = 0, now = now))
        assertFalse(QuickPickIcons.shouldFetch("https://a.com", privateState = false, tileFromPrivate = true, cachedAt = 0, failedAt = 0, now = now))
        assertTrue(QuickPickIcons.shouldFetch("https://a.com", false, false, 0, 0, now))
        val ma = File(main, "java/com/jamhowman/beastbrowser/ui/MainActivity.kt").readText()
        assertTrue(ma.contains("allowIconFetch = { current?.isPrivate != true && realm != Realm.GHOST }"))
        assertTrue(ma.contains("val fromPrivate = current?.isPrivate == true || realm == Realm.GHOST"))
    }

    @Test fun weeklyRefreshAndFailureBackoff() {
        val day = 24L * 3600 * 1000
        assertFalse(QuickPickIcons.shouldFetch("https://a.com", false, false, now - 6 * day, 0, now))
        assertTrue(QuickPickIcons.shouldFetch("https://a.com", false, false, now - 8 * day, 0, now))
        assertFalse(QuickPickIcons.shouldFetch("https://a.com", false, false, 0, now - 1000, now))
        assertTrue(QuickPickIcons.shouldFetch("https://a.com", false, false, 0, now - 2 * day, now))
    }

    @Test fun cacheNameIsStableAndOpaque() {
        val n = QuickPickIcons.cacheName("https://secret.example")
        assertEquals(n, QuickPickIcons.cacheName("https://secret.example"))
        assertFalse(n.contains("secret")); assertEquals(32, n.length)
    }

    @Test fun capAndNoCookies() {
        assertEquals(1024 * 1024, QuickPickIcons.MAX_BYTES)
        val h = QuickPickIcons.requestHeaders("image/*")
        assertFalse(h.keys.any { it.equals("Cookie", true) })
        val src = File(main, "java/com/jamhowman/beastbrowser/webapp/QuickPickIcons.kt").readText()
        assertTrue(src.contains("c.useCaches = false"))
        assertFalse(src.contains("CookieManager") || src.contains("CookieHandler.setDefault"))
        assertNull(java.net.CookieHandler.getDefault())
    }

    @Test fun svgBrokenAndTinyImagesFallBackToLetterWithoutCrash() {
        assertNull(QuickPickIconLoader.decode("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
        assertNull(QuickPickIconLoader.decode(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)))
        assertNull(QuickPickIconLoader.decode(ByteArray(0)))
        assertNull(QuickPickIconLoader.decode(png(16)))
        assertFalse(QuickPickIcons.usable(31, 64)); assertTrue(QuickPickIcons.usable(32, 32))
    }

    @Test fun goodIconDecodes() { assertNotNull(QuickPickIconLoader.decode(png(64))) }

    @Test fun outlinePlateOnlyOnDarkForTransparentOrDarkIcons() {
        assertTrue(QuickPickIcons.needsOutline(true, 0.8f, 0.9f))
        assertTrue(QuickPickIcons.needsOutline(true, 0f, 0.05f))
        assertFalse(QuickPickIcons.needsOutline(true, 0f, 0.7f))
        assertFalse(QuickPickIcons.needsOutline(false, 1f, 0f))
    }

    @Test fun tileRemembersPrivateOrigin() {
        val s = File(main, "java/com/jamhowman/beastbrowser/ui/SpeedDial.kt").readText()
        assertTrue(s.contains("it.optBoolean(\"p\", false)"))
        assertTrue(s.contains("QuickPickIconLoader.load(ctx, t.url, allowIconFetch(), t.fromPrivate)"))
    }

    private fun png(edge: Int): ByteArray {
        val b = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
