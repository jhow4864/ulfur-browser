package com.jamhowman.beastbrowser

import android.app.Application
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.backup.BackupManager
import com.jamhowman.beastbrowser.backup.BackupPayload
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.ThemeOverride
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.data.ThemePreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.8 theme selection: stored through Prefs (`accent` / `accent_work`), per-realm rules and the private-mode hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeSelectionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clean() {
        Prefs.init(ctx)
        Prefs.sp.edit(commit = true) { clear() }
        AppTheme.privateOverride = null
    }

    @After fun noOverride() { AppTheme.privateOverride = null }

    @Test fun freshInstallKeepsTheCurrentLook() {
        assertSame(ThemePreset.BLOOD_MOON, Prefs.theme)
        assertSame(ThemePreset.BLOOD_MOON, Prefs.accentFor(Realm.PLAY))
        assertSame("Work AUTO: Frost", ThemePreset.FROST, Prefs.accentFor(Realm.WORK))
        assertSame(ThemePreset.GHOST, Prefs.accentFor(Realm.GHOST))
        Realm.entries.forEach { assertFalse(Prefs.hasCustomAccent(it)) }
        assertNull("nothing is written until the user picks", Prefs.sp.getString("accent", null))
    }

    @Test fun pickedThemeIsStoredAndReadBack() {
        Prefs.setAccent(Realm.PLAY, ThemePreset.GOLD)
        assertEquals("gold", Prefs.sp.getString("accent", null))
        assertSame(ThemePreset.GOLD, Prefs.theme)

        Prefs.setAccent(Realm.PLAY, ThemePreset.FROST)
        assertEquals("legacy key for presets that replaced a 2.5 accent", "cyan", Prefs.sp.getString("accent", null))
        assertSame(ThemePreset.FROST, Prefs.theme)
    }

    @Test fun upgradesFrom25KeepTheirAccent() {
        Prefs.sp.edit(commit = true) { putString("accent", "green"); putString("accent_work", "purple") }
        assertSame(ThemePreset.TOXIC, Prefs.theme)
        assertSame(ThemePreset.VOID, Prefs.accentFor(Realm.WORK))
        assertTrue(Prefs.hasCustomAccent(Realm.WORK))
    }

    @Test fun unknownStoredValuesFallBack() {
        Prefs.sp.edit(commit = true) { putString("accent", "neon"); putString("accent_work", "") }
        assertSame(ThemePreset.DEFAULT, Prefs.theme)
        assertSame(ThemePreset.FROST, Prefs.accentFor(Realm.WORK))
        assertFalse(Prefs.hasCustomAccent(Realm.WORK))
    }

    @Test fun workAutoAvoidsThePlayTheme() {
        Prefs.setAccent(Realm.PLAY, ThemePreset.FROST)
        assertSame("Frost is taken: Ember", ThemePreset.EMBER, Prefs.accentFor(Realm.WORK))
        assertFalse(Prefs.hasCustomAccent(Realm.WORK))

        Prefs.setAccent(Realm.WORK, ThemePreset.SAKURA)
        assertTrue(Prefs.hasCustomAccent(Realm.WORK))
        assertEquals("sakura", Prefs.sp.getString("accent_work", null))
        assertSame(ThemePreset.SAKURA, Prefs.accentFor(Realm.WORK))
        assertSame("Work's pick doesn't touch the theme", ThemePreset.FROST, Prefs.theme)

        Prefs.setAccent(Realm.WORK, null)
        assertFalse(Prefs.sp.contains("accent_work"))
        assertSame(ThemePreset.EMBER, Prefs.accentFor(Realm.WORK))
    }

    @Test fun ghostIsFixed() {
        Prefs.setAccent(Realm.GHOST, ThemePreset.GOLD)
        assertSame(ThemePreset.GHOST, Prefs.accentFor(Realm.GHOST))
        assertFalse(Prefs.sp.all.keys.any { it.contains("ghost") })
        try {
            Prefs.setAccent(Realm.PLAY, ThemePreset.GHOST)
            throw AssertionError("Ghost can't be picked")
        } catch (_: IllegalArgumentException) {}
        assertSame(ThemePreset.DEFAULT, Prefs.theme)
    }

    @Test fun currentRealmPicksTheAccent() {
        Prefs.setAccent(Realm.PLAY, ThemePreset.ASH)
        Prefs.setAccent(Realm.WORK, ThemePreset.TOXIC)
        Prefs.realm = Realm.PLAY; assertSame(ThemePreset.ASH, Prefs.accent)
        Prefs.realm = Realm.WORK; assertSame(ThemePreset.TOXIC, Prefs.accent)
        Prefs.realm = Realm.GHOST; assertSame(ThemePreset.GHOST, Prefs.accent)
    }

    @Test fun themeChoiceTravelsInBackups() {
        Prefs.setAccent(Realm.PLAY, ThemePreset.SAKURA)
        Prefs.setAccent(Realm.WORK, ThemePreset.EMBER)
        val sections = BackupManager.Sections(logins = false, bookmarks = false, speedDial = false, readingList = false, settings = true)
        val out = BackupManager.collect(ctx, sections).settings!!
        assertEquals("sakura", out["accent"]); assertEquals("orange", out["accent_work"])
        Prefs.sp.edit(commit = true) { clear() }
        BackupManager.apply(ctx, BackupPayload.fromJson(BackupPayload(settings = out).toJson()), sections)
        assertSame(ThemePreset.SAKURA, Prefs.theme)
        assertSame(ThemePreset.EMBER, Prefs.accentFor(Realm.WORK))
    }

    @Test fun paletteResolvesLightAndDarkTokens() {
        val dark = AppTheme.palette(ThemePreset.FROST, night = true)
        val light = AppTheme.palette(ThemePreset.FROST, night = false)
        assertEquals(0xFF00E1FF.toInt(), dark.accentText)
        assertEquals(0xFF008294.toInt(), light.accentText)
        assertEquals(dark.color, light.color)
        assertEquals(listOf(ThemePreset.FROST.overlay), dark.overlays)
        assertNull(dark.systemBars)
    }

    /** Item 11 (darker private palette) plugs in through [AppTheme.privateOverride]; only private screens get it. */
    @Test fun privateOverrideLayersOnTopOfTheChosenTheme() {
        val overlay = R.style.ThemeOverlay_Ulfur_Preset_Ghost // any style id; the real one comes with item 11
        AppTheme.privateOverride = object : ThemeOverride {
            override val overlay = overlay
            override fun applyTo(base: ThemePalette) = base.copy(systemBars = 0xFF000000.toInt(), color = 0xFF112233.toInt())
        }
        val normal = AppTheme.palette(ThemePreset.GOLD, night = true, private = false)
        val private = AppTheme.palette(ThemePreset.GOLD, night = true, private = true)
        assertEquals(ThemePreset.GOLD.color, normal.color)
        assertNull(normal.systemBars)
        assertSame("the preset stays the user's choice", ThemePreset.GOLD, private.preset)
        assertEquals(0xFF112233.toInt(), private.color)
        assertEquals(0xFF000000.toInt(), private.systemBars)
        assertEquals("preset overlay first, override after", listOf(ThemePreset.GOLD.overlay, overlay), private.overlays)

        AppTheme.privateOverride = null
        assertEquals(normal, AppTheme.palette(ThemePreset.GOLD, night = true, private = true))
    }
}
