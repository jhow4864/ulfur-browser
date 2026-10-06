package com.jamhowman.beastbrowser.browser

/**
 * Counting rule for uBlock Origin's per-tab badge number. uBO only reports "N blocked on this page", and it can
 * send a late update for the previous page after we've already started the next one, so each tab remembers the
 * last badge number already counted ([Tab.uboCounted]) and that memory is not reset on navigation. Pure, so it's
 * unit-tested without Android.
 */
object UboBadge {
    /**
     * Blocks to add to the all-time total, and to the weekly tracker tally's uBlock line (roadmap 10, skipped for
     * private tabs), when a tab's badge goes from [lastCounted] to [badge]. Within a page the
     * badge only climbs, so a rise adds the difference and the same number again (a repeated update, or a late
     * update for the previous page) adds nothing. A drop means uBO started a new page or a reload, which begins at
     * zero, so the new number counts in full. Never negative.
     */
    fun newBlocks(lastCounted: Int, badge: Int): Int = when {
        badge <= 0 -> 0
        badge >= lastCounted -> badge - lastCounted
        else -> badge
    }

    /**
     * uBO's count to show for the current page. A repeat of the last counted number keeps [pageCount] as it is:
     * on the same page that's already [badge], and after a new page started (page stats reset to 0) it's a late
     * update for the previous page that mustn't be shown as this page's count.
     */
    fun pageCount(pageCount: Int, lastCounted: Int, badge: Int): Int =
        if (badge == lastCounted) pageCount else maxOf(badge, 0)
}
