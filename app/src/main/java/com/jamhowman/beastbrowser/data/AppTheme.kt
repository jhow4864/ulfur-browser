package com.jamhowman.beastbrowser.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StyleRes

/**
 * What a screen paints with (2.8): a [ThemePreset] resolved for light/dark, plus an optional [ThemeOverride].
 * Members mirror [ThemePreset] (`color`, `onColor`, `withAlpha`) so tint code reads the same either way.
 */
data class ThemePalette(
    val preset: ThemePreset,
    val night: Boolean,
    val color: Int,
    val onColor: Int,
    val accentStart: Int,
    val accentEnd: Int,
    val accentText: Int,
    val surfaceTint: Int,
    /** Second stop of the wolf's eye gradient (`?attr/ulfurEye`), for the animated wolf (item 19). */
    val eye: Int,
    /** Background behind the status and navigation bars (edge-to-edge); null = `@color/bg`, the pre-2.8 look. */
    val systemBars: Int? = null,
    /** Theme overlays, applied in order with `theme.applyStyle(it, true)`. */
    val overlays: List<Int> = listOf(preset.overlay),
) {
    val gradient: IntArray get() = intArrayOf(accentStart, accentEnd)
    /** Ink for text on a fill: on the gradient when the preset allows it, else (Ghost) only on the solid [color]. */
    val gradientCarriesText: Boolean get() = preset.gradientCarriesText
    fun withAlpha(alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    companion object {
        fun of(preset: ThemePreset, night: Boolean) = ThemePalette(
            preset = preset,
            night = night,
            color = preset.color,
            onColor = preset.onColor,
            accentStart = preset.accentStart,
            accentEnd = preset.accentEnd,
            accentText = preset.accentText(night),
            surfaceTint = preset.surfaceTint(night),
            eye = preset.eye,
        )
    }
}

/**
 * A palette layered on top of the chosen preset. Roadmap item 11 (darker private-mode palette) plugs in here by
 * setting [AppTheme.privateOverride]; nothing in item 18 provides one, so private tabs keep the realm's theme.
 */
interface ThemeOverride {
    /**
     * Optional overlay applied after the preset's; 0 = none. `applyStyle` can't be undone, so when the override
     * goes away the preset overlay is applied again on top: only set attributes every preset overlay also sets
     * (see `ThemeOverlay.Ulfur.Preset` in themes.xml), or recreate the activity.
     */
    @get:StyleRes val overlay: Int get() = 0

    /** Returns [base] with the override's colours (e.g. darker [ThemePalette.systemBars], dimmer accent). */
    fun applyTo(base: ThemePalette): ThemePalette
}

object AppTheme {
    /** Private-mode override (item 11). null = none. Read on every [palette] call, so it can change at runtime. */
    @Volatile var privateOverride: ThemeOverride? = null

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** [preset] for [night], with the private override on top when [private] (private tab, Ghost, vault). */
    fun palette(preset: ThemePreset, night: Boolean, private: Boolean = false): ThemePalette {
        val base = ThemePalette.of(preset, night)
        val o = (if (private) privateOverride else null) ?: return base
        val p = o.applyTo(base)
        return if (o.overlay != 0) p.copy(overlays = base.overlays + o.overlay) else p
    }

    /** Palette of [realm] (default: the current one) for [context]'s light/dark mode. */
    fun palette(context: Context, realm: Realm = Prefs.realm, private: Boolean = realm.alwaysPrivate): ThemePalette =
        palette(Prefs.accentFor(realm), isNight(context), private)

    fun applyOverlays(theme: Resources.Theme, palette: ThemePalette) = palette.overlays.forEach { theme.applyStyle(it, true) }
}
