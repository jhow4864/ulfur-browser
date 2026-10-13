package com.jamhowman.beastbrowser

import android.app.Application
import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.ThemePacks
import com.jamhowman.beastbrowser.data.AppIcon
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Stats
import com.jamhowman.beastbrowser.data.UiTheme

/**
 * Note: GeckoView content processes are services of this app, so this runs in them too.
 * Keep it light; the GeckoRuntime is created lazily by MainActivity (main process only).
 */
class BeastApp : Application(), androidx.work.Configuration.Provider {
    /** 2.11.2: on-demand WorkManager init (the startup-provider initializer is removed in the manifest). */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        // 2.11.2: the launch is counted on the very first line, before super.onCreate() and anything else, and the
        // count itself can never throw (safe start must see every crashing launch).
        val main = try { isMainProcess() } catch (_: Throwable) { true }
        if (main) try { SafeStart.begin(this, startedForActivity()) } catch (_: Throwable) { }
        super.onCreate()
        if (main) try { registerActivityLifecycleCallbacks(LaunchCallbacks) } catch (_: Throwable) { }
        Prefs.init(this)
        ThemePacks.init(this)
        runCatching { AppIcon.sync(this) } // 2.8: keep activity-alias state in line with the stored icon
        CrashReporter.install(this) // 2.5.1: opt-in, records nothing unless Settings › Crash reports is on
        Stats.init()
        UiTheme.apply()
        if (main) runCatching { com.jamhowman.beastbrowser.filters.FilterAutoUpdate.schedule(this) } // 2.11 weekly lists
    }

    /** 2.9.1: started to show an activity (vs. widget update / broadcast / job), judged at the start of onCreate. */
    private fun startedForActivity(): Boolean = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (_: Throwable) { false }

    /**
     * 2.9.1: counts a deferred launch when the first activity is created, and marks the launch stable after
     * [com.jamhowman.beastbrowser.crash.LaunchGuard.STABLE_MS] with any activity resumed, or when one is stopped
     * normally (MainActivity also marks it on the first page load).
     */
    private object LaunchCallbacks : ActivityLifecycleCallbacks {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val stable = Runnable { stableNow() }
        private fun stableNow() = Thread({ SafeStart.markStable() }, "launch-guard").start()
        override fun onActivityPreCreated(a: android.app.Activity, b: android.os.Bundle?) = SafeStart.onFirstActivity()
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = SafeStart.onFirstActivity()
        override fun onActivityResumed(a: android.app.Activity) { handler.removeCallbacks(stable); handler.postDelayed(stable, com.jamhowman.beastbrowser.crash.LaunchGuard.STABLE_MS) }
        override fun onActivityPaused(a: android.app.Activity) = handler.removeCallbacks(stable)
        override fun onActivityStopped(a: android.app.Activity) { if (!a.isChangingConfigurations) stableNow() }
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    }

    private fun isMainProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else try {
            java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        } catch (_: Throwable) { packageName }
        return name == packageName
    }
}
