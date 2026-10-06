package com.jamhowman.beastbrowser.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.ShieldLevel
import com.jamhowman.beastbrowser.data.ThemePalette

/**
 * Roadmap 10: how the toolbar shield looks for a [ShieldLevel]. The only place that maps shield state to
 * drawables and colours (all existing ones), so the designer can swap visuals here without touching the
 * state logic in [ShieldLevel] or the wiring in MainActivity.updateShield().
 */
object ShieldBadge {
    /**
     * Colour roles; MainActivity resolves them: ACCENT = the toolbar's [ThemePalette] (2.8: the realm's preset for
     * light/dark, plus the private-mode override when one is set), MUTED = R.color.text_hint, WARN = R.color.warn.
     */
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
        /** Paint the badge with the theme's accent gradient ([gradient]) instead of a flat [badgeTint] fill. */
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
     * Stops of the 'many' badge gradient (painted top-left to bottom-right, the spec's 135°): the active theme's own
     * accentStart → accentEnd from [palette] (roadmap 18), so it follows the chosen preset and re-tints with it.
     * Null when the palette doesn't allow text on its gradient (Ghost, SPEC "textFill": solid): the badge carries the
     * count, so it falls back to the flat solid accent, like every other text-bearing fill in that palette.
     */
    fun gradient(palette: ThemePalette): IntArray? = if (palette.gradientCarriesText) palette.gradient else null
}
