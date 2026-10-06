package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.ShieldLevel
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.ThemeOverride
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.ui.ShieldBadge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roadmap 10: page shield level thresholds (0 = none, 1-9 = some, 10+ = many). */
class ShieldLevelTest {
    @Test fun nothingBlockedIsNone() {
        assertEquals(ShieldLevel.NONE, ShieldLevel.of(0))
        assertEquals(ShieldLevel.NONE, ShieldLevel.of(-3)) // defensive: never negative in practice
    }

    @Test fun oneToNineIsSome() {
        for (n in 1..9) assertEquals("$n", ShieldLevel.SOME, ShieldLevel.of(n))
    }

    @Test fun tenAndUpIsMany() {
        assertEquals(10, ShieldLevel.MANY_FROM)
        for (n in listOf(10, 11, 99, 100, 10_000, Int.MAX_VALUE)) assertEquals("$n", ShieldLevel.MANY, ShieldLevel.of(n))
    }

    @Test fun boundaryFollowsTheConstant() {
        assertEquals(ShieldLevel.SOME, ShieldLevel.of(ShieldLevel.MANY_FROM - 1))
        assertEquals(ShieldLevel.MANY, ShieldLevel.of(ShieldLevel.MANY_FROM))
    }

    // Visual mapping (ShieldBadge) checked only structurally, so the designer can change the actual look.
    @Test fun eachLevelLooksDifferentWhileShieldsAreUp() {
        val styles = ShieldLevel.entries.map { ShieldBadge.style(it, shieldsUp = true) }
        assertEquals(styles.size, styles.toSet().size)
        assertFalse(styles[ShieldLevel.NONE.ordinal].showCount)
        assertTrue(styles[ShieldLevel.SOME.ordinal].showCount && styles[ShieldLevel.MANY.ordinal].showCount)
    }

    @Test fun shieldsDownOverridesTheLevelLook() {
        val up = ShieldBadge.style(ShieldLevel.MANY, shieldsUp = true)
        val down = ShieldBadge.style(ShieldLevel.MANY, shieldsUp = false)
        assertNotEquals(up.icon, down.icon)
        assertEquals(ShieldBadge.style(ShieldLevel.NONE, false).description, down.description)
    }

    @Test fun manyReadsAsAWinNotAWarning() {
        val some = ShieldBadge.style(ShieldLevel.SOME, shieldsUp = true)
        val many = ShieldBadge.style(ShieldLevel.MANY, shieldsUp = true)
        assertNotEquals(some.icon, many.icon)
        assertTrue(many.badgeGradient)
        assertFalse(some.badgeGradient)
        assertNotEquals(ShieldBadge.Tint.WARN, many.badgeTint)
    }

    // The many badge gradient is the active theme's own accentStart → accentEnd (2.8 palette), light and dark.
    @Test fun manyGradientFollowsTheChosenPreset() {
        for (preset in ThemePreset.presets) for (night in listOf(true, false)) {
            val g = ShieldBadge.gradient(AppTheme.palette(preset, night))
            assertNotNull("$preset night=$night", g)
            assertArrayEquals("$preset night=$night", intArrayOf(preset.accentStart, preset.accentEnd), g)
            assertNotEquals("$preset: a gradient, not a flat fill", g!![0], g[1])
        }
        val all = ThemePreset.presets.map { ShieldBadge.gradient(AppTheme.palette(it, night = true))!!.toList() }
        assertEquals("each preset paints its own badge", all.size, all.toSet().size)
    }

    @Test fun ghostBadgeStaysSolidBecauseItCarriesTheCount() {
        assertFalse(ThemePreset.GHOST.gradientCarriesText)
        for (night in listOf(true, false)) assertNull(ShieldBadge.gradient(AppTheme.palette(ThemePreset.GHOST, night, private = true)))
    }

    @Test fun privateTabsKeepTheAccentUnlessAnOverrideChangesThePalette() {
        val preset = ThemePreset.FROST
        val normal = ShieldBadge.gradient(AppTheme.palette(preset, night = true))
        assertArrayEquals(normal, ShieldBadge.gradient(AppTheme.palette(preset, night = true, private = true)))
        // Same palette as the rest of the toolbar: if a private-mode override (item 11) dims the accent, so does the badge.
        val saved = AppTheme.privateOverride
        try {
            AppTheme.privateOverride = object : ThemeOverride {
                override fun applyTo(base: ThemePalette) = base.copy(accentStart = 0xFF111111.toInt(), accentEnd = 0xFF222222.toInt())
            }
            assertArrayEquals(intArrayOf(0xFF111111.toInt(), 0xFF222222.toInt()),
                ShieldBadge.gradient(AppTheme.palette(preset, night = true, private = true)))
            assertArrayEquals(normal, ShieldBadge.gradient(AppTheme.palette(preset, night = true)))
        } finally {
            AppTheme.privateOverride = saved
        }
    }
}
