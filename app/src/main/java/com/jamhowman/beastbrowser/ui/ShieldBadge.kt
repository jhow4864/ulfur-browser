package com.jamhowman.beastbrowser.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.ShieldLevel

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
    )

    /** [shieldsUp] = shields on globally and not paused for this site. */
    fun style(level: ShieldLevel, shieldsUp: Boolean): Style = when {
        !shieldsUp -> Style(R.drawable.ic_shield_outline, Tint.MUTED, level != ShieldLevel.NONE, Tint.ACCENT, R.string.shield_level_down)
        level == ShieldLevel.NONE -> Style(R.drawable.ic_shield, Tint.ACCENT, false, Tint.ACCENT, R.string.shield_level_none)
        level == ShieldLevel.SOME -> Style(R.drawable.ic_shield, Tint.ACCENT, true, Tint.ACCENT, R.string.shield_level_some)
        else -> Style(R.drawable.ic_shield, Tint.ACCENT, true, Tint.WARN, R.string.shield_level_many)
    }
}
