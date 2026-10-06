package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.Accent
import com.jamhowman.beastbrowser.util.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 2.5: snackbar action text must reach WCAG AA (4.5:1) on surface3, app-wide (Designer's SPEC.md "snackAction"). */
class SnackContrastTest {
    private val darkSurface3 = 0xFF25252F.toInt()
    private val lightSurface3 = 0xFFE2E2E8.toInt()
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Test fun matchesSpecRatios() {
        assertEquals(4.16, Contrast.ratio(0xFFFF2D55.toInt(), darkSurface3), 0.01) // raw Blood Moon: fails
        assertEquals(4.60, Contrast.ratio(0xFFFF476A.toInt(), darkSurface3), 0.01) // SPEC's snackAction
        assertEquals(3.87, Contrast.ratio(Accent.RED.color, darkSurface3), 0.01)   // GX Red in 2.4.1: fails
    }

    @Test fun bloodMoonLandsNearSpecSnackAction() {
        val c = Contrast.readableOn(0xFFFF2D55.toInt(), darkSurface3)
        assertTrue(Contrast.ratio(c, darkSurface3) >= 4.5)
        val spec = 0xFFFF476A.toInt()
        for (shift in listOf(16, 8, 0)) {
            assertTrue("%08X vs FF476A".format(c), abs((c shr shift and 0xFF) - (spec shr shift and 0xFF)) <= 6)
        }
    }

    @Test fun everyAccentPassesOnBothThemes() {
        for (a in Accent.entries) for (bg in listOf(darkSurface3, lightSurface3)) {
            val c = Contrast.readableOn(a.color, bg)
            assertTrue("${a.key} on %08X: %.2f".format(bg, Contrast.ratio(c, bg)), Contrast.ratio(c, bg) >= 4.5)
        }
        // Light theme darkens instead of lightening.
        assertTrue(Contrast.luminance(Contrast.readableOn(Accent.RED.color, lightSurface3)) < Contrast.luminance(Accent.RED.color))
    }

    @Test fun passingColoursAreUntouched() {
        assertEquals(0xFF00E1FF.toInt(), Contrast.readableOn(0xFF00E1FF.toInt(), darkSurface3)) // cyan, 9.55:1
        assertEquals(0xFF2BFF88.toInt(), Contrast.readableOn(Accent.GREEN.color, darkSurface3))
    }

    @Test fun everySnackbarActionUsesTheReadableColour() {
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui")
        val calls = ui.walk().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""setActionTextColor\(([^\n]*)\)""").findAll(f.readText()).map { f.name to it.groupValues[1] }
        }.toList()
        assertTrue(calls.size >= 3)
        calls.forEach { (file, arg) -> assertTrue("$file: $arg", arg.startsWith("snackActionColor(")) }
    }
}
