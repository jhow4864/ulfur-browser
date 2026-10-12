package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.ShieldLevel
import com.jamhowman.beastbrowser.data.SiteToolbar
import com.jamhowman.beastbrowser.data.ThemeColorThrottle
import com.jamhowman.beastbrowser.ui.ShieldBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.10 toolbar follow-ups: (a) shield ink + shape, (b) bottom bar tint, (c) 250 ms theme-color throttle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteToolbarFollowUpTest {
    private val samples = listOf(0xFFFFFF, 0x000000, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x808080, 0x777777,
        0x1A73E8, 0xE91E63, 0x3B2A5C, 0x2E1A47, 0xF5F5F5, 0x0E0E12, 0x6A1B9A, 0xFFA500, 0x008080)

    // (a)
    @Test fun shieldUsesToolbarInkAtFourPointFive() {
        for (rgb in samples + (0..0xFFFFFF step 0x0F0F0F).toList()) {
            val c = SiteToolbar.colorsFor(rgb or 0xFF000000.toInt())
            assertEquals(c.ink, c.iconInk)
            assertTrue("shield on bar #${Integer.toHexString(rgb)}", SiteToolbar.contrast(c.iconInk, c.bar) >= 4.5)
        }
        for (lvl in ShieldLevel.values()) for (on in listOf(true, false)) for (paused in listOf(true, false))
            assertEquals(ShieldBadge.Tint.INK, ShieldBadge.siteTintStyle(lvl, on, paused).iconTint)
    }

    @Test fun shieldStateIsShownByShape() {
        for (lvl in ShieldLevel.values()) {
            assertEquals(R.drawable.ic_shield, ShieldBadge.siteTintStyle(lvl, blockingOn = true, pausedForSite = false).icon)
            assertEquals(R.drawable.ic_shield_outline, ShieldBadge.siteTintStyle(lvl, blockingOn = true, pausedForSite = true).icon)
            assertEquals(R.drawable.ic_shield_off, ShieldBadge.siteTintStyle(lvl, blockingOn = false, pausedForSite = false).icon)
            assertEquals(R.drawable.ic_shield_off, ShieldBadge.siteTintStyle(lvl, blockingOn = false, pausedForSite = true).icon)
        }
    }

    // (b)
    @Test fun bottomBarMatchesTopBar() {
        for (rgb in samples) {
            val c = SiteToolbar.colorsFor(rgb or 0xFF000000.toInt())
            assertEquals(c.bar, c.bottomBar)
            assertEquals(c.ink, c.bottomInk)
            assertTrue(SiteToolbar.contrast(c.bottomInk, c.bottomBar) >= 4.5)
        }
    }

    // (c)
    private class Fake {
        var t = 0L
        val queue = ArrayList<Pair<Long, () -> Unit>>()
        val applied = ArrayList<Pair<Long, String?>>()
        val th = ThemeColorThrottle(250, { t }, { d, b -> queue += (t + d) to b }, { applied += t to it })
        fun advance(to: Long) {
            while (true) {
                val next = queue.filter { it.first <= to }.minByOrNull { it.first } ?: break
                queue.remove(next); t = next.first; next.second()
            }
            t = to
        }
    }

    @Test fun firstChangeAppliesImmediately() {
        val f = Fake(); f.th.submit("#111111")
        assertEquals(listOf(0L to "#111111"), f.applied)
    }

    @Test fun burstIsCoalescedToTrailingValue() {
        val f = Fake()
        f.th.submit("a")
        for (i in 1..20) { f.advance(i * 10L); f.th.submit("v$i") }
        assertEquals(1, f.applied.size)
        f.advance(1000)
        assertEquals(listOf(0L to "a", 250L to "v20"), f.applied)
    }

    @Test fun neverFasterThan250ms() {
        val f = Fake()
        for (i in 0 until 300) { f.advance(i * 7L); f.th.submit("c$i") }
        f.advance(5000)
        f.applied.zipWithNext().forEach { (a, b) -> assertTrue("${a.first}->${b.first}", b.first - a.first >= 250) }
        assertEquals("c299", f.applied.last().second)
    }

    @Test fun nullClearIsAlsoTrailing() {
        val f = Fake()
        f.th.submit("#abcdef"); f.advance(50); f.th.submit(null)
        f.advance(400)
        assertEquals(listOf(0L to "#abcdef", 250L to null), f.applied)
        f.advance(1000); f.th.submit("#000000")
        assertEquals(1000L to "#000000", f.applied.last())
    }
}
