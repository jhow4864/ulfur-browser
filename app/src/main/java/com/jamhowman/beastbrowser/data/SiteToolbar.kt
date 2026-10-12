package com.jamhowman.beastbrowser.data

import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 2.10 Ulfur #5 "Match toolbar to site colour" (off by default, Settings › General).
 *
 * The site colour is the page's `<meta name="theme-color">` (sent by the helper extension, honouring `media`) or,
 * failing that, its web app manifest's `theme_color`. [resolve] turns it into toolbar colours:
 * - never for private tabs or Ghost (they keep [PrivateLook]),
 * - nudged in lightness until it is at least [MIN_DELTA_E] (CIE76 ΔE*ab) from every private / Ghost toolbar colour,
 *   so a site can't paint itself to look like a private tab,
 * - with ink (text, padlock, icons) picked white or near-black for ≥ 4.5:1 on both the bar and the address pill.
 * Pure (no Android views), unit-tested in SiteToolbarTest.
 */
object SiteToolbar {
    const val MIN_DELTA_E = 10.0
    const val MIN_CONTRAST = 4.5
    /** The address pill is the bar colour mixed this much toward the ink, so it stays distinct from the bar. */
    const val PILL_MIX = 0.14f

    const val INK_LIGHT = 0xFFFFFFFF.toInt()
    const val INK_DARK = 0xFF0E0E12.toInt()

    /**
     * Toolbar colours of private tabs and Ghost (Ghost is always private, so it uses the same PrivateLook), light and
     * dark. The private address pill (private_bg) is the same colour as the bars.
     */
    val PROTECTED: List<Int> = listOf(PrivateLook.BARS_NIGHT, PrivateLook.BARS_DAY)

    data class Colors(val bar: Int, val pill: Int, val ink: Int, val nudged: Boolean) {
        /** 2.10 follow-up: the bottom bar is painted exactly like the top bar, with the same ink on its icons. */
        val bottomBar: Int get() = bar
        val bottomInk: Int get() = ink
        /** Shield and other toolbar icons: the same ink (≥ [MIN_CONTRAST] on bar and pill). */
        val iconInk: Int get() = ink
    }

    /**
     * Null = leave the toolbar as it is (toggle off, private/Ghost, no or invalid colour). Otherwise the colours to
     * paint; they replace a theme pack's toolbar colour/image only while the toggle is on.
     */
    fun resolve(enabled: Boolean, privateTab: Boolean, realm: Realm, metaColor: String?, manifestColor: String?): Colors? {
        if (!enabled || privateTab || realm.alwaysPrivate || realm == Realm.GHOST) return null
        val site = parseCss(metaColor) ?: parseCss(manifestColor) ?: return null
        return colorsFor(site)
    }

    fun colorsFor(site: Int): Colors {
        val bar = nudge(site or 0xFF000000.toInt())
        var ink = inkFor(bar)
        var pill = nudge(ColorUtils.blendARGB(bar, ink, PILL_MIX))
        // The text and padlock sit on the pill: re-pick for it, and fall back to pure black/white if needed.
        if (contrast(ink, pill) < MIN_CONTRAST || contrast(ink, bar) < MIN_CONTRAST) {
            ink = inkFor(pill)
            if (contrast(ink, pill) < MIN_CONTRAST || contrast(ink, bar) < MIN_CONTRAST) {
                ink = if (ColorUtils.calculateLuminance(bar) > 0.18) 0xFF000000.toInt() else INK_LIGHT
                pill = bar
            }
        }
        return Colors(bar, pill, ink, bar != (site or 0xFF000000.toInt()))
    }

    /** White or near-black, whichever reads better; pure black if near-black doesn't reach 4.5:1. */
    fun inkFor(bg: Int): Int {
        val white = contrast(INK_LIGHT, bg)
        val dark = contrast(INK_DARK, bg)
        return when {
            white >= dark && white >= MIN_CONTRAST -> INK_LIGHT
            dark >= MIN_CONTRAST -> INK_DARK
            white >= dark -> INK_LIGHT
            else -> 0xFF000000.toInt()
        }
    }

    fun contrast(a: Int, b: Int) = ColorUtils.calculateContrast(a or 0xFF000000.toInt(), b or 0xFF000000.toInt())

    /** CIE76 ΔE*ab between two opaque colours. */
    fun deltaE(a: Int, b: Int): Double {
        val la = DoubleArray(3); val lb = DoubleArray(3)
        ColorUtils.colorToLAB(a or 0xFF000000.toInt(), la); ColorUtils.colorToLAB(b or 0xFF000000.toInt(), lb)
        return sqrt((la[0] - lb[0]).let { it * it } + (la[1] - lb[1]).let { it * it } + (la[2] - lb[2]).let { it * it })
    }

    fun minDistance(c: Int) = PROTECTED.minOf { deltaE(c, it) }

    /**
     * Moves [c] in L* (keeping a*, b*) away from the nearest protected colour until it's ≥ [MIN_DELTA_E] from all of
     * them; tries the direction away from the nearest first, then the other. Unchanged if already far enough.
     */
    fun nudge(c: Int): Int {
        if (minDistance(c) >= MIN_DELTA_E) return c
        val lab = DoubleArray(3); ColorUtils.colorToLAB(c or 0xFF000000.toInt(), lab)
        val nearest = PROTECTED.minBy { deltaE(c, it) }
        val nl = DoubleArray(3); ColorUtils.colorToLAB(nearest, nl)
        val first = if (lab[0] >= nl[0]) 1.0 else -1.0
        for (dir in listOf(first, -first)) {
            var l = lab[0]
            while (l in 0.0..100.0) {
                l += dir * 1.0
                val cand = ColorUtils.LABToColor(l.coerceIn(0.0, 100.0), lab[1], lab[2]) or 0xFF000000.toInt()
                if (minDistance(cand) >= MIN_DELTA_E + 0.5) return cand
            }
        }
        // Last resort (can't happen for the current protected set): neutral grey far from all of them.
        return 0xFF808080.toInt()
    }

    private val NAMED = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "orange" to 0xFFA500, "purple" to 0x800080, "gray" to 0x808080, "grey" to 0x808080,
        "navy" to 0x000080, "teal" to 0x008080, "maroon" to 0x800000, "silver" to 0xC0C0C0, "rebeccapurple" to 0x663399,
    )

    /**
     * CSS colour → opaque ARGB, or null for anything invalid or not fully opaque (`transparent`, alpha < 1,
     * `currentcolor`, var(), gradients…). Accepts #rgb, #rrggbb, #rgba/#rrggbbaa with full alpha, rgb()/rgba() with
     * numbers or percentages, and a few named colours.
     */
    fun parseCss(raw: String?): Int? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s.length > 64) return null
        NAMED[s]?.let { return it or 0xFF000000.toInt() }
        if (s.startsWith("#")) {
            val h = s.substring(1)
            if (!h.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val full = when (h.length) {
                3, 4 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            if (full.length == 8 && full.substring(6) != "ff") return null
            return full.substring(0, 6).toInt(16) or 0xFF000000.toInt()
        }
        val m = Regex("""^rgba?\((.*)\)$""").find(s) ?: return null
        val parts = m.groupValues[1].split(',', '/', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size !in 3..4) return null
        fun chan(p: String): Int? = if (p.endsWith("%")) p.dropLast(1).toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { (it * 2.55).toInt() }
            else p.toDoubleOrNull()?.takeIf { it in 0.0..255.0 }?.toInt()
        val (r, g, b) = parts.take(3).map { chan(it) ?: return null }
        if (parts.size == 4) {
            val a = parts[3].let { if (it.endsWith("%")) it.dropLast(1).toDoubleOrNull()?.div(100) else it.toDoubleOrNull() } ?: return null
            if (abs(a - 1.0) > 1e-9) return null
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
