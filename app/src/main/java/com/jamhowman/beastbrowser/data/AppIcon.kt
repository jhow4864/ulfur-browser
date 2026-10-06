package com.jamhowman.beastbrowser.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

import com.jamhowman.beastbrowser.R

/**
 * Alternate home-screen icons (2.8). The brand [DEFAULT] stays on [com.jamhowman.beastbrowser.ui.MainActivity];
 * each colourway is an `<activity-alias>` that targets MainActivity.
 *
 * Icon assets → ThemePreset map (Designer names first; mismatches matched by accent hex):
 * - blood_moon → BLOOD_MOON, frost → FROST, toxic → TOXIC, ember → EMBER,
 *   gold → GOLD, sakura → SAKURA
 * - full_moon → VOID (cream moon disc; remaining purple Void preset)
 * - ghost → ASH (grey/dim #C9CCD6/#7A7F90 ≈ Ash #F2F2F7/#8E8EA0; Ghost realm stays unselectable)
 */
enum class AppIcon(
    val key: String,
    val label: String,
    /** Theme this colourway matches, or null for the brand Default. */
    val theme: ThemePreset?,
    @DrawableRes val previewRes: Int,
    @DrawableRes val iconRes: Int,
    @DrawableRes val roundIconRes: Int,
    /** Simple class name of the activity-alias, or null when the launcher is MainActivity. */
    val aliasSimpleName: String?,
) {
    DEFAULT(
        "default", "Default", null,
        R.drawable.ic_icon_preview_default,
        R.mipmap.ic_launcher, R.mipmap.ic_launcher_round,
        null,
    ),
    BLOOD_MOON(
        "blood_moon", "Blood Moon", ThemePreset.BLOOD_MOON,
        R.drawable.ic_icon_preview_blood_moon,
        R.mipmap.ic_launcher_blood_moon, R.mipmap.ic_launcher_blood_moon_round,
        "IconAliasBloodMoon",
    ),
    FROST(
        "frost", "Frost", ThemePreset.FROST,
        R.drawable.ic_icon_preview_frost,
        R.mipmap.ic_launcher_frost, R.mipmap.ic_launcher_frost_round,
        "IconAliasFrost",
    ),
    TOXIC(
        "toxic", "Toxic", ThemePreset.TOXIC,
        R.drawable.ic_icon_preview_toxic,
        R.mipmap.ic_launcher_toxic, R.mipmap.ic_launcher_toxic_round,
        "IconAliasToxic",
    ),
    EMBER(
        "ember", "Ember", ThemePreset.EMBER,
        R.drawable.ic_icon_preview_ember,
        R.mipmap.ic_launcher_ember, R.mipmap.ic_launcher_ember_round,
        "IconAliasEmber",
    ),
    FULL_MOON(
        "full_moon", "Void", ThemePreset.VOID,
        R.drawable.ic_icon_preview_full_moon,
        R.mipmap.ic_launcher_full_moon, R.mipmap.ic_launcher_full_moon_round,
        "IconAliasFullMoon",
    ),
    GOLD(
        "gold", "Gold", ThemePreset.GOLD,
        R.drawable.ic_icon_preview_gold,
        R.mipmap.ic_launcher_gold, R.mipmap.ic_launcher_gold_round,
        "IconAliasGold",
    ),
    SAKURA(
        "sakura", "Sakura", ThemePreset.SAKURA,
        R.drawable.ic_icon_preview_sakura,
        R.mipmap.ic_launcher_sakura, R.mipmap.ic_launcher_sakura_round,
        "IconAliasSakura",
    ),
    GHOST(
        "ghost", "Ash", ThemePreset.ASH,
        R.drawable.ic_icon_preview_ghost,
        R.mipmap.ic_launcher_ghost, R.mipmap.ic_launcher_ghost_round,
        "IconAliasGhost",
    );

    fun componentName(context: Context): ComponentName {
        val pkg = context.packageName
        val cls = if (aliasSimpleName == null) "$pkg.ui.MainActivity" else "$pkg.ui.$aliasSimpleName"
        return ComponentName(pkg, cls)
    }

    companion object {
        fun from(key: String?): AppIcon =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        /** Persist [icon] and enable exactly one launcher component (MainActivity or one alias). */
        fun apply(context: Context, icon: AppIcon) {
            Prefs.appIconKey = icon.key
            enableOnly(context, icon)
        }

        /** Re-apply the stored choice (e.g. after install / process start). */
        fun sync(context: Context) = enableOnly(context, from(Prefs.appIconKey))

        private fun enableOnly(context: Context, selected: AppIcon) {
            val pm = context.packageManager
            for (entry in entries) {
                val state = when {
                    entry == selected && entry == DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    entry == selected -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(entry.componentName(context), state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
