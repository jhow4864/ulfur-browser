package com.jamhowman.beastbrowser.ui

import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.ThemePalette

/**
 * 2.8: keeps a secondary screen on the user's theme. Construct it first thing in `onCreate`, before
 * `super.onCreate()`: it applies the realm's preset overlay (plus the private-mode override when [private]),
 * sets the system bars once the window exists, and recreates the activity on resume when the palette changed
 * meanwhile (theme picked in Settings, realm switched, an override set or cleared).
 *
 * MainActivity doesn't use this: it re-tints in place (recreating it would tear down Gecko sessions).
 */
class ThemedScreen(
    private val activity: AppCompatActivity,
    private val private: Boolean = Prefs.realm.alwaysPrivate,
) : DefaultLifecycleObserver {

    var palette: ThemePalette = resolve()
        private set

    init {
        AppTheme.applyOverlays(activity.theme, palette)
        activity.lifecycle.addObserver(this)
    }

    private fun resolve() = AppTheme.palette(activity, Prefs.realm, private)

    override fun onCreate(owner: LifecycleOwner) = applySystemBars(activity.window, palette)

    override fun onResume(owner: LifecycleOwner) {
        if (resolve() != palette) activity.recreate()
    }

    /**
     * In-place switch (Settings > Appearance): applies the new palette's overlays and bars without recreating.
     * Views inflated earlier keep their colours, so the caller re-tints them. True if the palette changed.
     */
    fun refresh(): Boolean {
        val p = resolve()
        if (p == palette) return false
        palette = p
        AppTheme.applyOverlays(activity.theme, p)
        applySystemBars(activity.window, p)
        return true
    }
}

/** Android 8.0 (API 26) can't draw dark navigation bar icons; `windowLightNavigationBar` is API 27+. */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O_MR1)
fun lightNavIconsSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

/**
 * System bars for [palette] (2.8). Both bars show [ThemePalette.systemBars] (set by a private-mode override, item 11)
 * or `@color/bg`, the pre-2.8 look; the status bar stays transparent then (edge-to-edge, the screen draws behind it).
 * Icons are dark on light bars, except the API 26 navigation bar, which stays black like themes.xml has it.
 */
@Suppress("DEPRECATION") // bar colours: still honoured below API 35 and on 3-button navigation
fun applySystemBars(window: Window, palette: ThemePalette) {
    val bars = palette.systemBars ?: window.context.getColor(R.color.bg)
    val light = ColorUtils.calculateLuminance(bars) > 0.5
    window.statusBarColor = palette.systemBars ?: Color.TRANSPARENT
    window.navigationBarColor = if (light && !lightNavIconsSupported()) Color.BLACK else bars
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light && lightNavIconsSupported()
    }
}
