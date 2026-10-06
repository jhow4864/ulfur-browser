package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.UboBadge
import com.jamhowman.beastbrowser.data.TrackerTally
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * All-time blocked counter and the weekly tally's uBlock line (roadmap 10): uBlock Origin badge updates are counted
 * once, late updates included, by the same rule and the same Tab.uboCounted memory. Private tabs skip the tally.
 */
class UboBadgeTest {

    /**
     * Mirrors MainActivity's uBO action delegate (including tallyUbo) and TabCallbacks.onPageStart
     * (Tab.resetPageStats) for one tab.
     */
    private class FakeTab(val private: Boolean = false, val url: String = "https://www.example.com/page") {
        var total = 0L        // Stats.total
        var tally = 0L        // this tab's site, uBlock line of the weekly tally
        var uboCount = 0      // Tab.uboCount: shown as this page's uBO count
        var uboCounted = 0    // Tab.uboCounted: never reset on navigation

        fun badge(n: Int) {
            val gained = UboBadge.newBlocks(uboCounted, n)
            total += gained
            uboCount = UboBadge.pageCount(uboCount, uboCounted, n)
            uboCounted = n
            // tallyUbo(tab, gained)
            if (gained > 0 && TrackerTally.siteToRecord(private, url) != null) tally += gained
        }

        fun pageStart() { uboCount = 0 }

        /** Settings > Reset blocked counter (Stats.reset): only the total goes back to zero. */
        fun resetCounter() { total = 0 }
    }

    @Test fun riseAddsTheDifferenceAndRepeatAddsNothing() {
        assertEquals(3, UboBadge.newBlocks(0, 3))
        assertEquals(4, UboBadge.newBlocks(3, 7))
        assertEquals(0, UboBadge.newBlocks(7, 7))
    }

    @Test fun dropMeansANewPageAndCountsInFull() {
        assertEquals(2, UboBadge.newBlocks(7, 2))
        assertEquals(1, UboBadge.newBlocks(40, 1))
    }

    @Test fun emptyOrZeroBadgeCountsNothingAndIsNeverNegative() {
        assertEquals(0, UboBadge.newBlocks(5, 0))
        assertEquals(0, UboBadge.newBlocks(0, 0))
        assertEquals(0, UboBadge.newBlocks(5, -1))
        assertEquals(0, UboBadge.pageCount(5, 5, -1))
    }

    @Test fun pageCountIgnoresARepeatOnlyAfterTheNewPageStarted() {
        assertEquals(7, UboBadge.pageCount(7, 7, 7))   // same page, repeated update
        assertEquals(0, UboBadge.pageCount(0, 7, 7))   // new page, late update for the old one
        assertEquals(9, UboBadge.pageCount(7, 7, 9))
        assertEquals(2, UboBadge.pageCount(0, 7, 2))
    }

    @Test fun lateUpdateForThePreviousPageIsNotCountedTwice() {
        val t = FakeTab()
        t.badge(2); t.badge(5)
        assertEquals(5L, t.total)
        t.pageStart()
        t.badge(5)            // uBO's late update for the previous page
        assertEquals(5L, t.total)   // the old rule counted 10
        assertEquals(0, t.uboCount) // and showed 5 on the new page's badge
        t.badge(0)            // uBO starts the new page
        t.badge(3)
        assertEquals(8L, t.total)
        assertEquals(3, t.uboCount)
    }

    @Test fun newPageWithoutAZeroUpdateStillCounts() {
        val t = FakeTab()
        t.badge(6)
        t.pageStart()
        t.badge(2)            // lower than before: a new page, counted in full
        t.badge(4)
        assertEquals(10L, t.total)
        assertEquals(4, t.uboCount)
    }

    @Test fun reloadAndRepeatedUpdatesOnOnePage() {
        val t = FakeTab()
        t.badge(4); t.badge(4); t.badge(4)
        assertEquals(4L, t.total)
        assertEquals(4, t.uboCount)
        t.pageStart(); t.badge(0); t.badge(4)   // reload of the same page blocks the same 4 again
        assertEquals(8L, t.total)
        assertEquals(4, t.uboCount)
    }

    @Test fun resetBlockedCounterKeepsWorking() {
        val t = FakeTab()
        t.badge(5)
        t.resetCounter()
        assertEquals(0L, t.total)
        t.badge(5)            // repeat after the reset: nothing new was blocked
        assertEquals(0L, t.total)
        t.badge(8)            // only the 3 new blocks count from zero
        assertEquals(3L, t.total)
        t.pageStart(); t.badge(8)   // late update for that page after navigating
        assertEquals(3L, t.total)
    }

    /** Badge stream for one tab; returns the blocks the weekly tally's uBlock line got. */
    private fun tallyOf(vararg badges: Int): Long {
        val t = FakeTab()
        for (b in badges) t.badge(b)
        assertEquals("the tally gets exactly what the all-time total gets", t.total, t.tally)
        return t.tally
    }

    @Test fun weeklyTallyGetsExactlyTheNewlyCountedBlocks() {
        assertEquals(7L, tallyOf(1, 1, 3, 3, 7, 7, 7))          // repeated updates of the same number
        assertEquals(7L + 7, tallyOf(1, 3, 7, 0, 2, 7))         // reload: badge restarts, the page blocks 7 again
        assertEquals(7L + 4, tallyOf(2, 7, 4))                  // next page reported straight away with 4
        assertEquals(7L, tallyOf(3, 7, 7, 7))                   // late updates for the old page after navigation
        assertEquals(1200L + 5, tallyOf(999, 1200, 0, 5))       // "1.2k" style badges still only add the rise
        assertEquals(0L, tallyOf(0, 0, -1))                     // nothing blocked
    }

    @Test fun lateUpdateIsNotTalliedTwice() {
        val t = FakeTab()
        t.badge(2); t.badge(5)
        t.pageStart()
        t.badge(5)            // uBO's late update for the previous page
        assertEquals(5L, t.tally)
        t.badge(0); t.badge(3)
        assertEquals(8L, t.tally)
    }

    @Test fun privateTabsCountTowardsTheTotalButNeverTheTally() {
        val t = FakeTab(private = true)
        t.badge(4); t.badge(9); t.pageStart(); t.badge(2)
        assertEquals(11L, t.total)
        assertEquals(0L, t.tally)
    }

    @Test fun nonWebPagesAreNotTallied() {
        val t = FakeTab(url = "beast://home")
        t.badge(3)
        assertEquals(3L, t.total)
        assertEquals(0L, t.tally)
    }
}
