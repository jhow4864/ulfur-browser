package com.jamhowman.beastbrowser.data

import androidx.annotation.StringRes
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.util.Domains
import org.mozilla.geckoview.ContentBlocking
import java.time.LocalDate

/**
 * Roadmap 10: what kind of tracker was blocked. [key] is stored in tracker_tally.db; don't change it.
 * Gecko's blocks are derived from a [ContentBlocking.BlockEvent]'s category flags ([from]), most specific first.
 * [UBLOCK] is uBlock Origin's own line: it only reports a per-page number, so its blocks have no finer type.
 */
enum class TrackerCategory(val key: String, @StringRes val label: Int) {
    ADS("ads", R.string.tally_cat_ads),
    ANALYTICS("analytics", R.string.tally_cat_analytics),
    SOCIAL("social", R.string.tally_cat_social),
    FINGERPRINTERS("fingerprinters", R.string.tally_cat_fingerprinters),
    CRYPTOMINERS("cryptominers", R.string.tally_cat_cryptominers),
    EMAIL("email", R.string.tally_cat_email),
    CONTENT("content", R.string.tally_cat_content),
    COOKIES("cookies", R.string.tally_cat_cookies),
    OTHER("other", R.string.tally_cat_other),
    /** Increase of uBlock Origin's per-page badge ([TrackerTally.uboIncrease]); never returned by [from]. */
    UBLOCK("ublock", R.string.tally_cat_ublock);

    companion object {
        fun fromKey(key: String?): TrackerCategory = entries.firstOrNull { it.key == key } ?: OTHER

        /** From BlockEvent.getAntiTrackingCategory() / getCookieBehaviorCategory() (bit flags, may combine). */
        fun from(antiTracking: Int, cookieBehavior: Int): TrackerCategory {
            fun has(flag: Int) = antiTracking and flag != 0
            return when {
                has(ContentBlocking.AntiTracking.FINGERPRINTING) -> FINGERPRINTERS
                has(ContentBlocking.AntiTracking.CRYPTOMINING) -> CRYPTOMINERS
                has(ContentBlocking.AntiTracking.SOCIAL) || has(ContentBlocking.AntiTracking.STP) -> SOCIAL
                has(ContentBlocking.AntiTracking.EMAIL) -> EMAIL
                has(ContentBlocking.AntiTracking.AD) -> ADS
                has(ContentBlocking.AntiTracking.ANALYTIC) -> ANALYTICS
                has(ContentBlocking.AntiTracking.CONTENT) -> CONTENT
                cookieBehavior != 0 -> COOKIES
                else -> OTHER
            }
        }
    }
}

/** One stored row: [count] blocks of [category] on pages of [site] during local day [day] (epoch day). */
data class TallyRow(val day: Long, val site: String, val category: TrackerCategory, val count: Long)

/** Last [TrackerTally.WEEK_DAYS] days (today included) of the tally. */
data class WeeklySummary(
    val total: Long,
    /** Top [TrackerTally.TOP_SITES] sites, most blocked first. */
    val topSites: List<Pair<String, Long>>,
    /** Every category with blocks this week, most first. */
    val byCategory: List<Pair<TrackerCategory, Long>>,
    val fromDay: Long,
    val toDay: Long,
) {
    val isEmpty: Boolean get() = total == 0L
}

/**
 * Rules for the on-device weekly tracker tally (roadmap 10). Pure, so it's unit-tested without Android.
 * Only the host of the top-level page is ever kept (never a URL or path), never from private tabs, and
 * nothing here (or in [TrackerTallyDb]) talks to the network.
 */
object TrackerTally {
    /** Rows older than this many days are pruned (~8 weeks). */
    const val RETENTION_DAYS = 56
    const val WEEK_DAYS = 7
    const val TOP_SITES = 5
    private const val MAX_HOST = 253

    fun today(): Long = LocalDate.now().toEpochDay()

    /**
     * Site key for a top-level page: lower-case host without "www.", or null if the page isn't a web page
     * (Speed Dial, about:, extension and reader pages) or has no host. Never returns anything past the host.
     */
    fun siteOf(pageUrl: String?): String? {
        if (pageUrl == null) return null
        val scheme = pageUrl.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = pageUrl.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        val site = Domains.display(host).trimEnd('.')
        return site.takeIf { it.isNotEmpty() && it.length <= MAX_HOST }
    }

    /** The only gate in front of the store: private (and Ghost) tabs are never recorded. */
    fun siteToRecord(isPrivate: Boolean, pageUrl: String?): String? = if (isPrivate) null else siteOf(pageUrl)

    /**
     * Blocks to add when uBlock Origin's per-page badge for a tab goes from [previous] (the last value already
     * counted) to [badge]. Within a page the badge only climbs, so a rise counts the difference and the same
     * number again (repeated updates, late updates for the previous page) counts nothing. A drop means uBO
     * started a new page or a reload, which begins at zero, so the new number counts in full. Never negative.
     */
    fun uboIncrease(previous: Int, badge: Int): Int = when {
        badge <= 0 -> 0
        badge >= previous -> badge - previous
        else -> badge
    }

    /** Rows dated on or before this day are pruned. */
    fun pruneBefore(today: Long): Long = today - RETENTION_DAYS

    fun weekStart(today: Long): Long = today - (WEEK_DAYS - 1)

    /** Aggregates [rows] (any range; only the week ending [today] counts). */
    fun weekly(rows: List<TallyRow>, today: Long): WeeklySummary {
        val from = weekStart(today)
        val week = rows.filter { it.day in from..today && it.count > 0 }
        val bySite = HashMap<String, Long>()
        val byCat = HashMap<TrackerCategory, Long>()
        for (r in week) {
            bySite.merge(r.site, r.count, Long::plus)
            byCat.merge(r.category, r.count, Long::plus)
        }
        return WeeklySummary(
            total = week.sumOf { it.count },
            topSites = bySite.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(TOP_SITES).map { it.key to it.value },
            byCategory = byCat.entries
                .sortedWith(compareByDescending<Map.Entry<TrackerCategory, Long>> { it.value }.thenBy { it.key.ordinal })
                .map { it.key to it.value },
            fromDay = from,
            toDay = today,
        )
    }
}
