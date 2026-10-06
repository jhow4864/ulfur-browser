package com.jamhowman.beastbrowser

import android.app.Application
import com.jamhowman.beastbrowser.crash.CrashReporter
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
        Prefs.init(this)
        CrashReporter.install(this) // 2.5.1: opt-in, records nothing unless Settings › Crash reports is on
        Stats.init()
        UiTheme.apply()
    }
}
