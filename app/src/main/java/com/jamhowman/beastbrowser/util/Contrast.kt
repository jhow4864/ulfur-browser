package com.jamhowman.beastbrowser.util

import kotlin.math.pow

/** WCAG 2.1 contrast helpers (pure, so they're unit-tested without Android). Colours are ARGB ints; alpha is ignored. */
object Contrast {
    /** WCAG AA for normal text. */
    const val AA_TEXT = 4.5

    fun luminance(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * [fg] made readable on [bg]: unchanged when it already reaches [min], otherwise mixed toward white (dark
     * backgrounds) or black (light backgrounds) in 1% steps until it does. Designer's SPEC.md "snackAction":
     * Blood Moon #FF2D55 on surface3 #25252F becomes about #FF476A (4.5:1+); cyan stays #00E1FF.
     */
    fun readableOn(fg: Int, bg: Int, min: Double = AA_TEXT): Int {
        if (ratio(fg, bg) >= min) return fg or 0xFF000000.toInt()
        val target = if (luminance(bg) < 0.5) 255 else 0
        for (step in 1..100) {
            val c = mix(fg, target, step / 100.0)
            if (ratio(c, bg) >= min) return c
        }
        return if (target == 255) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    private fun mix(c: Int, target: Int, t: Double): Int {
        fun m(v: Int) = Math.round(v + (target - v) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(c shr 16 and 0xFF) shl 16) or (m(c shr 8 and 0xFF) shl 8) or m(c and 0xFF)
    }
}
