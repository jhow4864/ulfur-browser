package com.jamhowman.beastbrowser.data

import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min

/**
 * 2.11 "Create theme from photo": pure colour logic (no Android graphics), so every rule is unit-tested.
 * Palette extraction from the photo's pixel sample, schema-2 colour suggestions for dark and light, auto-fix to
 * 4.5:1 (with a small margin so a re-encode on import can't tip it under), the minimum readable scrim, and the pack.
 */
object PhotoTheme {
    const val MIN = ThemeContrast.MIN_TEXT
    /** Suggestions aim a little higher than 4.5 so JPEG re-encoding on re-import never drops them below it. */
    const val TARGET = 4.8
    const val HOME_TEXT_TARGET = 7.0
    const val SCRIM_MAX = 0.9f
    const val AUTHOR = "Made on this phone"

    data class Swatch(val color: Int, val population: Int, val readable: Boolean)

    /** Editable state of the editor: per-mode colour maps, the cropped home image and the scrim the user chose. */
    data class Draft(
        val name: String,
        val accent: Int,
        val dark: Map<String, Int>,
        val light: Map<String, Int>,
        val image: ThemeImage? = null,
        val scrim: Float = 0f,
    )

    fun ratio(a: Int, b: Int) = ThemeContrast.ratio(a, b)
    private fun opaque(c: Int) = c or 0xFF000000.toInt()

    // ------------------------------------------------------------------ palette

    /**
     * Up to [max] distinct colours of [pixels] (ARGB), most common first, favouring colourful ones: 4-bit-per-channel
     * buckets, averaged, then near-duplicates (in hue/lightness) merged. Transparent pixels are ignored.
     */
    fun extract(pixels: IntArray, max: Int = 6): List<Pair<Int, Int>> {
        if (pixels.isEmpty()) return emptyList()
        val count = HashMap<Int, IntArray>() // key -> [n, r, g, b]
        for (p in pixels) {
            if ((p ushr 24) < 0x80) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val a = count.getOrPut(k) { IntArray(4) }
            a[0]++; a[1] += r; a[2] += g; a[3] += b
        }
        val buckets = count.values.map { a -> opaque((a[1] / a[0] shl 16) or (a[2] / a[0] shl 8) or (a[3] / a[0])) to a[0] }
        // Weight: population, boosted by saturation (a photo's sky/skin should beat a sea of grey).
        val ranked = buckets.sortedByDescending { (c, n) -> n * (0.35 + hsl(c)[1].toDouble()) }
        val out = mutableListOf<Pair<Int, Int>>()
        for ((c, n) in ranked) {
            if (out.size >= max) break
            if (out.none { distinct(it.first, c) < 0.12 }) out += c to n
        }
        return out
    }

    private fun distinct(a: Int, b: Int): Double {
        val x = hsl(a); val y = hsl(b)
        var dh = abs(x[0] - y[0]); if (dh > 180) dh = 360 - dh
        val sat = min(x[1], y[1])
        return (dh / 180.0) * sat + abs(x[2] - y[2]) + abs(x[1] - y[1]) * 0.5
    }

    fun hsl(c: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(opaque(c), it) }
    fun tone(c: Int, lightness: Float, maxSat: Float): Int {
        val h = hsl(c)
        return ColorUtils.HSLToColor(floatArrayOf(h[0], min(h[1], maxSat), lightness.coerceIn(0f, 1f)))
    }

    // ------------------------------------------------------------------ contrast fixes

    /** [fg] moved in lightness (away from [bgs]) until it reaches [target] against all of them; black/white at worst. */
    fun fix(fg: Int, bgs: List<Int>, target: Double = TARGET): Int {
        fun worst(c: Int) = bgs.minOf { ratio(c, it) }
        if (worst(fg) >= target) return opaque(fg)
        val darkBgs = bgs.map { ColorUtils.calculateLuminance(opaque(it)) }.average() < 0.18
        val h = hsl(fg)
        var l = h[2]
        repeat(100) {
            l = if (darkBgs) (l + 0.01f).coerceAtMost(1f) else (l - 0.01f).coerceAtLeast(0f)
            val c = ColorUtils.HSLToColor(floatArrayOf(h[0], h[1], l))
            if (worst(c) >= target) return c
        }
        val bw = if (darkBgs) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val other = if (darkBgs) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return if (worst(bw) >= worst(other)) bw else other
    }

    /** Black or white, whichever reads better on [bg]. */
    fun onColor(bg: Int): Int =
        if (ratio(0xFFFFFFFF.toInt(), bg) >= ratio(0xFF000000.toInt(), bg)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    /** Accent usable as a filled button with black/white text at [target] (moved in lightness if needed). */
    fun fixAccent(accent: Int, target: Double = TARGET): Int {
        if (ratio(onColor(accent), accent) >= target) return opaque(accent)
        val h = hsl(accent)
        val up = h[2] < 0.5f
        var l = h[2]
        repeat(100) {
            l = if (up) (l - 0.01f).coerceAtLeast(0f) else (l + 0.01f).coerceAtMost(1f) // toward the nearer extreme of white/black text
            val c = ColorUtils.HSLToColor(floatArrayOf(h[0], h[1], l))
            if (ratio(onColor(c), c) >= target) return c
        }
        return opaque(accent)
    }

    // ------------------------------------------------------------------ suggestions

    /** The background the suggestion would use for [night] (what swatches are judged against). */
    fun backgroundFor(dominant: Int, night: Boolean) = if (night) tone(dominant, 0.08f, 0.35f) else tone(dominant, 0.96f, 0.30f)

    /** 4–6 accent swatches from [pixels]; a swatch is greyed out when it can't be read as text on the background. */
    fun swatches(pixels: IntArray, night: Boolean, max: Int = 6): List<Swatch> {
        val ex = extract(pixels, max)
        val bg = backgroundFor(ex.firstOrNull()?.first ?: 0xFF808080.toInt(), night)
        return ex.map { (c, n) -> Swatch(c, n, ratio(c, bg) >= MIN) }
    }

    /** Suggested schema-2 colours for both modes from the photo's [dominant] colour and the chosen [accent]. */
    fun suggest(name: String, pixels: IntArray, accent: Int? = null, image: ThemeImage? = null): Draft {
        val ex = extract(pixels)
        val dominant = ex.firstOrNull()?.first ?: 0xFF607080.toInt()
        val acc = accent ?: ex.firstOrNull { hsl(it.first)[1] > 0.25f }?.first ?: ex.getOrNull(1)?.first ?: dominant
        val d = enforce(seed(dominant, acc, true), true)
        val l = enforce(seed(dominant, acc, false), false)
        val draft = Draft(name, acc, d, l, image)
        return draft.copy(scrim = minScrim(draft))
    }

    private fun seed(dom: Int, acc: Int, night: Boolean): Map<String, Int> = if (night) mapOf(
        "background" to tone(dom, 0.08f, 0.35f), "surface" to tone(dom, 0.12f, 0.30f), "surfaceVariant" to tone(dom, 0.16f, 0.25f),
        "toolbar" to tone(dom, 0.10f, 0.35f), "text" to tone(dom, 0.95f, 0.15f), "textSecondary" to tone(dom, 0.74f, 0.15f),
        "onToolbar" to tone(dom, 0.95f, 0.15f), "accent" to acc,
    ) else mapOf(
        "background" to tone(dom, 0.96f, 0.30f), "surface" to tone(dom, 0.99f, 0.25f), "surfaceVariant" to tone(dom, 0.92f, 0.25f),
        "toolbar" to tone(dom, 0.93f, 0.30f), "text" to tone(dom, 0.09f, 0.20f), "textSecondary" to tone(dom, 0.33f, 0.15f),
        "onToolbar" to tone(dom, 0.09f, 0.20f), "accent" to acc,
    )

    /** Every text colour fixed against what it sits on; derived keys (onAccent, accentText, gradient, divider) recomputed. */
    fun enforce(m: Map<String, Int>, night: Boolean): Map<String, Int> {
        val out = m.toMutableMap()
        val bg = out.getValue("background"); val s = out.getValue("surface"); val sv = out.getValue("surfaceVariant")
        val tb = out.getValue("toolbar")
        // Ulfur's own XML text (TEXT_DARK/TEXT_LIGHT) is also checked against the pack's background and surface.
        val xml = if (night) ThemeContrast.TEXT_DARK else ThemeContrast.TEXT_LIGHT
        if (min(ratio(xml, bg), ratio(xml, s)) < TARGET) {
            out["background"] = fix(bg, listOf(xml), TARGET); out["surface"] = fix(s, listOf(xml), TARGET)
        }
        val bgs = listOf(out.getValue("background"), out.getValue("surface"), sv)
        out["text"] = fix(out.getValue("text"), bgs, HOME_TEXT_TARGET)
        out["textSecondary"] = fix(out.getValue("textSecondary"), bgs.take(2), TARGET)
        out["onToolbar"] = fix(out.getValue("onToolbar"), listOf(tb), TARGET)
        val acc = fixAccent(out.getValue("accent"))
        out["accent"] = acc; out["accentStart"] = acc; out["accentEnd"] = acc
        out["onAccent"] = onColor(acc)
        out["accentText"] = fix(acc, bgs.take(2), TARGET)
        out["divider"] = ColorUtils.blendARGB(out.getValue("background"), out.getValue("text"), 0.12f) // opaque: pack hex has no alpha
        return out
    }

    /** Applies one tweak; returns the new draft and whether anything was auto-fixed for readability. */
    fun tweak(d: Draft, night: Boolean, key: String, color: Int): Pair<Draft, Boolean> {
        val m = (if (night) d.dark else d.light) + (key to opaque(color))
        val fixed = enforce(m, night)
        val changed = fixed[key] != opaque(color) || TEXT_KEYS.any { k -> k != key && m[k] != null && fixed[k] != m[k] }
        val nd = if (night) d.copy(dark = fixed) else d.copy(light = fixed)
        return nd.copy(scrim = maxOf(nd.scrim, minScrim(nd))) to changed
    }

    val TEXT_KEYS = listOf("text", "textSecondary", "onToolbar", "onAccent", "accentText")
    val EDITABLE = listOf("background", "surface", "toolbar", "text", "onToolbar", "accent")

    // ------------------------------------------------------------------ image + scrim

    fun spec(scrim: Float) = ImageSpec(ImageSlot.HOME, "images/home.jpg", 0.5f, 0.5f, scrim, true, SCRIM_MAX)

    /** Smallest scrim (0.01 steps) at which home text reads at [TARGET] over the image in both modes. */
    fun minScrim(d: Draft): Float {
        val img = d.image ?: return 0f
        var need = 0f
        for (m in listOf(d.dark, d.light)) {
            val text = m.getValue("text"); val bg = m.getValue("background")
            var s = 0f
            while (s < SCRIM_MAX) {
                val e = ImageContrast.extremes(img, ImageSlot.HOME, spec(s), bg, s)
                if (e == null || ImageContrast.worstRatio(text, e) >= TARGET) break
                s = (s + 0.01f).coerceAtMost(SCRIM_MAX)
            }
            need = maxOf(need, s)
        }
        return need
    }

    /** Slider clamp: the user can't go below the readable minimum. */
    fun clampScrim(d: Draft, wanted: Float): Float = wanted.coerceIn(minScrim(d), SCRIM_MAX)

    fun toPack(d: Draft): ThemePack {
        val name = d.name.trim().ifEmpty { "My photo theme" }.take(ThemePackParser.MAX_TEXT)
        val img = d.image
        val scrim = clampScrim(d, d.scrim)
        return ThemePack(name, AUTHOR, "1", ThemePreset.DEFAULT, d.dark, d.light, 2,
            if (img == null) emptyMap() else mapOf(ImageSlot.HOME to spec(scrim).copy(file = "images/home.${img.ext}")),
            if (img == null) emptyMap() else mapOf(ImageSlot.HOME to img))
    }

    /** The .ulfurtheme zip: theme.json plus Ulfur's re-encoded image. */
    fun export(p: ThemePack): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(ThemePackParser.JSON_NAME)); z.write(p.toJson().toString(2).toByteArray()); z.closeEntry()
            p.images.forEach { (slot, img) ->
                z.putNextEntry(java.util.zip.ZipEntry(p.storedName(slot))); z.write(img.bytes); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
