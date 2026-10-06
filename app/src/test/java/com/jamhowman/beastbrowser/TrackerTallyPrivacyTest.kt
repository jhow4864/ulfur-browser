package com.jamhowman.beastbrowser

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
        assertTrue(ui.contains("override fun onTrackerBlocked(tab: Tab, site: String, category: TrackerCategory) {\n        tally.record(site, category)"))
    }
}
