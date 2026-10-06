package com.jamhowman.beastbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Roadmap 10 static guards: the tally stays on the phone, and private tabs never reach it. */
class TrackerTallyPrivacyTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, "java/com/jamhowman/beastbrowser/$path").readText()

    @Test fun tallyDbExcludedFromBackupAndDeviceTransfer() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(main, "res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val sec = doc.getElementsByTagName(section).item(0) as Element
            val ex = sec.getElementsByTagName("exclude").let { l -> (0 until l.length).map { l.item(it) as Element } }
            assertTrue(section, ex.any { it.getAttribute("domain") == "database" && it.getAttribute("path") == "tracker_tally.db" })
        }
        assertTrue(src("data/TrackerTallyDb.kt").contains("const val NAME = \"tracker_tally.db\""))
        assertFalse("not part of user backups", src("backup/BackupManager.kt").contains("TrackerTally"))
    }

    @Test fun tallyCodeHasNoNetworkAccess() {
        val code = src("data/TrackerTally.kt") + src("data/TrackerTallyDb.kt") + src("ui/TrackerTallyFragment.kt")
        for (api in listOf("HttpURLConnection", "openConnection", "java.net.URL", "Socket", "GeckoWebExecutor", "okhttp", "Intent.ACTION_SEND")) {
            assertFalse(api, code.contains(api))
        }
    }

    @Test fun privateTabsAreGatedBeforeTheStore() {
        val cb = src("browser/TabCallbacks.kt")
        assertTrue(cb.contains("val private = tab.isPrivate || session.settings.usePrivateMode"))
        assertTrue(cb.contains("TrackerTally.siteToRecord(private, tab.url)"))
        // The store is only written through BrowserHost.onTrackerBlocked, which TabCallbacks calls after the gate.
        val ui = src("ui/MainActivity.kt")
        assertTrue(Regex("""tally\.record\(""").findAll(ui).count() == 1)
        assertTrue(ui.contains("override fun onTrackerBlocked(tab: Tab, site: String, category: TrackerCategory, count: Int) {\n        tally.record(site, category, count.toLong())"))
        // uBlock Origin's line goes through the same private-tab gate before onTrackerBlocked.
        assertTrue(ui.contains("TrackerTally.siteToRecord(tab.isPrivate || tab.session.settings.usePrivateMode, tab.url) ?: return\n        onTrackerBlocked(tab, site, TrackerCategory.UBLOCK, gained)"))
        assertTrue(Regex("""onTrackerBlocked\(""").findAll(ui).count() == 2) // the override + the uBO path
    }

    @Test fun uboTallyReusesTheAllTimeCountingRule() {
        val ui = src("ui/MainActivity.kt")
        // One rule (UboBadge.newBlocks), one memory (Tab.uboCounted): the tally gets the very number the total got.
        assertTrue(ui.contains("val gained = UboBadge.newBlocks(tab.uboCounted, n)\n                Stats.total.addAndGet(gained.toLong())"))
        assertTrue(ui.contains("tab.uboCounted = n\n                tallyUbo(tab, gained)"))
        assertEquals(1, Regex("""UboBadge\.newBlocks\(""").findAll(ui).count())
        assertFalse("no second uBO counting rule", src("data/TrackerTally.kt").contains("fun uboIncrease"))
        assertFalse("no second uBO memory on Tab", src("browser/Tab.kt").contains("uboTallied"))
    }
}
