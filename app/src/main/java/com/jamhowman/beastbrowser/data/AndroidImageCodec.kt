package com.jamhowman.beastbrowser.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 2.10: theme-pack images through BitmapFactory. Bounds are read first (inJustDecodeBounds) and refused past
 * [ImageSniffer.MAX_SIDE] / [ImageSniffer.MAX_PIXELS] before any pixel memory is allocated; then a power-of-two
 * inSampleSize plus a final scale brings the long side to ≤ maxSide. The result is re-encoded from pixels (PNG when
 * it has transparency, else JPEG q90), so EXIF, GPS, ICC, XMP and every other original byte are dropped.
 * Animated WebP/APNG are refused earlier by [ImageSniffer]; BitmapFactory would only ever read the first frame.
 */
object AndroidImageCodec : ThemeImageCodec {
    const val SAMPLE_SIDE = 192

    data class Bounds(val width: Int, val height: Int, val mime: String?)

    fun bounds(bytes: ByteArray): Bounds? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth <= 0 || o.outHeight <= 0) null else Bounds(o.outWidth, o.outHeight, o.outMimeType)
    }

    private fun within(b: Bounds) = b.width <= ImageSniffer.MAX_SIDE && b.height <= ImageSniffer.MAX_SIDE &&
        b.width.toLong() * b.height <= ImageSniffer.MAX_PIXELS &&
        (b.mime == null || b.mime in setOf("image/png", "image/jpeg", "image/webp"))

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val b = bounds(bytes) ?: return null
        if (!within(b)) return null
        var sample = 1
        while (max(b.width, b.height) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) } catch (_: OutOfMemoryError) { null } ?: return null
        val long = max(bmp.width, bmp.height)
        if (long <= maxSide) return bmp
        val f = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    override fun sanitize(bytes: ByteArray, maxSide: Int): ThemeImage? {
        val bmp = decode(bytes, maxSide) ?: return null
        val alpha = bmp.hasAlpha() && hasTransparentPixel(bmp)
        val out = ByteArrayOutputStream()
        val ok = if (alpha) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (!ok) return null
        return image(out.toByteArray(), if (alpha) "png" else "jpg", bmp)
    }

    override fun load(bytes: ByteArray, ext: String): ThemeImage? {
        val bmp = decode(bytes, max(ImageSniffer.MAX_SIDE, 1)) ?: return null
        return image(bytes, ext, bmp)
    }

    fun bitmap(img: ThemeImage): Bitmap? = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)

    private fun image(bytes: ByteArray, ext: String, bmp: Bitmap): ThemeImage {
        val f = SAMPLE_SIDE.toFloat() / max(bmp.width, bmp.height)
        val small = if (f < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), false) else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val img = ThemeImage(bytes, ext, bmp.width, bmp.height, small.width, small.height, px)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return img
    }

    private fun hasTransparentPixel(b: Bitmap): Boolean {
        val row = IntArray(b.width)
        for (y in 0 until b.height step max(1, b.height / 64)) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }
}
