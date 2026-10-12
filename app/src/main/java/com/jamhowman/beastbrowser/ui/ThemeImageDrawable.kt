package com.jamhowman.beastbrowser.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.jamhowman.beastbrowser.data.ImageContrast
import com.jamhowman.beastbrowser.data.ImageSlot
import com.jamhowman.beastbrowser.data.ThemePack
import com.jamhowman.beastbrowser.data.ThemePacks
import kotlin.math.roundToInt

/**
 * 2.10: a theme-pack image drawn "cover" around its focal point, on its backing colour, under the scrim the
 * readability check settled on ([ThemePack.scrimVerdict]). Same crop maths as [ImageContrast.cover].
 */
class ThemeImageDrawable(
    private val bitmap: Bitmap,
    private val fx: Float,
    private val fy: Float,
    private val backing: Int,
    private val scrim: Float,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawColor(backing or 0xFF000000.toInt())
        val c = ImageContrast.cover(bitmap.width, bitmap.height, b.width(), b.height(), fx, fy)
        src.set(c.left.roundToInt(), c.top.roundToInt(), (c.left + c.width).roundToInt(), (c.top + c.height).roundToInt())
        canvas.drawBitmap(bitmap, src, b, paint)
        canvas.drawColor(((scrim.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (backing and 0xFFFFFF))
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        /** The active pack's [slot] image for [night], or null (no pack, paused by safe start, no image). */
        fun active(slot: ImageSlot, night: Boolean): ThemeImageDrawable? {
            val pack = ThemePacks.activePack() ?: return null
            return of(pack, slot, night, ThemePacks.activeBitmap(slot) ?: return null)
        }

        fun of(pack: ThemePack, slot: ImageSlot, night: Boolean, bmp: Bitmap): ThemeImageDrawable? {
            val spec = pack.imageSpecs[slot] ?: return null
            val v = pack.scrimVerdict(slot, night) ?: return null
            return ThemeImageDrawable(bmp, spec.focusX, spec.focusY, pack.slotColors(slot, night).second, v.scrim)
        }
    }
}
