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
class BeastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 2.9 safe start: count this launch first, before anything that could crash (main process only).
        val main = isMainProcess()
        if (main) try { SafeStart.begin(this, startedForActivity()); registerActivityLifecycleCallbacks(LaunchCallbacks) } catch (_: Throwable) { }
        Prefs.init(this)
        ThemePacks.init(this)
        AppIcon.sync(this) // 2.8: keep activity-alias state in line with the stored icon
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
