package com.jamhowman.beastbrowser.browser

/**
 * Roadmap 10: how much the shield caught on the current page. Pure state only; the toolbar's look for each
 * level lives in [com.jamhowman.beastbrowser.ui.ShieldBadge] so the visuals can change without touching this.
 */
enum class ShieldLevel {
    /** Nothing blocked on this page (or no page: Speed Dial). */
    NONE,
    /** 1 .. [MANY_FROM] - 1 blocked. */
    SOME,
    /** [MANY_FROM] or more blocked. */
    MANY;

    companion object {
        /** The one threshold: below this a page with blocks is "some", from it on "many". */
        const val MANY_FROM = 10

        fun of(blocked: Int): ShieldLevel = when {
            blocked <= 0 -> NONE
            blocked < MANY_FROM -> SOME
            else -> MANY
        }
    }
}
