package com.jamhowman.beastbrowser

import android.app.Application
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
        Stats.init()
        UiTheme.apply()
    }
}
