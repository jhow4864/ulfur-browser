package com.jamhowman.beastbrowser.data

import androidx.annotation.StyleRes
import com.jamhowman.beastbrowser.R

enum class Accent(val key: String, val label: String, val color: Int, val onColor: Int, @StyleRes val overlay: Int) {
    RED("red", "GX Red", 0xFFFA1E4E.toInt(), 0xFFFFFFFF.toInt(), R.style.ThemeOverlay_Beast_Accent_Red),
    PURPLE("purple", "Ultraviolet", 0xFFA63BFF.toInt(), 0xFFFFFFFF.toInt(), R.style.ThemeOverlay_Beast_Accent_Purple),
    CYAN("cyan", "Cyber Cyan", 0xFF00E1FF.toInt(), 0xFF001F26.toInt(), R.style.ThemeOverlay_Beast_Accent_Cyan),
    GREEN("green", "Toxic Green", 0xFF2BFF88.toInt(), 0xFF00210E.toInt(), R.style.ThemeOverlay_Beast_Accent_Green),
    ORANGE("orange", "Lava Orange", 0xFFFF7A1A.toInt(), 0xFF2A1100.toInt(), R.style.ThemeOverlay_Beast_Accent_Orange);

    fun withAlpha(alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: RED
    }
}
