package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.Accent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.pow

/** 2.4.1: text on accent fills must meet WCAG 2.1 AA (4.5:1) — GX Red now uses dark ink (Designer's SPEC.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccentContrastTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    private fun lum(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun gxRedUsesDarkInkThatPassesAA() {
        assertEquals(0xFF0E0E12.toInt(), Accent.RED.onColor)
        assertTrue(contrast(Accent.RED.color, Accent.RED.onColor) >= 4.5)
        assertTrue("white on GX Red is the failure being fixed", contrast(Accent.RED.color, 0xFFFFFFFF.toInt()) < 4.5)
    }

    @Test fun darkInkAccentsPassAA() {
        // Ultraviolet (white, 4.48:1) is left as is until the 2.8 Void preset (SPEC.md doesn't flag it as failing).
        listOf(Accent.RED, Accent.CYAN, Accent.GREEN, Accent.ORANGE).forEach {
            assertTrue("${it.key}: ${contrast(it.color, it.onColor)}", contrast(it.color, it.onColor) >= 4.5)
        }
    }

    @Test fun redThemeOverlaysMatchOnColor() {
        // 2.8: GX Red's overlay became Blood Moon's (same `red` key); its ink is still #0E0E12.
        for (dir in listOf("values", "values-night")) {
            val xml = File(main, "res/$dir/themes.xml").readText()
            val red = xml.substringAfter("<style name=\"ThemeOverlay.Ulfur.Preset.BloodMoon\">").substringBefore("</style>")
            assertTrue("$dir red overlay", red.contains("<item name=\"colorOnPrimary\">#0E0E12</item>"))
            assertTrue("$dir red overlay", red.contains("<item name=\"colorOnSecondary\">#0E0E12</item>"))
            assertFalse(red.contains("#FFFFFF"))
        }
        val base = File(main, "res/values/themes.xml").readText()
            .substringAfter("<style name=\"Theme.Beast.Base\"").substringBefore("</style>")
        assertTrue("base theme is GX Red: dark ink too", base.contains("<item name=\"colorOnPrimary\">#0E0E12</item>"))
    }
}
