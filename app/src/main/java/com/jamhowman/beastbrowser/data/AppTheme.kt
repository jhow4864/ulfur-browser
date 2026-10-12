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
    /** 2.10 theme packs v2 (Play only): surfaces and text set in code; null = the resource colour (pre-2.10 look). */
    val surfaces: ThemeSurfaces? = null,
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
 * A palette layered on top of the chosen preset. Roadmap item 11 (darker private-mode palette) plugs in here:
 * [AppTheme.privateOverride] defaults to [PrivateLook].
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
    /** Private-mode override (item 11), [PrivateLook] by default. null = none. Read on every [palette] call. */
    @Volatile var privateOverride: ThemeOverride? = PrivateLook

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
        palette(Prefs.accentFor(realm), isNight(context), private, if (realm == Realm.PLAY) ThemePacks.activeForPalette() else null)

    /** 2.9: [pack] (an applied theme pack, Play only) recolours the preset before the private override. */
    fun palette(preset: ThemePreset, night: Boolean, private: Boolean, pack: ThemePack?): ThemePalette {
        if (pack == null) return palette(preset, night, private)
        // 2.10: private tabs keep the pre-2.10 behaviour (accent only, then the private override); no surfaces/images.
        val base = pack.applyTo(ThemePalette.of(preset, night), surfaces = !private)
        val o = (if (private) privateOverride else null) ?: return base
        val p = o.applyTo(base)
        return if (o.overlay != 0) p.copy(overlays = base.overlays + o.overlay) else p
    }

    fun applyOverlays(theme: Resources.Theme, palette: ThemePalette) = palette.overlays.forEach { theme.applyStyle(it, true) }
}

/**
 * 2.10: a v2 pack's surface and text colours for one mode, each null when the pack leaves it to Ulfur
 * (`@color/bg`, `surface`, `surface2`, `text_primary`, `text_secondary`, toolbar = bg, onToolbar = text, divider).
 * [imagesId] identifies the pack's images so a palette change is noticed when only the images differ.
 */
data class ThemeSurfaces(
    val background: Int? = null,
    val surface: Int? = null,
    val surfaceVariant: Int? = null,
    val text: Int? = null,
    val textSecondary: Int? = null,
    val toolbar: Int? = null,
    val onToolbar: Int? = null,
    val divider: Int? = null,
    val imagesId: String? = null,
) {
    fun resolved(night: Boolean) = Resolved(
        background = background ?: if (night) ThemeContrast.BG_DARK else ThemeContrast.BG_LIGHT,
        surface = surface ?: if (night) ThemeContrast.SURFACE_DARK else ThemeContrast.SURFACE_LIGHT,
        surfaceVariant = surfaceVariant ?: if (night) ThemeContrast.SURFACE2_DARK else ThemeContrast.SURFACE2_LIGHT,
        text = text ?: if (night) ThemeContrast.TEXT_DARK else ThemeContrast.TEXT_LIGHT,
        textSecondary = textSecondary ?: if (night) ThemeContrast.TEXT2_DARK else ThemeContrast.TEXT2_LIGHT,
        toolbar = toolbar ?: background ?: if (night) ThemeContrast.BG_DARK else ThemeContrast.BG_LIGHT,
        onToolbar = onToolbar ?: text ?: if (night) ThemeContrast.TEXT_DARK else ThemeContrast.TEXT_LIGHT,
        divider = divider ?: ((text ?: if (night) ThemeContrast.TEXT_DARK else ThemeContrast.TEXT_LIGHT) and 0x00FFFFFF or 0x1F000000),
    )

    data class Resolved(val background: Int, val surface: Int, val surfaceVariant: Int, val text: Int,
                        val textSecondary: Int, val toolbar: Int, val onToolbar: Int, val divider: Int)
}
