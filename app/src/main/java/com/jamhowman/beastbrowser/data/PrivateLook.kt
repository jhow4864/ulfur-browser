package com.jamhowman.beastbrowser.data

/**
 * Roadmap 11: a private mode you can't mistake. Private tabs, Ghost and the private vault paint their bars
 * night violet (`@color/private_bg`) whatever preset is chosen. The accent, its gradient and the wolf keep the
 * theme's own colours on purpose, so only the bars, the top stripe and the address-bar chip say "private".
 * Design: branding/ulfur/mockups-2.8-private/.
 */
object PrivateLook : ThemeOverride {
    /** Same values as `@color/private_bg` (night / day); [ThemeOverride.applyTo] has no Context to resolve it. */
    val BARS_NIGHT = 0xFF1B1328.toInt()
    val BARS_DAY = 0xFFF3EDFA.toInt()
    /** Ends of the 2dp top stripe; the middle stop is the theme's own accentEnd. */
    val STRIPE_VIOLET = 0xFF9A6BFF.toInt()

    override fun applyTo(base: ThemePalette): ThemePalette =
        base.copy(systemBars = if (base.night) BARS_NIGHT else BARS_DAY)
}
