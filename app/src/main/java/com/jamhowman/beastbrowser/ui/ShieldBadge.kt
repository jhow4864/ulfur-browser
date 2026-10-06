package com.jamhowman.beastbrowser.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.ShieldLevel
import com.jamhowman.beastbrowser.data.Accent

/**
 * Roadmap 10: how the toolbar shield looks for a [ShieldLevel]. The only place that maps shield state to
 * drawables and colours (all existing ones), so the designer can swap visuals here without touching the
 * state logic in [ShieldLevel] or the wiring in MainActivity.updateShield().
 */
object ShieldBadge {
    /** Colour roles; MainActivity resolves them (accent of the current realm, R.color.text_hint, R.color.warn). */
    enum class Tint { ACCENT, MUTED, WARN }

    data class Style(
        @DrawableRes val icon: Int,
        val iconTint: Tint,
        /** Show the blocked count on the badge. */
        val showCount: Boolean,
        /** Badge background (bg_badge recoloured). */
        val badgeTint: Tint,
        /** Spoken description, formatted with the blocked count. */
        @StringRes val description: Int,
        /** Paint the badge as a diagonal gradient from [badgeTint] to [gradientEnd] instead of a flat fill. */
        val badgeGradient: Boolean = false,
    )

    /**
     * [shieldsUp] = shields on globally and not paused for this site.
     * Design: none = quiet solid shield, no count. Some = same shield plus the count.
     * Many = shield with a check and a gradient badge. A tracker-heavy page means the shield is working,
     * so it reads as a win, not a warning (no amber).
     */
    fun style(level: ShieldLevel, shieldsUp: Boolean): Style = when {
        !shieldsUp -> Style(R.drawable.ic_shield_outline, Tint.MUTED, level != ShieldLevel.NONE, Tint.ACCENT, R.string.shield_level_down)
        level == ShieldLevel.NONE -> Style(R.drawable.ic_shield, Tint.ACCENT, false, Tint.ACCENT, R.string.shield_level_none)
        level == ShieldLevel.SOME -> Style(R.drawable.ic_shield, Tint.ACCENT, true, Tint.ACCENT, R.string.shield_level_some)
        else -> Style(R.drawable.ic_shield_check, Tint.ACCENT, true, Tint.ACCENT, R.string.shield_level_many, badgeGradient = true)
    }

    /**
     * Second gradient stop for each accent, taken from the 2.8 presets' accentEnd
     * (branding/ulfur/mockups/themes.json, matched on legacyKey). Swap for the theme's own accentEnd once item 18 lands.
     */
    fun gradientEnd(accent: Accent): Int = when (accent) {
        Accent.RED -> 0xFFE040FB.toInt()
        Accent.CYAN -> 0xFF3D7BFF.toInt()
        Accent.GREEN -> 0xFFC8FF2E.toInt()
        Accent.ORANGE -> 0xFFFF4E1A.toInt()
        Accent.PURPLE -> 0xFF5A4BFF.toInt()
    }
}
