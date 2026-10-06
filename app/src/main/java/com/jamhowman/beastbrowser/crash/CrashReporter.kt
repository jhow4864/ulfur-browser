package com.jamhowman.beastbrowser.crash

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.jamhowman.beastbrowser.BuildConfig
import com.jamhowman.beastbrowser.data.Prefs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 2.5.1: opt-in, on-device crash reporter. Off by default ([Prefs.crashReports]); while off nothing is recorded.
 *
 * - Our own default uncaught-exception handler, chained to the previous one (Android's, which shows the
 *   "app has stopped" dialog and kills the process), installed by [com.jamhowman.beastbrowser.BeastApp].
 * - GeckoView content process crashes are noted from TabCallbacks.onCrash (no stack is available for those).
 * - GeckoView's own crash reporter (GeckoRuntimeSettings.crashHandler, which uploads to Mozilla) stays off.
 *
 * Reports only ever go to filesDir/crash-reports/ ([CrashStore]); nothing is sent anywhere. The next launch offers
 * to view, delete or report one on GitHub (ui/CrashReportUi.kt), which just opens a pre-filled issue page.
 */
object CrashReporter {
    const val DIR = "crash-reports"
    private const val CONTENT_CRASH = "GeckoView content process crashed"

    @Volatile private var appContext: Context? = null

    fun store(context: Context) = CrashStore(File(context.applicationContext.filesDir, DIR))

    /** Call once from Application.onCreate, after [Prefs.init]. Safe to call again (no double chaining). */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(previous))
    }

    private class Handler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        private val handling = AtomicBoolean(false)

        override fun uncaughtException(t: Thread, e: Throwable) {
            // Record at most once per process, and never let recording get in the way of the normal crash path.
            if (handling.compareAndSet(false, true)) {
                try { recordAppCrash(t, e) } catch (_: Throwable) { }
            }
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                // Android always has a previous handler (it kills the process); this is for plain JVMs (tests),
                // where ThreadGroup.uncaughtException would just call us again.
                e.printStackTrace()
                try { Process.killProcess(Process.myPid()) } catch (_: Throwable) { }
            }
        }
    }

    private fun enabled(): Boolean = try { Prefs.crashReports } catch (_: Throwable) { false }

    private fun recordAppCrash(t: Thread, e: Throwable) {
        if (!enabled()) return
        val ctx = appContext ?: return
        val trace = CrashFormat.traceOf(e)
        store(ctx).save(report(CrashReport.Kind.APP, CrashFormat.scrub(t.name), CrashFormat.summary(trace), trace))
    }

    /** A tab's content process crashed. Records nothing about the tab (no URL, title, or private/normal). */
    fun recordContentCrash() {
        if (!enabled()) return
        val ctx = appContext ?: return
        val r = report(CrashReport.Kind.CONTENT, "", CONTENT_CRASH, "")
        // Called on the UI thread; the write is tiny but keep disk work off it anyway.
        Thread({ try { store(ctx).save(r) } catch (_: Throwable) { } }, "crash-report").start()
    }

    private fun report(kind: CrashReport.Kind, thread: String, exception: String, trace: String) = CrashReport(
        time = System.currentTimeMillis(),
        kind = kind,
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        android = Build.VERSION.RELEASE.orEmpty(),
        api = Build.VERSION.SDK_INT,
        device = deviceName(),
        gecko = geckoVersion(),
        process = processName(),
        thread = thread,
        exception = exception,
        trace = trace,
    )

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (maker.isEmpty() || model.startsWith(maker, ignoreCase = true)) model
        else maker.replaceFirstChar { it.uppercase() } + " " + model
    }

    private fun geckoVersion(): String = try { org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION } catch (_: Throwable) { "" }

    /** "com.jamhowman.beastbrowser" or a Gecko child process (":tab0", ":gpu"…). */
    private fun processName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName().orEmpty() else ""
}
