package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.crash.LaunchGuard
import com.jamhowman.beastbrowser.crash.PrivateRedactor
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.SavedSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.9 safe start: launch-crash counting, the reset, restore-exactly and private data in crash reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeStartTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var file: File

    @Before fun setUp() {
        Prefs.init(ctx)
        Prefs.sp.edit().clear().commit()
        file = File(ctx.filesDir, "guard-test").also { it.delete() }
        SafeStart.reset(); PrivateRedactor.clear()
    }

    @After fun tearDown() { SafeStart.reset(); PrivateRedactor.clear(); file.delete() }

    /** A fresh guard each time = a fresh process reading what the last one left on disk. */
    private fun launch() = LaunchGuard(file).onLaunch()

    @Test fun oneLaunchCrashDoesNotTrigger() {
        launch()                       // crashed before stable
        assertFalse(launch().safeStart)
    }

    @Test fun twoConsecutiveLaunchCrashesTrigger() {
        launch(); launch()             // two crashes in a row, neither stable
        val l = launch()
        assertTrue(l.safeStart)
        assertEquals(2, l.previousUnstable)
        assertFalse(l.crashedAfterRestore)
    }

    @Test fun crashAfterStableWindowDoesNotCount() {
        launch()                                        // launch crash #1
        launch(); LaunchGuard(file).markStable()        // stable, then crashed later: not a launch crash
        assertFalse(launch().safeStart)                 // launch crash #2 is not consecutive with #1
        assertFalse(launch().safeStart)                 // prev = 1 only
        assertTrue(launch().safeStart)                  // now two in a row
    }

    @Test fun cleanLaunchResetsCounter() {
        launch(); launch()
        LaunchGuard(file).markStable()
        assertEquals(LaunchGuard.State(), LaunchGuard(file).read())
        assertFalse(launch().safeStart)
    }

    @Test fun countIsOnDiskBeforeAnythingElseRuns() {
        // Application.onCreate dies right after onLaunch: the bump is already synced to the file.
        LaunchGuard(file).onLaunch()
        assertEquals(1, LaunchGuard(file).read().unstable)
        file.writeText("garbage")                       // a torn/corrupt file never crashes the guard itself
        assertEquals(LaunchGuard.State(), LaunchGuard(file).read())
    }

    @Test fun crashAgainAfterRestoreReturnsToSafeStartAndSaysSo() {
        launch(); launch()
        val g = LaunchGuard(file)
        assertTrue(g.onLaunch().safeStart)
        g.markRestoring()                               // Restore everything, then died before stable
        val l = launch()
        assertTrue(l.safeStart); assertTrue(l.crashedAfterRestore)
    }

    @Test fun restoreThenStableClearsEverything() {
        launch(); launch()
        val g = LaunchGuard(file); g.onLaunch(); g.markRestoring(); g.markStable()
        assertFalse(launch().safeStart)
    }

    @Test fun safeStartPausesExtensionsForTheSessionOnly() {
        launch(); launch()
        SafeStart.begin(LaunchGuard(file))
        assertTrue(SafeStart.tabsPaused && SafeStart.extensionsPaused && SafeStart.themePaused && SafeStart.awaitingChoice)
        assertTrue(Prefs.ublockEnabled)                 // the user's setting is untouched
        assertFalse(Prefs.ublockActive)
        SafeStart.restore(tabs = false, extensions = true, theme = false)
        assertTrue(Prefs.ublockActive); assertTrue(SafeStart.tabsPaused); assertFalse(SafeStart.extensionsPaused)
    }

    @Test fun countingWorksWithCrashReportsOff() {
        assertFalse(Prefs.crashReports)
        launch(); launch()
        assertTrue(SafeStart.begin(LaunchGuard(file)).safeStart)
    }

    @Test fun restoreEverythingRestoresTabsExactly() {
        // Play: 4 tabs, groups, a parent link, 3rd selected. Work (another container) has its own session.
        Prefs.saveTabs(Realm.PLAY, "https://a.example/1\nbeast://home\nhttps://b.example/x?y=1\nhttps://c.example/",
            "g1\n\ng1\ng2", "\n0\n\n2", 2)
        Prefs.saveTabs(Realm.WORK, "https://work.example/", "", "", 0)
        val play = SavedSession.load(Realm.PLAY)
        val work = SavedSession.load(Realm.WORK)
        launch(); launch()
        SafeStart.begin(LaunchGuard(file))
        // While paused nothing reads or writes the saved sessions; afterwards they are exactly what was saved.
        SafeStart.restore()
        assertEquals(play, SavedSession.load(Realm.PLAY))
        assertEquals(work, SavedSession.load(Realm.WORK))
        assertEquals(listOf("https://a.example/1", "beast://home", "https://b.example/x?y=1", "https://c.example/"), play.tabs.map { it.url })
        assertEquals(listOf("g1", null, "g1", "g2"), play.tabs.map { it.group })
        assertEquals(listOf(null, 0, null, 2), play.tabs.map { it.parent })
        assertEquals(2, play.selected)
        // Round trip through the encoder gives back the stored strings byte for byte.
        val e = play.encode()
        assertEquals(Prefs.savedTabs(Realm.PLAY), e.tabs)
        assertEquals(Prefs.savedTabGroups(Realm.PLAY), e.groups)
        assertEquals(Prefs.savedTabParents(Realm.PLAY), e.parents)
        assertEquals(Prefs.savedTabIndex(Realm.PLAY), e.index)
        // Ghost (always private) never has a saved session to restore.
        assertTrue(SavedSession.load(Realm.GHOST).tabs.isEmpty() || !Realm.GHOST.persistsTabs)
    }

    @Test fun savedSessionSurvivesBlankLinesAndBadParents() {
        val s = SavedSession.parse("https://a/\n\nhttps://b/", "x\n\ny", "\n\n0", 5)
        assertEquals(listOf("https://a/", "https://b/"), s.tabs.map { it.url })
        assertEquals(listOf("x", "y"), s.tabs.map { it.group })
        assertEquals(listOf(null, 0), s.tabs.map { it.parent })
        assertEquals(1, s.selected)
    }

    @Test fun privateTabDataNeverReachesACrashReport() {
        val title = "Hospital results for Jam"
        val url = "https://secret-clinic.example/patient/42?token=abc"
        PrivateRedactor.register(url, title, "Private Diary")
        val e = IllegalStateException("Failed rendering '$title' at $url (Private Diary) host secret-clinic.example",
            RuntimeException("cause: $title"))
        val r = CrashReporter.appReport(Thread("tab $title"), e)
        val all = listOf(r.thread, r.exception, r.trace).joinToString("\n")
        listOf(title, url, "secret-clinic", "Private Diary", "token=abc", "patient/42").forEach {
            assertFalse("leaked '$it' in: $all", all.contains(it, ignoreCase = true))
        }
        assertTrue(all.contains("IllegalStateException"))
    }

    @Test fun redactorIgnoresTinyStringsAndClears() {
        PrivateRedactor.register("ab", null, "")
        assertEquals("ab cd", PrivateRedactor.redact("ab cd"))
        PrivateRedactor.register("Secret title")
        assertEquals("x ${PrivateRedactor.MASK} y", PrivateRedactor.redact("x secret TITLE y"))
        PrivateRedactor.clear()
        assertEquals("Secret title", PrivateRedactor.redact("Secret title"))
    }
}
