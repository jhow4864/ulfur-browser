package com.jamhowman.beastbrowser

import android.app.Application
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.backup.BackupManager
import com.jamhowman.beastbrowser.crash.CrashReport
import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.5.1: the uncaught-exception handler is opt-in, chained, local-only and scrubbed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReporterTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private var original: Thread.UncaughtExceptionHandler? = null
    private val seen = mutableListOf<Throwable>()

    @Before fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        // Stand-in for Android's own handler (which would kill the process).
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        CrashReporter.install(ctx)
        CrashReporter.store(ctx).deleteAll()
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        CrashReporter.store(ctx).deleteAll()
        Prefs.sp.edit(commit = true) { remove("crash_reports") }
    }

    private fun crash(e: Throwable) = Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), e)

    @Test fun offByDefaultRecordsNothingButStillChains() {
        assertFalse(Prefs.crashReports)
        val e = IllegalStateException("boom")
        crash(e)
        assertEquals(listOf<Throwable>(e), seen)
        assertEquals(0, CrashReporter.store(ctx).count())
    }

    @Test fun optedInSavesAScrubbedReportInFilesDirAndChains() {
        Prefs.sp.edit(commit = true) { putBoolean("crash_reports", true) }
        val e = IllegalStateException("Couldn't open https://secret.example.com/inbox?u=jane")
        crash(e)
        assertSame(e, seen.single())
        val store = CrashReporter.store(ctx)
        assertEquals(File(ctx.filesDir, "crash-reports").canonicalPath, store.dir.canonicalPath)
        val entry = store.reports().single()
        val r = entry.report
        assertEquals(CrashReport.Kind.APP, r.kind)
        assertEquals(BuildConfig.VERSION_NAME, r.appVersion)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), r.versionCode)
        assertEquals(34, r.api)
        assertEquals(Thread.currentThread().name, r.thread)
        assertEquals("java.lang.IllegalStateException: Couldn't open <url>", r.exception)
        val text = entry.file.readText()
        assertFalse(text, text.contains("secret.example.com"))
        assertFalse(text, text.contains("jane"))
        assertTrue(text.contains("\tat com.jamhowman.beastbrowser.CrashReporterTest."))
    }

    @Test fun installIsIdempotent() {
        val h = Thread.getDefaultUncaughtExceptionHandler()
        CrashReporter.install(ctx)
        assertSame(h, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun contentCrashesRecordNothingAboutTheTab() {
        CrashReporter.recordContentCrash() // off: nothing
        Prefs.sp.edit(commit = true) { putBoolean("crash_reports", true) }
        CrashReporter.recordContentCrash()
        val store = CrashReporter.store(ctx)
        val deadline = System.currentTimeMillis() + 5_000
        while (store.count() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20) // written off the UI thread
        val r = store.reports().single().report
        assertEquals(CrashReport.Kind.CONTENT, r.kind)
        assertEquals("", r.trace)
    }

    @Test fun optInIsOffInSettingsAndNeverBackedUp() {
        val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val prefs = File(main, "res/xml/preferences.xml").readText()
        assertTrue(Regex("""app:key="crash_reports" app:defaultValue="false"""").containsMatchIn(prefs))
        assertFalse("crash_reports" in BackupManager.SETTINGS)
        // GeckoView's own crash reporter (uploads to Mozilla) stays off.
        val engine = File(main, "java/com/jamhowman/beastbrowser/browser/Engine.kt").readText()
        assertFalse(engine.contains(".crashHandler("))
    }
}
