package com.jamhowman.beastbrowser.data

import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * 2.10 theme packs v2: background images (docs/theme-packs.md "Images").
 *
 * Every slot is drawn "cover" (fills the view, cropped around the pack's focal point) under a scrim of the slot's
 * backing colour. [textZone] is where Ulfur draws text over that view, as fractions of the view; the readability check
 * maps it through the cover crop of every [views] size, so whatever part of the image can end up under text is tested.
 */
enum class ImageSlot(
    val key: String,
    /** Representative view sizes in px (phones portrait/landscape, a tablet). */
    val views: List<Pair<Int, Int>>,
    /** Text areas in view fractions: left, top, right, bottom each (Designer's provisional slots, 2026-10-12). */
    val textZones: List<FloatArray>,
    /** Long side after import (downsampled to this, never upscaled). */
    val maxSide: Int,
    /** What Designer should deliver (px). */
    val recommendedW: Int,
    val recommendedH: Int,
    /** True: scrim = the pack's background colour; false: its toolbar colour. */
    val onBackground: Boolean,
) {
    /** 20:9 portrait; text in the top 12% and the 22–70% band; the bottom 30% is safe for figures. */
    HOME("home", listOf(1080 to 2400, 1080 to 1920, 1440 to 3200, 1600 to 2560, 2400 to 1080),
        listOf(floatArrayOf(0f, 0f, 1f, 0.12f), floatArrayOf(0f, 0.22f, 1f, 0.70f)), 2400, 1080, 2400, true),
    /** Texture only: text across the full strip. */
    TOOLBAR("toolbar", listOf(1440 to 256, 1080 to 210, 2400 to 210),
        listOf(floatArrayOf(0f, 0f, 1f, 1f)), 1440, 1440, 256, false),
    /** Not in Designer's provisional list: tab-switcher background, same art rules as home ("Tabs" title on top). */
    SWITCHER("switcher", listOf(1080 to 2400, 1080 to 1920, 1600 to 2560, 2400 to 1080),
        listOf(floatArrayOf(0f, 0f, 1f, 0.12f)), 2400, 1080, 2400, true),
    /** Settings header: title in the bottom-left third. */
    HEADER("header", listOf(1440 to 480, 1080 to 360, 2400 to 480),
        listOf(floatArrayOf(0f, 0.667f, 0.333f, 1f)), 1440, 1440, 480, false);

    companion object {
        fun from(key: String): ImageSlot? = entries.firstOrNull { it.key == key }
    }
}

/** How a pack wants an image drawn. [scrim] is the declared strength; [scrimAuto] lets Ulfur raise it up to [scrimMax]. */
data class ImageSpec(
    val slot: ImageSlot,
    val file: String,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val scrim: Float = 0f,
    val scrimAuto: Boolean = true,
    val scrimMax: Float = 0.85f,
)

/** A re-encoded image (no metadata, no original bytes) plus a small pixel sample for the readability check. */
class ThemeImage(
    val bytes: ByteArray,
    val ext: String,
    val width: Int,
    val height: Int,
    val sampleW: Int,
    val sampleH: Int,
    val sample: IntArray,
) {
    override fun equals(other: Any?) = other is ThemeImage && other.ext == ext && other.width == width &&
        other.height == height && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "ThemeImage(${width}x$height .$ext, ${bytes.size} B)"
}

/** Decoding and re-encoding (Android: BitmapFactory, see AndroidImageCodec). */
interface ThemeImageCodec {
    /**
     * Bounds first (inJustDecodeBounds), refuse past [ImageSniffer.MAX_SIDE] / [ImageSniffer.MAX_PIXELS], then decode
     * downsampled to ≤ [maxSide] and re-encode (PNG if it has alpha, else JPEG). Null = can't be decoded.
     */
    fun sanitize(bytes: ByteArray, maxSide: Int): ThemeImage?
    /** Our own stored, already sanitised file: decode only for the sample. */
    fun load(bytes: ByteArray, ext: String): ThemeImage?
}

/** Format sniffing from magic bytes, and image dimensions read from the header without decoding. */
object ImageSniffer {
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 16_000_000L

    enum class Format(val mime: String) { PNG("image/png"), JPEG("image/jpeg"), WEBP("image/webp") }

    sealed class Result {
        data class Ok(val format: Format, val width: Int, val height: Int) : Result()
        /** [reason]: "svg", "gif", "heic", "animated", "unknown", "bad header", "too many pixels"… */
        data class Refused(val reason: String) : Result()
    }

    fun sniff(b: ByteArray): Result {
        val r = header(b)
        if (r is Result.Ok) {
            if (r.width <= 0 || r.height <= 0) return Result.Refused("bad header")
            if (r.width > MAX_SIDE || r.height > MAX_SIDE || r.width.toLong() * r.height > MAX_PIXELS) return Result.Refused("too many pixels")
        }
        return r
    }

    private fun header(b: ByteArray): Result = when {
        starts(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> png(b)
        starts(b, 0xFF, 0xD8, 0xFF) -> jpeg(b)
        b.size >= 12 && ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP") -> webp(b)
        b.size >= 6 && ascii(b, 0, "GIF8") -> Result.Refused("gif")
        b.size >= 12 && ascii(b, 4, "ftyp") -> Result.Refused("heic")
        looksLikeSvg(b) -> Result.Refused("svg")
        else -> Result.Refused("unknown")
    }

    private fun png(b: ByteArray): Result {
        if (b.size < 33 || !ascii(b, 12, "IHDR")) return Result.Refused("bad header")
        val w = be32(b, 16); val h = be32(b, 20)
        // APNG: an acTL chunk before the first IDAT.
        var p = 8
        while (p + 8 <= b.size) {
            val len = be32(b, p)
            if (len < 0 || len > b.size) return Result.Refused("bad header")
            if (ascii(b, p + 4, "acTL")) return Result.Refused("animated")
            if (ascii(b, p + 4, "IDAT") || ascii(b, p + 4, "IEND")) break
            p += 12 + len
        }
        return Result.Ok(Format.PNG, w, h)
    }

    private fun jpeg(b: ByteArray): Result {
        var p = 2
        while (p + 4 <= b.size) {
            if (u8(b, p) != 0xFF) return Result.Refused("bad header")
            val m = u8(b, p + 1)
            if (m == 0xFF) { p++; continue }
            if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { p += 2; continue }
            if (m == 0xD9 || m == 0xDA) return Result.Refused("bad header") // no frame header before scan/end
            val len = (u8(b, p + 2) shl 8) or u8(b, p + 3)
            if (len < 2) return Result.Refused("bad header")
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (p + 9 > b.size) return Result.Refused("bad header")
                val h = (u8(b, p + 5) shl 8) or u8(b, p + 6)
                val w = (u8(b, p + 7) shl 8) or u8(b, p + 8)
                return Result.Ok(Format.JPEG, w, h)
            }
            p += 2 + len
        }
        return Result.Refused("bad header")
    }

    private fun webp(b: ByteArray): Result {
        if (b.size < 30) return Result.Refused("bad header")
        return when {
            ascii(b, 12, "VP8X") -> {
                val flags = u8(b, 20)
                if (flags and 0x02 != 0) return Result.Refused("animated")
                if (containsChunk(b, "ANIM") || containsChunk(b, "ANMF")) return Result.Refused("animated")
                Result.Ok(Format.WEBP, 1 + le24(b, 24), 1 + le24(b, 27))
            }
            ascii(b, 12, "VP8L") -> {
                if (u8(b, 20) != 0x2F) return Result.Refused("bad header")
                val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                Result.Ok(Format.WEBP, 1 + (bits and 0x3FFF), 1 + ((bits ushr 14) and 0x3FFF))
            }
            ascii(b, 12, "VP8 ") -> {
                if (!(u8(b, 23) == 0x9D && u8(b, 24) == 0x01 && u8(b, 25) == 0x2A)) return Result.Refused("bad header")
                Result.Ok(Format.WEBP, (u8(b, 26) or (u8(b, 27) shl 8)) and 0x3FFF, (u8(b, 28) or (u8(b, 29) shl 8)) and 0x3FFF)
            }
            else -> Result.Refused("bad header")
        }
    }

    private fun containsChunk(b: ByteArray, id: String): Boolean {
        var p = 12
        while (p + 8 <= b.size) {
            if (ascii(b, p, id)) return true
            val len = u8(b, p + 4) or (u8(b, p + 5) shl 8) or (u8(b, p + 6) shl 16) or (u8(b, p + 7) shl 24)
            if (len < 0) return false
            p += 8 + len + (len and 1)
        }
        return false
    }

    private fun looksLikeSvg(b: ByteArray): Boolean {
        val head = String(b, 0, min(b.size, 512), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        return head.startsWith("<svg") || head.startsWith("<?xml") || "<svg" in head
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun starts(b: ByteArray, vararg m: Int) = b.size >= m.size && m.indices.all { u8(b, it) == m[it] }
    private fun ascii(b: ByteArray, at: Int, s: String) = at + s.length <= b.size && s.indices.all { b[at + it].toInt() == s[it].code }
}

/** Readability of text drawn over an image slot (after its scrim). Pure maths on the pixel sample. */
object ImageContrast {
    data class Crop(val left: Float, val top: Float, val width: Float, val height: Float)

    /** Source rectangle (image px) a "cover" fit shows in a [vw]×[vh] view, centred on the focal point. */
    fun cover(iw: Int, ih: Int, vw: Int, vh: Int, fx: Float, fy: Float): Crop {
        val scale = max(vw.toFloat() / iw, vh.toFloat() / ih)
        val cw = vw / scale; val ch = vh / scale
        val left = (fx * iw - cw / 2).coerceIn(0f, max(0f, iw - cw))
        val top = (fy * ih - ch / 2).coerceIn(0f, max(0f, ih - ch))
        return Crop(left, top, cw, ch)
    }

    data class Extremes(val darkest: Double, val lightest: Double)

    /**
     * Darkest and lightest relative luminance of the sample pixels that can sit under [slot]'s text, after a scrim
     * of [scrimColor] at [scrim] strength, across every view size. Null if no pixel falls in the zone.
     */
    fun extremes(img: ThemeImage, slot: ImageSlot, spec: ImageSpec, scrimColor: Int, scrim: Float): Extremes? {
        var lo = Double.MAX_VALUE; var hi = -1.0
        val sx = img.sampleW.toFloat() / img.width; val sy = img.sampleH.toFloat() / img.height
        val seen = BooleanArray(img.sample.size)
        for ((vw, vh) in slot.views) {
            val c = cover(img.width, img.height, vw, vh, spec.focusX, spec.focusY)
            for (z in slot.textZones) {
            val x0 = ((c.left + z[0] * c.width) * sx).toInt().coerceIn(0, img.sampleW - 1)
            val x1 = ((c.left + z[2] * c.width) * sx).toInt().coerceIn(x0, img.sampleW - 1)
            val y0 = ((c.top + z[1] * c.height) * sy).toInt().coerceIn(0, img.sampleH - 1)
            val y1 = ((c.top + z[3] * c.height) * sy).toInt().coerceIn(y0, img.sampleH - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * img.sampleW + x
                if (seen[i]) continue
                seen[i] = true
                val l = ColorUtils.calculateLuminance(blend(img.sample[i], scrimColor, scrim))
                if (l < lo) lo = l
                if (l > hi) hi = l
            }
            }
        }
        return if (hi < 0) null else Extremes(lo, hi)
    }

    /** Opaque result of [scrimColor] at [alpha] over [px] (px alpha treated as over [scrimColor] too). */
    fun blend(px: Int, scrimColor: Int, alpha: Float): Int {
        val base = ColorUtils.compositeColors(px, scrimColor or 0xFF000000.toInt())
        return ColorUtils.blendARGB(base, scrimColor or 0xFF000000.toInt(), alpha.coerceIn(0f, 1f))
    }

    /** Worst ratio of [text] against the darkest and the lightest point (not an average). */
    fun worstRatio(text: Int, e: Extremes): Double {
        val lt = ColorUtils.calculateLuminance(text or 0xFF000000.toInt())
        fun r(l: Double) = (max(lt, l) + 0.05) / (min(lt, l) + 0.05)
        return min(r(e.darkest), r(e.lightest))
    }

    data class Verdict(val scrim: Float, val ratio: Double, val raised: Boolean)

    /** The scrim to draw with: the declared one, raised in 0.01 steps up to scrimMax when allowed, to reach 4.5:1. */
    fun resolve(img: ThemeImage, spec: ImageSpec, text: Int, scrimColor: Int, min: Double = ThemeContrast.MIN_TEXT): Verdict {
        fun at(s: Float) = extremes(img, spec.slot, spec, scrimColor, s)?.let { worstRatio(text, it) } ?: Double.MAX_VALUE
        val declared = at(spec.scrim)
        if (declared >= min || !spec.scrimAuto) return Verdict(spec.scrim, declared, false)
        var s = spec.scrim
        var r = declared
        while (s < spec.scrimMax - 1e-6) {
            s = (s + 0.01f).coerceAtMost(spec.scrimMax)
            r = at(s)
            if (r >= min) break
        }
        return Verdict(s, r, true)
    }
}
