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

/** Snackbar action text in [accent], lightened/darkened until it reaches 4.5:1 on the snackbar's surface3 (SPEC.md "snackAction"). */
fun snackActionColor(ctx: Context, accent: Int): Int =
    com.jamhowman.beastbrowser.util.Contrast.readableOn(accent, ctx.getColor(com.jamhowman.beastbrowser.R.color.surface3))
