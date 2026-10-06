package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.PrivateLook
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.data.ThemePreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Roadmap 11: private tabs, Ghost and the vault get night-violet bars; the theme's accent is left alone. */
class PrivateLookTest {
    @After fun restore() { AppTheme.privateOverride = PrivateLook }

    @Test fun privateLookIsTheDefaultOverride() = assertSame(PrivateLook, AppTheme.privateOverride)

    @Test fun privateBarsAreVioletInBothModes() {
        for (preset in ThemePreset.entries) {
            assertEquals(preset.name, PrivateLook.BARS_NIGHT, AppTheme.palette(preset, night = true, private = true).systemBars)
            assertEquals(preset.name, PrivateLook.BARS_DAY, AppTheme.palette(preset, night = false, private = true).systemBars)
        }
    }

    @Test fun normalTabsKeepThePre28Bars() {
        for (preset in ThemePreset.entries) assertNull(preset.name, AppTheme.palette(preset, night = true).systemBars)
    }

    /** The shield, menu and new-tab wolf keep the chosen theme's colours in private tabs (design decision). */
    @Test fun accentAndGradientAreUntouched() {
        for (preset in ThemePreset.entries) for (night in listOf(true, false)) {
            val normal = ThemePalette.of(preset, night)
            val private = AppTheme.palette(preset, night, private = true)
            assertEquals(normal.copy(systemBars = private.systemBars), private)
        }
    }

    @Test fun barsMatchPrivateBgResource() {
        // values-night/colors.xml and values/colors.xml: private_bg
        assertEquals(0xFF1B1328.toInt(), PrivateLook.BARS_NIGHT)
        assertEquals(0xFFF3EDFA.toInt(), PrivateLook.BARS_DAY)
    }
}
