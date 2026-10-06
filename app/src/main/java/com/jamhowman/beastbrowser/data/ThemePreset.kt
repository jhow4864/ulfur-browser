package com.jamhowman.beastbrowser.data

import androidx.annotation.StyleRes
import com.jamhowman.beastbrowser.R

/**
 * 2.8 accent theme presets (roadmap item 18; Designer's SPEC.md + themes.json, "Theme tokens").
 *
 * Tokens are written as the hex strings from themes.json so the table can be diffed against the spec
 * (ThemePresetTest checks every one). Dark-mode values unless the name says Light.
 *
 * Persistence: `accent` (Play / the theme) and `accent_work` hold [storageKey]. The five presets that replace a
 * 2.3–2.5 [Accent] keep its key ([legacyKey]: GX Red → Blood Moon, Cyber Cyan → Frost, …), so upgrades, backups
 * and downgrades keep working. Gold, Sakura and Ash are new keys (an older build reading them falls back to red).
 *
 * [GHOST] is the fixed, dim Ghost-realm palette: never selectable, never persisted.
 */
enum class ThemePreset(
    val key: String,
    val legacyKey: String?,
    val label: String,
    accentStart: String,
    accentEnd: String,
    accent: String,
    onAccent: String,
    accentTextDark: String,
    accentTextLight: String,
    surfaceTintDark: String,
    surfaceTintLight: String,
    eye: String,
    /** SPEC "textFill": text may sit on the gradient. False (Ghost): text-bearing fills use the solid [color]. */
    val gradientCarriesText: Boolean,
    @StyleRes val overlay: Int,
) {
    BLOOD_MOON("blood_moon", "red", "Blood Moon", "#FF2D55", "#E040FB", "#FF2D55", "#0E0E12",
        "#FF3C61", "#EA002D", "#371924", "#FFE6EB", "#FF7A9A", true, R.style.ThemeOverlay_Ulfur_Preset_BloodMoon),
    FROST("frost", "cyan", "Frost", "#00E1FF", "#3D7BFF", "#00E1FF", "#0E0E12",
        "#00E1FF", "#008294", "#13323C", "#E0FBFF", "#3D7BFF", true, R.style.ThemeOverlay_Ulfur_Preset_Frost),
    TOXIC("toxic", "green", "Toxic", "#2BFF88", "#C8FF2E", "#2BFF88", "#0E0E12",
        "#2BFF88", "#00873B", "#19372B", "#E6FFF1", "#C8FF2E", true, R.style.ThemeOverlay_Ulfur_Preset_Toxic),
    EMBER("ember", "orange", "Ember", "#FFA41B", "#FF4E1A", "#FF7A1A", "#0E0E12",
        "#FF7A1A", "#C25100", "#37241C", "#FFEFE4", "#FF4E1A", true, R.style.ThemeOverlay_Ulfur_Preset_Ember),
    VOID("void", "purple", "Void", "#9C33FF", "#5A4BFF", "#A63BFF", "#FFFFFF",
        "#B65FFF", "#A436FF", "#2A1B3C", "#F4E7FF", "#5A4BFF", true, R.style.ThemeOverlay_Ulfur_Preset_Void),
    GOLD("gold", null, "Gold", "#FFD54A", "#FF9F1C", "#FFC233", "#0E0E12",
        "#FFC233", "#996B00", "#372E1F", "#FFF8E7", "#FF9F1C", true, R.style.ThemeOverlay_Ulfur_Preset_Gold),
    SAKURA("sakura", null, "Sakura", "#FF8AD8", "#B57BFF", "#FF8AD8", "#0E0E12",
        "#FF8AD8", "#E10096", "#372636", "#FFF1FA", "#B57BFF", true, R.style.ThemeOverlay_Ulfur_Preset_Sakura),
    ASH("ash", null, "Ash", "#F2F2F7", "#8E8EA0", "#D8D8E2", "#0E0E12",
        "#D8D8E2", "#727296", "#313138", "#FAFAFC", "#8E8EA0", true, R.style.ThemeOverlay_Ulfur_Preset_Ash),
    GHOST("ghost", null, "Ghost", "#8C84A8", "#5E5874", "#9A92B8", "#0E0E12",
        "#9A92B8", "#786DA0", "#282732", "#F3F2F6", "#7A6A9A", false, R.style.ThemeOverlay_Ulfur_Preset_Ghost);

    /** Start of the 135° decorative gradient (swatches, filled buttons, progress, selected border, the wolf's edge). */
    val accentStart: Int = ThemeColor.parse(accentStart)
    val accentEnd: Int = ThemeColor.parse(accentEnd)
    /** Solid accent (`colorPrimary`): icons, outlines, glows, accent lines. Same role as [Accent.color] before 2.8. */
    val color: Int = ThemeColor.parse(accent)
    /** Ink for text and icons on accent fills (≥ 4.5:1 across the gradient); also the toggle thumb. */
    val onColor: Int = ThemeColor.parse(onAccent)
    val accentTextDark: Int = ThemeColor.parse(accentTextDark)
    val accentTextLight: Int = ThemeColor.parse(accentTextLight)
    val surfaceTintDark: Int = ThemeColor.parse(surfaceTintDark)
    val surfaceTintLight: Int = ThemeColor.parse(surfaceTintLight)
    /** Second stop of the wolf's eye gradient (`?attr/ulfurEye`); for the animated wolf (item 19). */
    val eye: Int = ThemeColor.parse(eye)

    /** The value written to `accent` / `accent_work`. */
    val storageKey: String get() = legacyKey ?: key
    /** In the 4×2 grid (everything but [GHOST]). */
    val selectable: Boolean get() = this != GHOST
    val gradient: IntArray get() = intArrayOf(accentStart, accentEnd)
    /** `accent` + `55` alpha (themes.json "glow"). */
    val glow: Int get() = withAlpha(0x55)

    fun withAlpha(alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    /** Accent used as text: lightened (dark mode) or darkened (light mode) to pass AA. */
    fun accentText(night: Boolean): Int = if (night) accentTextDark else accentTextLight
    /** Selected-container colour (`colorPrimaryContainer`). */
    fun surfaceTint(night: Boolean): Int = if (night) surfaceTintDark else surfaceTintLight

    companion object {
        /** Unchanged default: GX Red's successor, same key (`red`). */
        val DEFAULT = BLOOD_MOON
        /** The eight presets of the picker, in grid order. */
        val presets: List<ThemePreset> get() = entries.filter { it.selectable }

        /** A stored or spec key (`red` and `blood_moon` both work); null when unknown, blank or Ghost. */
        fun parse(key: String?): ThemePreset? =
            if (key.isNullOrBlank()) null else presets.firstOrNull { it.key == key || it.legacyKey == key }

        /** Like [Accent.from]: unknown keys fall back to [DEFAULT]. */
        fun from(key: String?): ThemePreset = parse(key) ?: DEFAULT
    }
}

/** Hex colours as written in themes.json: `#RRGGBB` or CSS-order `#RRGGBBAA` (e.g. glow `#FF2D5555`). Returns ARGB. */
object ThemeColor {
    fun parse(hex: String): Int {
        val h = hex.trim().removePrefix("#")
        require((h.length == 6 || h.length == 8) && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Not a #RRGGBB or #RRGGBBAA colour: '$hex'"
        }
        val rgb = h.substring(0, 6).toLong(16)
        val alpha = if (h.length == 8) h.substring(6, 8).toLong(16) else 0xFFL
        return ((alpha shl 24) or rgb).toInt()
    }

    /** `#RRGGBB` (alpha dropped), as shown on the swatch cards. */
    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
