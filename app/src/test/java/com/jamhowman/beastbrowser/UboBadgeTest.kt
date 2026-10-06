package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.UboBadge
import org.junit.Assert.assertEquals
import org.junit.Test

/** All-time blocked counter: uBlock Origin badge updates are counted once, late updates included. */
class UboBadgeTest {

    /** Mirrors MainActivity's uBO action delegate and TabCallbacks.onPageStart (Tab.resetPageStats) for one tab. */
    private class FakeTab {
        var total = 0L        // Stats.total
        var uboCount = 0      // Tab.uboCount: shown as this page's uBO count
        var uboCounted = 0    // Tab.uboCounted: never reset on navigation

        fun badge(n: Int) {
            total += UboBadge.newBlocks(uboCounted, n)
            uboCount = UboBadge.pageCount(uboCount, uboCounted, n)
            uboCounted = n
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
}
