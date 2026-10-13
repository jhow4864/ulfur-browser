package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.filters.FilterAutoUpdate
import com.jamhowman.beastbrowser.filters.FilterAutoUpdate.Conditions
import com.jamhowman.beastbrowser.filters.FilterAutoUpdate.Gate
import com.jamhowman.beastbrowser.filters.FilterFetcher
import com.jamhowman.beastbrowser.filters.FilterRules
import com.jamhowman.beastbrowser.filters.FilterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** 2.11 weekly filter-list auto-update, including the Apk Tester checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterAutoUpdateTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var dir: File
    private var clock = 1_000_000L
    private val src = File("src/main")

    @Before fun setUp() {
        Prefs.init(ctx); Prefs.sp.edit().clear().commit()
        dir = File(ctx.filesDir, "fau-${System.nanoTime()}").apply { mkdirs() }
    }

    private fun store() = FilterStore(dir) { clock }
    private val wifi = Conditions(metered = false, powerSave = false, dataSaver = false)

    @Test fun onByDefaultWifiOnlyByDefault() {
        assertTrue(FilterAutoUpdate.enabled(Prefs.sp))
        assertFalse(FilterAutoUpdate.allowMobile(Prefs.sp))
    }

    @Test fun constraintsUnmeteredUnlessMobileAllowedAndBatteryNotLow() {
        val w = FilterAutoUpdate.constraints(allowMobile = false)
        assertEquals(NetworkType.UNMETERED, w.requiredNetworkType)
        assertTrue(w.requiresBatteryNotLow())
        val m = FilterAutoUpdate.constraints(allowMobile = true)
        assertEquals(NetworkType.CONNECTED, m.requiredNetworkType)
        assertTrue(m.requiresBatteryNotLow())
    }

    @Test fun check_noRunOnMeteredWhileWifiOnly() {
        assertTrue(FilterAutoUpdate.gate(true, false, wifi.copy(metered = true)) is Gate.Skip)
        assertEquals(Gate.Run, FilterAutoUpdate.gate(true, true, wifi.copy(metered = true)))
        assertEquals(Gate.Run, FilterAutoUpdate.gate(true, false, wifi))
    }

    @Test fun check_noRunInBatterySaverOrDataSaver() {
        for (mobile in listOf(false, true)) {
            assertTrue(FilterAutoUpdate.gate(true, mobile, wifi.copy(powerSave = true)) is Gate.Skip)
            assertTrue(FilterAutoUpdate.gate(true, mobile, wifi.copy(dataSaver = true)) is Gate.Skip)
        }
        assertTrue(FilterAutoUpdate.gate(false, true, wifi) is Gate.Skip)
    }

    @Test fun workerChecksGateBeforeFetching() {
        val w = File(src, "java/com/jamhowman/beastbrowser/filters/FilterAutoUpdate.kt").readText()
        val job = w.substringAfter("class Job(")
        assertTrue(job.indexOf("gate(") in 0 until job.indexOf("updateBlocking"))
        assertTrue(job.contains("FilterFetcher.fetch(it)"))
        assertTrue(job.contains("conditions(applicationContext)"))
    }

    @Test fun check_failedUpdateKeepsOldRulesAndNudgeStays() {
        val s = store()
        val id = (s.add("https://lists.example/a.txt") as FilterStore.AddResult.Added).list.id
        s.update(id) { "##.ad\n##.banner" }
        val before = s.rulesOf(id)
        assertEquals(2, before.size)
        clock += 8 * FilterStore.DAY_MS
        val o = FilterAutoUpdate.runOnce(s) { throw IOException("HTTP 503") }
        assertEquals(1, o.failed); assertEquals(0, o.updated)
        assertEquals(before, s.rulesOf(id))
        assertNotNull(s.lists.single().error)
        assertNotNull("7-day nudge still shows", s.staleDays())
        FilterAutoUpdate.record(Prefs.sp, clock, o)
        assertNotNull(Prefs.sp.getString(FilterAutoUpdate.KEY_LAST_ERROR, null))
        // A later good run clears the error and the nudge.
        val ok = FilterAutoUpdate.runOnce(s) { "##.ad" }
        assertEquals(1, ok.updated)
        FilterAutoUpdate.record(Prefs.sp, clock, ok)
        assertNull(Prefs.sp.getString(FilterAutoUpdate.KEY_LAST_ERROR, null))
        assertNull(s.staleDays())
        assertEquals(clock, Prefs.sp.getLong(FilterAutoUpdate.KEY_LAST, 0))
    }

    @Test fun onlyEnabledListsAreUpdated() {
        val s = store()
        val a = (s.add("https://lists.example/a.txt") as FilterStore.AddResult.Added).list.id
        val b = (s.add("https://lists.example/b.txt") as FilterStore.AddResult.Added).list.id
        s.setEnabled(b, false)
        val seen = mutableListOf<String>()
        FilterAutoUpdate.runOnce(s) { seen += it; "##.x" }
        assertEquals(listOf("https://lists.example/a.txt"), seen)
        assertTrue(s.rulesOf(a).isNotEmpty())
    }

    @Test fun check_downloadsStayHttpsOnlyWithinCaps() {
        try { FilterFetcher.fetch("http://lists.example/a.txt"); fail() } catch (_: IOException) {}
        assertNotNull(FilterRules.checkListUrl("http://lists.example/a.txt"))
        try { FilterFetcher.readCapped(ByteArrayInputStream(ByteArray(2048)), 1024); fail() } catch (_: FilterFetcher.TooLargeException) {}
        assertEquals(10, FilterFetcher.readCapped(ByteArrayInputStream(ByteArray(10)), 1024).length)
        // Same path as manual updates: updateBlocking -> runOnce -> FilterStore.update (https re-check, keep old).
        val f = File(src, "java/com/jamhowman/beastbrowser/filters/Filters.kt").readText()
        assertTrue(f.substringAfter("fun updateBlocking").contains("FilterAutoUpdate.runOnce(s, fetch)"))
        assertTrue(File(src, "java/com/jamhowman/beastbrowser/filters/FilterAutoUpdate.kt").readText().contains("store.update(l.id, fetch)"))
        // A stored list whose URL is no longer https is refused by update itself, old rules kept.
        val s = store()
        val id = (s.add("https://lists.example/a.txt") as FilterStore.AddResult.Added).list.id
        s.update(id) { "##.ad" }
        val json = File(dir, "filters.json")
        json.writeText(json.readText().replace("https:", "http:"))
        val s2 = store()
        var fetched = false
        val o = FilterAutoUpdate.runOnce(s2) { fetched = true; "##.new" }
        assertFalse("http list never fetched", fetched)
        assertEquals(0, o.updated)
        // Either dropped on load or refused by update; if kept, its old rules are untouched.
        s2.lists.firstOrNull { it.id == id }?.let { assertEquals(listOf("##.ad"), s2.rulesOf(id)) }
    }

    @Test fun check_noNewPermissionsInManifest() {
        val perms = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(File(src, "AndroidManifest.xml").readText())
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf(
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            // 2.12.1 (Jam approved): per-site camera / mic / location, requested only when an allowed site asks.
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.REQUEST_INSTALL_PACKAGES",
        ), perms)
    }

    @Test fun settingsHasToggleMobileAndLastRun() {
        val a = File(src, "java/com/jamhowman/beastbrowser/ui/FiltersActivity.kt").readText()
        listOf("R.string.filters_auto,", "R.string.filters_auto_mobile", "KEY_LAST", "FilterAutoUpdate.schedule(this)", "store.staleDays()")
            .forEach { assertTrue(it, a.contains(it)) }
        assertTrue(File(src, "java/com/jamhowman/beastbrowser/BeastApp.kt").readText().contains("FilterAutoUpdate.schedule(this)"))
    }
}
