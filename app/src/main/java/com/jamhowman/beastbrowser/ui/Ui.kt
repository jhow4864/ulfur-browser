package com.jamhowman.beastbrowser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import java.text.NumberFormat

fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

fun fmt(n: Number): String = NumberFormat.getIntegerInstance().format(n)

/** Neon line: transparent -> accent -> transparent. */
fun accentLine(view: View, accent: Int, alpha: Int = 0xCC) {
    val c = (accent and 0x00FFFFFF) or (alpha shl 24)
    view.background = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x00000000, c, c, 0x00000000)
    )
}

/** Soft radial glow behind the logo. */
fun glow(view: View, accent: Int) {
    view.background = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        shape = GradientDrawable.OVAL
        colors = intArrayOf((accent and 0x00FFFFFF) or (0x55 shl 24), 0x00000000)
        gradientRadius = view.resources.displayMetrics.density * 75
    }
}
