package com.jamhowman.beastbrowser

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.AppIcon
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.8 alternate app icons: Prefs round-trip and exactly one launcher component enabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clean() {
        Prefs.init(ctx)
        Prefs.sp.edit(commit = true) { clear() }
        val pm = ctx.packageManager
        AppIcon.entries.forEach { icon ->
            val state = if (icon == AppIcon.DEFAULT) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(icon.componentName(ctx), state, PackageManager.DONT_KILL_APP)
        }
    }

    @Test fun defaultKeyWhenUnset() {
        assertEquals("default", Prefs.appIconKey)
        assertSame(AppIcon.DEFAULT, AppIcon.from(Prefs.appIconKey))
        assertSame(AppIcon.DEFAULT, AppIcon.from(null))
        assertSame(AppIcon.DEFAULT, AppIcon.from("neon"))
    }

    @Test fun preferenceRoundTrip() {
        Prefs.appIconKey = AppIcon.FROST.key
        assertEquals("frost", Prefs.sp.getString("app_icon", null))
        assertSame(AppIcon.FROST, AppIcon.from(Prefs.appIconKey))

        Prefs.appIconKey = AppIcon.DEFAULT.key
        assertEquals("default", Prefs.appIconKey)
    }

    @Test fun themeMapMatchesDesignerAssets() {
        assertSame(ThemePreset.BLOOD_MOON, AppIcon.BLOOD_MOON.theme)
        assertSame(ThemePreset.FROST, AppIcon.FROST.theme)
        assertSame(ThemePreset.TOXIC, AppIcon.TOXIC.theme)
        assertSame(ThemePreset.EMBER, AppIcon.EMBER.theme)
        assertSame(ThemePreset.VOID, AppIcon.FULL_MOON.theme)
        assertSame(ThemePreset.GOLD, AppIcon.GOLD.theme)
        assertSame(ThemePreset.SAKURA, AppIcon.SAKURA.theme)
        assertSame(ThemePreset.ASH, AppIcon.GHOST.theme)
        assertEquals(null, AppIcon.DEFAULT.theme)
        assertEquals(9, AppIcon.entries.size)
        assertEquals(8, AppIcon.entries.count { it.theme != null })
    }

    @Test fun applyEnablesExactlyOneLauncherComponent() {
        AppIcon.apply(ctx, AppIcon.SAKURA)
        assertEquals("sakura", Prefs.appIconKey)
        assertEnabled(AppIcon.SAKURA)
        AppIcon.entries.filter { it != AppIcon.SAKURA }.forEach { assertDisabled(it) }

        AppIcon.apply(ctx, AppIcon.DEFAULT)
        assertEquals("default", Prefs.appIconKey)
        assertEnabled(AppIcon.DEFAULT)
        AppIcon.entries.filter { it != AppIcon.DEFAULT }.forEach { assertDisabled(it) }
    }

    @Test fun syncRestoresStoredChoice() {
        Prefs.appIconKey = AppIcon.FULL_MOON.key
        AppIcon.sync(ctx)
        assertEnabled(AppIcon.FULL_MOON)
        AppIcon.entries.filter { it != AppIcon.FULL_MOON }.forEach { assertDisabled(it) }
    }

    private fun assertEnabled(icon: AppIcon) {
        val state = ctx.packageManager.getComponentEnabledSetting(icon.componentName(ctx))
        val ok = when (icon) {
            AppIcon.DEFAULT -> state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else -> state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        assertTrue("${icon.key} should be enabled (state=$state)", ok)
    }

    private fun assertDisabled(icon: AppIcon) {
        val state = ctx.packageManager.getComponentEnabledSetting(icon.componentName(ctx))
        assertEquals(
            "${icon.key} should be disabled",
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            state,
        )
    }
}
