package com.jamhowman.beastbrowser.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 2.11: the photo behind "Create theme from photo". Bounded decode only: bounds first, then a power-of-two
 * inSampleSize so the decoded bitmap is at most [ImageSniffer.MAX_SIDE] px a side and [ImageSniffer.MAX_PIXELS]
 * pixels; tiny and corrupt photos are refused with a reason instead of crashing. The crop is re-encoded from pixels
 * (no EXIF, GPS, XMP or ICC survive) and kept in memory: the original is never written anywhere.
 */
object PhotoImage {
    const val MAX_INPUT_BYTES = 48L * 1024 * 1024
    const val MIN_SIDE = 64
    const val HOME_W = 1080
    const val HOME_H = 2400

    sealed class Result {
        class Ok(val bitmap: Bitmap) : Result()
        data class Refused(val reason: String) : Result()
    }

    /** Reads at most [MAX_INPUT_BYTES] (+1 to detect more). */
    fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > MAX_INPUT_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Power-of-two sample so the decode fits the side and pixel caps. */
    fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        while (w / s > ImageSniffer.MAX_SIDE || h / s > ImageSniffer.MAX_SIDE ||
            (w / s).toLong() * (h / s) > ImageSniffer.MAX_PIXELS) s *= 2
        return s
    }

    fun decode(bytes: ByteArray?): Result {
        if (bytes == null) return Result.Refused("Photo is too large")
        if (bytes.isEmpty()) return Result.Refused("Photo can't be read")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) } catch (_: Throwable) { return Result.Refused("Photo can't be read") }
        if (o.outWidth <= 0 || o.outHeight <= 0) return Result.Refused("Photo can't be read")
        if (o.outWidth < MIN_SIDE || o.outHeight < MIN_SIDE) return Result.Refused("Photo is too small")
        if (o.outMimeType != null && o.outMimeType !in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic", "image/avif"))
            return Result.Refused("Unsupported image type")
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(o.outWidth, o.outHeight); inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }
            ?: return Result.Refused("Photo can't be read")
        if (bmp.width > ImageSniffer.MAX_SIDE || bmp.height > ImageSniffer.MAX_SIDE) { bmp.recycle(); return Result.Refused("Photo is too large") }
        return Result.Ok(bmp)
    }

    /** The home-slot crop (20:9 portrait, ≤ 1080×2400) around the focal point, re-encoded as JPEG. */
    fun cropHome(src: Bitmap, fx: Float, fy: Float): ByteArray {
        val c = ImageContrast.cover(src.width, src.height, HOME_W, HOME_H, fx, fy)
        val left = c.left.roundToInt().coerceIn(0, src.width - 1)
        val top = c.top.roundToInt().coerceIn(0, src.height - 1)
        val w = c.width.roundToInt().coerceIn(1, src.width - left)
        val h = c.height.roundToInt().coerceIn(1, src.height - top)
        val crop = Bitmap.createBitmap(src, left, top, w, h)
        val f = minOf(1f, HOME_W.toFloat() / w, HOME_H.toFloat() / h)
        val out = if (f < 1f) Bitmap.createScaledBitmap(crop, max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()), true) else crop
        val bos = ByteArrayOutputStream()
        // Fresh pixels on an opaque canvas: JPEG from a Bitmap carries no metadata at all.
        out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        if (out !== crop) out.recycle()
        if (crop !== src) crop.recycle()
        return bos.toByteArray()
    }

    /** Crop, then the same sanitiser theme-pack import uses (bounded decode + re-encode): the pack's home image. */
    fun homeImage(src: Bitmap, fx: Float, fy: Float): ThemeImage? =
        AndroidImageCodec.sanitize(cropHome(src, fx, fy), ImageSlot.HOME.maxSide)
}
