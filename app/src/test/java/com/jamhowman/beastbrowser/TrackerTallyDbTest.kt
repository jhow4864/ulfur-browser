package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.TrackerCategory
import com.jamhowman.beastbrowser.data.TrackerTally
import com.jamhowman.beastbrowser.data.TrackerTallyDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Roadmap 10: the on-device tally store (tracker_tally.db): counting, weekly read, pruning, clearing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerTallyDbTest {
    private lateinit var db: TrackerTallyDb
    // Relative to the real date: the store's own background flush prunes against today.
    private val today = TrackerTally.today()

    @Before fun setUp() {
        db = TrackerTallyDb.get(ApplicationProvider.getApplicationContext<Application>())
        db.clear()
    }

    @Test fun countsAccumulatePerDaySiteAndCategory() {
        repeat(3) { db.record("example.com", TrackerCategory.ADS, day = today) }
        db.flush(today)
        db.record("example.com", TrackerCategory.ADS, count = 2, day = today) // update path after the insert
        db.record("example.com", TrackerCategory.SOCIAL, day = today)
        db.record("example.com", TrackerCategory.ADS, day = today - 1)
        db.record("other.org", TrackerCategory.ADS, day = today)
        db.flush(today)
        val rows = db.rows(today - 1, today).associate { Triple(it.day, it.site, it.category) to it.count }
        assertEquals(
            mapOf(
                Triple(today, "example.com", TrackerCategory.ADS) to 5L,
                Triple(today, "example.com", TrackerCategory.SOCIAL) to 1L,
                Triple(today - 1, "example.com", TrackerCategory.ADS) to 1L,
                Triple(today, "other.org", TrackerCategory.ADS) to 1L,
            ),
            rows,
        )
    }

    @Test fun weeklyIncludesBufferedCounts() {
        db.record("a.com", TrackerCategory.ANALYTICS, count = 4, day = today)
        db.record("b.com", TrackerCategory.ADS, count = 2, day = today - 6)
        db.record("c.com", TrackerCategory.ADS, count = 50, day = today - 7) // outside the week
        val w = db.weekly(today) // no explicit flush: the summary must not miss the last few seconds
        assertEquals(6L, w.total)
        assertEquals(listOf("a.com" to 4L, "b.com" to 2L), w.topSites)
        assertEquals(listOf(TrackerCategory.ANALYTICS to 4L, TrackerCategory.ADS to 2L), w.byCategory)
    }

    @Test fun pruneDropsDaysOlderThanEightWeeks() {
        db.record("kept.com", TrackerCategory.ADS, day = today - (TrackerTally.RETENTION_DAYS - 1))
        db.record("gone.com", TrackerCategory.ADS, day = today - TrackerTally.RETENTION_DAYS)
        db.record("ancient.com", TrackerCategory.ADS, day = today - 400)
        db.rows(Long.MIN_VALUE / 2, today) // writes the buffer without pruning
        db.prune(today)
        assertEquals(listOf("kept.com"), db.rows(Long.MIN_VALUE / 2, today).map { it.site })
        // Today is already pruned, so nothing in the background prunes again: the count is exact.
        db.record("gone2.com", TrackerCategory.SOCIAL, day = today - TrackerTally.RETENTION_DAYS - 1)
        db.record("gone3.com", TrackerCategory.SOCIAL, day = today - 100)
        db.rows(Long.MIN_VALUE / 2, today)
        assertEquals(2, db.prune(today))
        assertEquals(listOf("kept.com"), db.rows(Long.MIN_VALUE / 2, today).map { it.site })
    }

    @Test fun clearRemovesStoredAndBufferedCounts() {
        db.record("a.com", TrackerCategory.ADS, day = today)
        db.flush(today)
        db.record("b.com", TrackerCategory.ADS, day = today) // still buffered
        db.clear()
        assertTrue(db.weekly(today).isEmpty)
        db.flush(today)
        assertTrue(db.rows(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2).isEmpty())
    }

    @Test fun ublockIsItsOwnCategoryLine() {
        db.record("video.example", TrackerCategory.UBLOCK, count = 12, day = today)
        db.record("video.example", TrackerCategory.ADS, count = 3, day = today)
        db.record("news.example", TrackerCategory.UBLOCK, count = 5, day = today - 2)
        val w = db.weekly(today)
        assertEquals(20L, w.total)
        assertEquals(listOf(TrackerCategory.UBLOCK to 17L, TrackerCategory.ADS to 3L), w.byCategory)
        assertEquals(listOf("video.example" to 15L, "news.example" to 5L), w.topSites)
        assertEquals("ublock", db.readableDatabase.rawQuery("SELECT DISTINCT category FROM tally WHERE site = 'news.example'", null)
            .use { it.moveToFirst(); it.getString(0) })
        // Same retention and clearing rules as Gecko's categories
        db.record("old.example", TrackerCategory.UBLOCK, day = today - TrackerTally.RETENTION_DAYS)
        db.rows(Long.MIN_VALUE / 2, today)
        db.prune(today)
        assertTrue(db.rows(Long.MIN_VALUE / 2, today).none { it.site == "old.example" })
        db.clear()
        assertTrue(db.weekly(today).isEmpty)
    }

    @Test fun ignoresEmptySitesAndNonPositiveCounts() {
        db.record("", TrackerCategory.ADS, day = today)
        db.record("a.com", TrackerCategory.ADS, count = 0, day = today)
        db.record("a.com", TrackerCategory.ADS, count = -5, day = today)
        assertTrue(db.weekly(today).isEmpty)
    }

    @Test fun schemaHoldsNoUrlsOrTimestamps() {
        val cols = db.readableDatabase.rawQuery("PRAGMA table_info(tally)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertEquals(listOf("day", "site", "category", "count"), cols)
        val site = TrackerTally.siteToRecord(false, "https://www.example.com/account/123?token=abc")!!
        db.record(site, TrackerCategory.FINGERPRINTERS, day = today)
        val stored = db.rows(today, today).single()
        assertEquals("example.com", stored.site)
        assertFalse(stored.site.contains("account") || stored.site.contains("token"))
    }
}
