package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.TallyRow
import com.jamhowman.beastbrowser.data.TrackerCategory
import com.jamhowman.beastbrowser.data.TrackerTally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.ContentBlocking.AntiTracking
import org.mozilla.geckoview.ContentBlocking.CookieBehavior

/**
 * Roadmap 10: tally rules (site key, private gate, categories, weekly aggregation, retention). Pure JVM.
 * The uBlock line's counting rule is main's [com.jamhowman.beastbrowser.browser.UboBadge.newBlocks]: see UboBadgeTest.
 */
class TrackerTallyTest {
    private val today = 20_000L

    @Test fun siteIsOnlyTheHostOfTheTopLevelPage() {
        assertEquals("example.com", TrackerTally.siteOf("https://www.example.com/secret/path?q=1#frag"))
        assertEquals("news.bbc.co.uk", TrackerTally.siteOf("http://news.bbc.co.uk:8080/a"))
        assertEquals("example.org", TrackerTally.siteOf("https://user:pw@Example.ORG./x"))
        assertEquals("example.net", TrackerTally.siteOf("https://example.net?x=/y"))
        assertEquals("[::1]", TrackerTally.siteOf("http://[::1]:8000/"))
        for (s in listOf("https://www.example.com/secret/path?q=1", "https://a.b/c?d#e")) {
            val site = TrackerTally.siteOf(s)!!
            assertTrue(site, '/' !in site && '?' !in site && '#' !in site && '@' !in site)
        }
    }

    @Test fun nonWebPagesHaveNoSite() {
        for (u in listOf(null, "", "beast://home", "about:blank", "moz-extension://abc/reader.html", "data:text/html,hi",
            "file:///sdcard/a.html", "https://", "https:///path")) {
            assertNull(u, TrackerTally.siteOf(u))
        }
    }

    @Test fun privateTabsAreNeverRecorded() {
        assertNull(TrackerTally.siteToRecord(isPrivate = true, pageUrl = "https://example.com/"))
        assertEquals("example.com", TrackerTally.siteToRecord(isPrivate = false, pageUrl = "https://example.com/"))
    }

    @Test fun categoriesFromBlockEventFlags() {
        assertEquals(TrackerCategory.ADS, TrackerCategory.from(AntiTracking.AD, 0))
        assertEquals(TrackerCategory.ANALYTICS, TrackerCategory.from(AntiTracking.ANALYTIC, 0))
        assertEquals(TrackerCategory.SOCIAL, TrackerCategory.from(AntiTracking.SOCIAL, 0))
        assertEquals(TrackerCategory.SOCIAL, TrackerCategory.from(AntiTracking.STP, 0))
        assertEquals(TrackerCategory.FINGERPRINTERS, TrackerCategory.from(AntiTracking.FINGERPRINTING, 0))
        assertEquals(TrackerCategory.CRYPTOMINERS, TrackerCategory.from(AntiTracking.CRYPTOMINING, 0))
        assertEquals(TrackerCategory.EMAIL, TrackerCategory.from(AntiTracking.EMAIL, 0))
        assertEquals(TrackerCategory.CONTENT, TrackerCategory.from(AntiTracking.CONTENT, 0))
        assertEquals(TrackerCategory.COOKIES, TrackerCategory.from(0, CookieBehavior.ACCEPT_NON_TRACKERS))
        assertEquals(TrackerCategory.OTHER, TrackerCategory.from(0, 0))
        // Combined flags: the most specific wins
        assertEquals(TrackerCategory.FINGERPRINTERS, TrackerCategory.from(AntiTracking.AD or AntiTracking.FINGERPRINTING, 0))
        assertEquals(TrackerCategory.ADS, TrackerCategory.from(AntiTracking.AD or AntiTracking.ANALYTIC, CookieBehavior.ACCEPT_NON_TRACKERS))
    }

    @Test fun geckoFlagsNeverMapToTheUblockLine() {
        val flags = listOf(0, AntiTracking.AD, AntiTracking.ANALYTIC, AntiTracking.SOCIAL, AntiTracking.STP, AntiTracking.CONTENT,
            AntiTracking.FINGERPRINTING, AntiTracking.CRYPTOMINING, AntiTracking.EMAIL, AntiTracking.STRICT, -1)
        for (at in flags) for (cb in listOf(0, CookieBehavior.ACCEPT_NON_TRACKERS)) {
            assertTrue("$at/$cb", TrackerCategory.from(at, cb) != TrackerCategory.UBLOCK)
        }
        assertEquals("ublock", TrackerCategory.UBLOCK.key)
    }

    @Test fun categoryKeysRoundTripAndUnknownIsOther() {
        for (c in TrackerCategory.entries) assertEquals(c, TrackerCategory.fromKey(c.key))
        assertEquals(TrackerCategory.OTHER, TrackerCategory.fromKey("something-new"))
        assertEquals(TrackerCategory.OTHER, TrackerCategory.fromKey(null))
        assertEquals(TrackerCategory.entries.size, TrackerCategory.entries.map { it.key }.toSet().size)
    }

    @Test fun weeklyCountsOnlyTheLastSevenDays() {
        val rows = listOf(
            TallyRow(today, "a.com", TrackerCategory.ADS, 3),
            TallyRow(today - 6, "a.com", TrackerCategory.ADS, 2),     // first day of the week
            TallyRow(today - 7, "a.com", TrackerCategory.ADS, 100),   // 8 days ago: out
            TallyRow(today + 1, "a.com", TrackerCategory.ADS, 100),   // clock went back: out
            TallyRow(today - 2, "b.com", TrackerCategory.SOCIAL, 4),
        )
        val w = TrackerTally.weekly(rows, today)
        assertEquals(9L, w.total)
        assertEquals(listOf("a.com" to 5L, "b.com" to 4L), w.topSites)
        assertEquals(listOf(TrackerCategory.ADS to 5L, TrackerCategory.SOCIAL to 4L), w.byCategory)
        assertEquals(today - 6, w.fromDay)
        assertEquals(today, w.toDay)
    }

    @Test fun topFiveSitesSummedAcrossDaysAndCategories() {
        val rows = buildList {
            listOf("s1" to 1L, "s2" to 20L, "s3" to 3L, "s4" to 40L, "s5" to 5L, "s6" to 6L, "s7" to 7L).forEach { (s, n) ->
                add(TallyRow(today, s, TrackerCategory.ADS, n))
            }
            add(TallyRow(today - 1, "s1", TrackerCategory.ANALYTICS, 50)) // s1 = 51 in total
        }
        val w = TrackerTally.weekly(rows, today)
        assertEquals(TrackerTally.TOP_SITES, w.topSites.size)
        assertEquals(listOf("s1" to 51L, "s4" to 40L, "s2" to 20L, "s7" to 7L, "s6" to 6L), w.topSites)
        assertEquals(132L, w.total) // every site counts in the total, not just the top five
        assertEquals(w.total, w.byCategory.sumOf { it.second })
    }

    @Test fun tiesAreStable() {
        val rows = listOf(
            TallyRow(today, "zeta.com", TrackerCategory.SOCIAL, 2),
            TallyRow(today, "alpha.com", TrackerCategory.ADS, 2),
        )
        val w = TrackerTally.weekly(rows, today)
        assertEquals(listOf("alpha.com", "zeta.com"), w.topSites.map { it.first })
        assertEquals(listOf(TrackerCategory.ADS, TrackerCategory.SOCIAL), w.byCategory.map { it.first }) // enum order
    }

    @Test fun emptyWeek() {
        val w = TrackerTally.weekly(listOf(TallyRow(today - 30, "old.com", TrackerCategory.ADS, 9)), today)
        assertTrue(w.isEmpty)
        assertTrue(w.topSites.isEmpty() && w.byCategory.isEmpty())
    }

    @Test fun retentionIsEightWeeks() {
        assertEquals(56, TrackerTally.RETENTION_DAYS)
        assertEquals(today - 56, TrackerTally.pruneBefore(today))
    }
}
