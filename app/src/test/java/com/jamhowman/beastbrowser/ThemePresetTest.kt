package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.ThemeColor
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.util.Contrast
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2.8 theme presets (roadmap item 18): parsing of stored keys and hex tokens, and the tokens themselves against the
 * Designer's themes.json (copied verbatim from branding/ulfur/mockups into src/test/resources/ulfur/).
 * Robolectric only for a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemePresetTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private val spec = JSONObject(javaClass.getResource("/ulfur/themes.json")!!.readText())
    private val specThemes = spec.getJSONArray("themes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val specAll = specThemes + spec.getJSONObject("ghost")

    private fun c(hex: String) = ThemeColor.parse(hex)

    // ---------------------------------------------------------------- keys

    @Test fun eightPresetsInSpecOrderAndBloodMoonIsTheDefault() {
        assertEquals(specThemes.map { it.getString("key") }, ThemePreset.presets.map { it.key })
        assertEquals(8, ThemePreset.presets.size)
        assertSame(ThemePreset.BLOOD_MOON, ThemePreset.DEFAULT)
        assertFalse(ThemePreset.GHOST in ThemePreset.presets)
    }

    @Test fun legacyAccentKeysLandOnTheirSuccessors() {
        // SPEC "How this maps onto the current code": GX Red → Blood Moon, Cyber Cyan → Frost, Toxic Green → Toxic,
        // Lava Orange → Ember, Ultraviolet → Void.
        mapOf("red" to ThemePreset.BLOOD_MOON, "cyan" to ThemePreset.FROST, "green" to ThemePreset.TOXIC,
            "orange" to ThemePreset.EMBER, "purple" to ThemePreset.VOID).forEach { (legacy, preset) ->
            assertSame(legacy, preset, ThemePreset.parse(legacy))
            assertEquals("stored under the 2.3–2.5 key so downgrades and backups keep working", legacy, preset.storageKey)
        }
        specAll.forEach { t ->
            val preset = ThemePreset.entries.single { it.key == t.getString("key") }
            assertEquals(t.getString("key"), t.optString("legacyKey").takeUnless { t.isNull("legacyKey") }, preset.legacyKey)
        }
    }

    @Test fun newKeysAndSpecKeysParse() {
        ThemePreset.presets.forEach { assertSame(it, ThemePreset.parse(it.key)) }
        assertEquals("gold", ThemePreset.GOLD.storageKey)
        assertEquals("sakura", ThemePreset.SAKURA.storageKey)
        assertEquals("ash", ThemePreset.ASH.storageKey)
    }

    @Test fun unknownBlankAndGhostKeysDontParse() {
        listOf(null, "", "  ", "neon", "RED", "Blood Moon", "ghost").forEach { assertNull("'$it'", ThemePreset.parse(it)) }
        assertSame("from() falls back like Accent.from did", ThemePreset.DEFAULT, ThemePreset.from("neon"))
        assertSame(ThemePreset.DEFAULT, ThemePreset.from(null))
        assertSame(ThemePreset.SAKURA, ThemePreset.from("sakura"))
    }

    // ---------------------------------------------------------------- hex parsing

    @Test fun hexColoursParseToArgb() {
        assertEquals(0xFFFF2D55.toInt(), c("#FF2D55"))
        assertEquals(0xFF00E1FF.toInt(), c("00e1ff"))
        assertEquals("CSS order #RRGGBBAA (themes.json glow)", 0x55FF2D55, c("#FF2D5555"))
        assertEquals(0xFF0E0E12.toInt(), c(" #0E0E12 "))
        assertEquals("#FF2D55", ThemeColor.hex(0x55FF2D55))
        assertEquals("FF2D55 · E040FB", com.jamhowman.beastbrowser.ui.AppearanceFragment.hexPair(ThemePreset.BLOOD_MOON))
    }

    @Test fun malformedHexIsRejected() {
        listOf("", "#", "#FFF", "#FF2D5", "#FF2D555", "#GG2D55", "FF2D55FF00").forEach {
            try { c(it); fail("'$it' parsed") } catch (_: IllegalArgumentException) {}
        }
    }

    // ---------------------------------------------------------------- tokens

    @Test fun tokensMatchThemesJson() {
        specAll.forEach { t ->
            val p = ThemePreset.entries.single { it.key == t.getString("key") }
            val k = p.key
            assertEquals(k, t.getString("name"), p.label)
            assertEquals(k, c(t.getString("accentStart")), p.accentStart)
            assertEquals(k, c(t.getString("accentEnd")), p.accentEnd)
            assertEquals(k, c(t.getString("accent")), p.color)
            assertEquals(k, c(t.getString("onAccent")), p.onColor)
            assertEquals(k, c(t.getString("accentTextDark")), p.accentText(night = true))
            assertEquals(k, c(t.getString("accentTextLight")), p.accentText(night = false))
            assertEquals(k, c(t.getString("surfaceTintDark")), p.surfaceTint(night = true))
            assertEquals(k, c(t.getString("surfaceTintLight")), p.surfaceTint(night = false))
            assertEquals(k, c(t.getString("glow")), p.glow)
            assertEquals(k, t.getString("textFill") == "gradient", p.gradientCarriesText)
        }
    }

    @Test fun contrastHoldsForEveryPreset() {
        val white = 0xFFFFFFFF.toInt()
        val surface2Dark = 0xFF1C1C24.toInt()
        ThemePreset.entries.forEach { p ->
            val fills = if (p.gradientCarriesText) listOf(p.accentStart, mid(p.accentStart, p.accentEnd), p.accentEnd) else listOf(p.color)
            fills.forEach { f -> assertTrue("${p.key}: ink on fill", Contrast.ratio(p.onColor, f) >= Contrast.AA_TEXT) }
            assertTrue("${p.key}: accentText on white", Contrast.ratio(p.accentText(false), white) >= Contrast.AA_TEXT)
            assertTrue("${p.key}: accentText on surface2", Contrast.ratio(p.accentText(true), surface2Dark) >= Contrast.AA_TEXT)
            assertTrue("${p.key}: accentText on its tint", Contrast.ratio(p.accentText(true), p.surfaceTint(true)) >= Contrast.AA_TEXT)
        }
    }

    private fun mid(a: Int, b: Int): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((b shr s) and 0xFF)) / 2
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------------------------------------------------------------- overlays in themes.xml

    private fun overlay(dir: String, p: ThemePreset): Map<String, String> {
        val name = "ThemeOverlay.Ulfur.Preset." + p.label.replace(" ", "")
        val xml = File(main, "res/$dir/themes.xml").readText()
        assertTrue("$dir has $name", xml.contains("<style name=\"$name\">"))
        val body = xml.substringAfter("<style name=\"$name\">").substringBefore("</style>")
        return Regex("<item name=\"([^\"]+)\">([^<]+)</item>").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun overlaysCarryTheTokensForTheirMode() {
        for ((dir, night) in listOf("values" to false, "values-night" to true)) {
            val attrSets = ThemePreset.entries.map { p ->
                val o = overlay(dir, p)
                fun hex(v: Int) = ThemeColor.hex(v)
                val k = "$dir ${p.key}"
                assertEquals(k, hex(p.color), o["colorPrimary"]); assertEquals(k, hex(p.color), o["ulfurAccent"])
                assertEquals(k, hex(p.onColor), o["colorOnPrimary"]); assertEquals(k, hex(p.onColor), o["ulfurOnAccent"])
                assertEquals(k, hex(p.accentStart), o["ulfurAccentStart"]); assertEquals(k, hex(p.accentEnd), o["ulfurAccentEnd"])
                assertEquals(k, hex(p.accentText(night)), o["ulfurAccentText"])
                assertEquals(k, hex(p.surfaceTint(night)), o["ulfurSurfaceTint"]); assertEquals(k, hex(p.surfaceTint(night)), o["colorPrimaryContainer"])
                assertEquals(k, hex(p.eye), o["ulfurEye"])
                o.keys
            }
            assertEquals("$dir: every overlay sets the same attributes, so applying one fully replaces another",
                1, attrSets.toSet().size)
        }
    }

    @Test fun noLightNavigationBarIconsOnApi26() {
        val values = File(main, "res/values/themes.xml").readText()
        val night = File(main, "res/values-night/themes.xml").readText()
        val light = "name=\"android:windowLightNavigationBar\""
        assertFalse("windowLightNavigationBar is API 27+ (NewApi)", values.contains(light))
        assertFalse(night.contains(light))
        assertTrue(values.substringAfter("<style name=\"Theme.Beast\" parent=\"Theme.Beast.Base\">").substringBefore("</style>")
            .contains("<item name=\"android:navigationBarColor\">@android:color/black</item>"))
        ThemePreset.entries.forEach { p ->
            assertFalse("overlays never touch the bars", overlay("values", p).keys.any { it.contains("Bar") })
        }
        val v27 = File(main, "res/values-v27/themes.xml").readText()
        assertTrue(v27.contains("<item name=\"android:windowLightNavigationBar\">true</item>"))
    }
}
