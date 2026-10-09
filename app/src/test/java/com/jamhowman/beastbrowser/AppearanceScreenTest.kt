package com.jamhowman.beastbrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.core.content.edit
import com.jamhowman.beastbrowser.data.AppIcon
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.ui.AppearanceFragment
import com.jamhowman.beastbrowser.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

/** Settings > Appearance (2.8): tapping a swatch card stores the preset; also renders the screen for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class AppearanceScreenTest {
    private val out = File(System.getProperty("preview.dir") ?: "build/previews").apply { mkdirs() }

    private fun open(): Pair<SettingsActivity, AppearanceFragment> {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val f = AppearanceFragment()
        activity.supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return activity to f
    }

    private fun grid(f: AppearanceFragment) = f.requireView().findViewById<GridLayout>(R.id.presetGrid)

    private fun render(activity: SettingsActivity, name: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val w = 1080; val h = 2900 // a little taller than a phone so the realm tiles show too
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { root.draw(this) }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tappingACardStoresThePreset() {
        Prefs.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        Prefs.sp.edit(commit = true) { clear() }
        val (activity, f) = open()
        assertEquals(8, grid(f).childCount)
        assertTrue("Blood Moon is selected by default", grid(f).getChildAt(0).isSelected)
        render(activity, "appearance_blood_moon.png")

        grid(f).getChildAt(ThemePreset.presets.indexOf(ThemePreset.FROST)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("cyan", Prefs.sp.getString("accent", null))
        assertSame(ThemePreset.FROST, Prefs.theme)
        assertTrue("grid rebinds with the new selection", grid(f).getChildAt(1).isSelected)
        assertTrue(!grid(f).getChildAt(0).isSelected)
        render(activity, "appearance_frost.png")

        // Work tile shows AUTO → Ember now that the theme is Frost; Ghost is fixed (not clickable).
        val realms = f.requireView().findViewById<ViewGroup>(R.id.realmRow)
        assertEquals(3, realms.childCount)
        assertTrue(realms.getChildAt(1).contentDescription.contains("Ember"))
        assertTrue(!realms.getChildAt(2).isClickable)
        assertSame(ThemePreset.EMBER, Prefs.accentFor(Realm.WORK))
    }

    @Test fun lightModeUsesTheLightTokens() {
        Prefs.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        Prefs.sp.edit(commit = true) { clear(); putString("ui_theme", "light"); putString("accent", "cyan") }
        // Light for this activity only: the app-wide default night mode is static and would leak into other tests.
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        controller.get().delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        val activity = controller.setup().get()
        val f = AppearanceFragment()
        activity.supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!com.jamhowman.beastbrowser.data.AppTheme.isNight(activity))
        val mode = f.requireView().findViewById<ViewGroup>(R.id.modeRow)
        assertTrue("Light is the selected segment", mode.getChildAt(1).isSelected)
        render(activity, "appearance_frost_light.png")
    }

    @Test fun animatedWolfSwitchStoresThePref() {
        Prefs.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        Prefs.sp.edit(commit = true) { clear() }
        val (_, f) = open()
        val sw = f.requireView().findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.wolfSwitch)
        assertTrue("on by default", sw.isChecked && Prefs.wolfAnimation)
        f.requireView().findViewById<View>(R.id.wolfRow).performClick()
        assertTrue(!sw.isChecked && !Prefs.wolfAnimation)
        assertEquals(f.getString(R.string.appearance_wolf_off),
            f.requireView().findViewById<android.widget.TextView>(R.id.wolfSummary).text.toString())
    }

    @Test fun appIconGridShowsNineChoicesAndStoresThePick() {
        Prefs.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        Prefs.sp.edit(commit = true) { clear() }
        val (_, f) = open()
        val grid = f.requireView().findViewById<GridLayout>(R.id.iconGrid)
        assertEquals(AppIcon.entries.size, grid.childCount)
        assertTrue("Default is selected by default", grid.getChildAt(0).isSelected)
        grid.getChildAt(AppIcon.entries.indexOf(AppIcon.GOLD)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("gold", Prefs.appIconKey)
        assertSame(AppIcon.GOLD, AppIcon.from(Prefs.appIconKey))
        assertTrue(grid.getChildAt(AppIcon.entries.indexOf(AppIcon.GOLD)).isSelected)
        assertTrue(!grid.getChildAt(0).isSelected)
    }
}
