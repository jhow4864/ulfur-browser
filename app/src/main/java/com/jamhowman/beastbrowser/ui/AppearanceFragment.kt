package com.jamhowman.beastbrowser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.AppIcon
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.ThemeColor
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.data.UiTheme
import com.jamhowman.beastbrowser.databinding.FragmentAppearanceBinding
import com.jamhowman.beastbrowser.databinding.ViewSectionHeaderBinding
import com.jamhowman.beastbrowser.widget.SpeedDialWidget
import java.text.NumberFormat
import java.util.Locale

/**
 * 2.8 Settings > Appearance (roadmap item 18; Designer's SPEC.md "Theme picker", mockups theme-picker-*.png):
 * live preview, Dark / Light / System mode (`ui_theme`), the 4×2 grid of [ThemePreset]s and the realm accents.
 *
 * The grid edits the theme, i.e. Play's accent (`accent`); Work has its own (AUTO or CUSTOM, `accent_work`) and Ghost
 * is fixed. Picks apply straight away: [SettingsActivity.onThemeChanged] re-applies the overlay in place under a
 * 200 ms crossfade; MainActivity re-tints when it resumes, other screens recreate on resume ([ThemedScreen]).
 * Everything here is coloured in code from [ThemePalette]s, so [bind] can run again after every change.
 */
class AppearanceFragment : Fragment() {
    private var _b: FragmentAppearanceBinding? = null
    private val b get() = _b!!
    private var sheet: BottomSheetDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAppearanceBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listOf(b.headerPreview, b.headerMode, b.headerAccent, b.headerRealms, b.headerIcon, b.headerMotion).forEach {
            ViewCompat.setAccessibilityHeading(it.sectionLabel, true)
        }
        bind()
    }

    override fun onResume() {
        super.onResume()
        bind() // Work's AUTO accent and the realm tiles depend on prefs other screens can change
    }

    override fun onDestroyView() {
        sheet?.dismiss()
        sheet = null
        _b = null
        super.onDestroyView()
    }

    /** Colours every part of the screen from the current prefs. */
    private fun bind() {
        val ctx = requireContext()
        val night = AppTheme.isNight(ctx)
        val theme = Prefs.theme
        val p = AppTheme.palette(theme, night)

        b.accentWash.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.withAlpha(0x14), 0))
        header(b.headerPreview, p, R.string.appearance_section_preview,
            getString(R.string.appearance_preview_meta, theme.label, Realm.PLAY.label))
        header(b.headerMode, p, R.string.appearance_section_mode, null)
        header(b.headerAccent, p, R.string.appearance_section_accent,
            resources.getQuantityString(R.plurals.appearance_presets_count, ThemePreset.presets.size, ThemePreset.presets.size))
        header(b.headerRealms, p, R.string.appearance_section_realms, getString(R.string.appearance_realms_meta))

        bindPreview(ctx, p)
        bindModes(ctx, p)
        b.presetGrid.removeAllViews()
        ThemePreset.presets.forEach { preset ->
            b.presetGrid.addView(presetCard(ctx, preset, preset == theme, night) { pickTheme(it) }, gridCell(ctx))
        }
        bindRealms(ctx, night)
        header(b.headerIcon, p, R.string.appearance_section_icon,
            resources.getQuantityString(R.plurals.appearance_icons_count, AppIcon.entries.size, AppIcon.entries.size))
        bindIcons(ctx, p)
        header(b.headerMotion, p, R.string.appearance_section_motion, null)
        bindWolf(p)
    }

    /**
     * Item 19's "Animated wolf" switch (`wolf_animation`). With system animations off the wolf can't move anyway,
     * so the row is disabled and says why; the saved choice is kept for when they come back on.
     */
    private fun bindWolf(p: ThemePalette) {
        val system = ValueAnimator.areAnimatorsEnabled()
        val on = Prefs.wolfAnimation
        b.wolfSwitch.setOnCheckedChangeListener(null)
        b.wolfSwitch.isChecked = system && on
        b.wolfSwitch.isEnabled = system
        b.wolfRow.isEnabled = system
        b.wolfRow.alpha = if (system) 1f else 0.6f
        b.wolfSummary.setText(when {
            !system -> R.string.appearance_wolf_system_off
            on -> R.string.appearance_wolf_on
            else -> R.string.appearance_wolf_off
        })
        b.wolfSwitch.thumbTintList = null
        b.wolfSwitch.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.color, requireContext().getColor(R.color.surface3)),
        )
        b.wolfSwitch.contentDescription = getString(R.string.appearance_wolf_title) + ". " + b.wolfSummary.text
        b.wolfRow.setOnClickListener { if (system) b.wolfSwitch.toggle() }
        b.wolfSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.wolfAnimation = checked
            bindWolf(p)
        }
    }

    private fun header(h: ViewSectionHeaderBinding, p: ThemePalette, label: Int, meta: String?) {
        h.sectionMarker.setBackgroundColor(p.color)
        h.sectionLabel.setText(label)
        h.sectionMeta.text = meta
        h.sectionMeta.visibility = if (meta == null) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ live preview

    private fun bindPreview(ctx: Context, p: ThemePalette) {
        val surface2 = ctx.getColor(R.color.surface2)
        val stroke = ctx.getColor(R.color.stroke)
        b.miniPhone.background = rounded(ctx, ctx.getColor(R.color.bg), 18, stroke, 2)
        b.miniPhone.clipToOutline = true
        b.miniPhone.outlineProvider = ViewOutlineProvider.BACKGROUND
        b.miniAddress.background = rounded(ctx, surface2, 10)
        b.miniShield.imageTintList = ColorStateList.valueOf(p.color)
        b.miniGlow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            shape = GradientDrawable.OVAL
            colors = intArrayOf(p.glow(), 0)
            gradientRadius = ctx.resources.displayMetrics.density * 30
        }
        b.miniLogo.imageTintList = ColorStateList.valueOf(p.color)
        b.miniWordmarkSub.text = getString(R.string.appearance_preview_wordmark_sub, Realm.PLAY.label).uppercase(Locale.ROOT)
        b.miniWordmarkSub.setTextColor(p.accentText)
        b.miniSearch.background = rounded(ctx, surface2, 10, p.withAlpha(0x66), 1)
        b.miniSearchIcon.imageTintList = ColorStateList.valueOf(p.color)
        b.miniSearchText.text = getString(R.string.appearance_preview_search, Prefs.searchEngine.label)
        b.miniDialMarker.setBackgroundColor(p.color)
        b.miniTiles.removeAllViews()
        SpeedDialStore.load().take(4).forEach { tile ->
            b.miniTiles.addView(TextView(ctx).apply {
                text = (tile.title.firstOrNull() ?: '•').uppercase()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.accentText)
                background = rounded(ctx, surface2, 5, p.withAlpha(0x55), 1)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(ctx, 2); marginEnd = dp(ctx, 2)
            })
        }
        b.miniTabCount.text = fmt(3)
        b.miniTabCount.background = rounded(ctx, 0, 2, p.color, 1)
        b.miniMenuWolf.imageTintList = ColorStateList.valueOf(p.color)

        // Filled button: the gradient carries dark ink (white on Void); Ghost's text-bearing fills stay solid.
        b.previewNewTabBox.background = fill(ctx, p, 18)
        b.previewNewTab.setTextColor(p.onColor)
        AppCompatResources.getDrawable(ctx, R.drawable.ic_add)?.mutate()?.let { icon ->
            val s = dp(ctx, 16)
            icon.setBounds(0, 0, s, s)
            icon.setTint(p.onColor)
            b.previewNewTab.setCompoundDrawablesRelative(icon, null, null, null)
        }
        b.previewSwitch.thumbTintList = ColorStateList.valueOf(p.onColor)
        b.previewSwitch.trackTintList = ColorStateList.valueOf(p.color)
        b.previewSwitch.trackDecorationTintList = ColorStateList.valueOf(p.color)
        listOf(b.previewChipShields, b.previewChipRealm).forEach {
            it.background = rounded(ctx, p.withAlpha(0x22), 10, p.withAlpha(0x88), 1)
            it.setTextColor(p.accentText)
        }
        b.previewChipRealm.text = Realm.PLAY.label
        b.previewPercent.text = NumberFormat.getPercentInstance().format(0.62)
        b.previewPercent.setTextColor(p.accentText)
        b.previewProgressTrack.background = rounded(ctx, ctx.getColor(R.color.track_muted), 2)
        b.previewProgress.background = gradient(ctx, p, 2)
        b.previewTabCard.background = rounded(ctx, surface2, 8, stroke, 1)
        b.previewTabCard.clipToOutline = true
        b.previewTabCard.outlineProvider = ViewOutlineProvider.BACKGROUND
        b.previewTabStrip.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, p.gradient)
        b.previewTabIcon.imageTintList = ColorStateList.valueOf(p.color)
    }

    // ------------------------------------------------------------------ mode (ui_theme)

    private fun bindModes(ctx: Context, p: ThemePalette) {
        val keys = resources.getStringArray(R.array.ui_theme_keys)
        val names = resources.getStringArray(R.array.ui_theme_names)
        val current = Prefs.uiTheme
        b.modeRow.removeAllViews()
        keys.forEachIndexed { i, key ->
            val selected = key == current
            val label = TextView(ctx).apply {
                text = names[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) p.accentText else ctx.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                if (selected) AppCompatResources.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.let { icon ->
                    val s = dp(ctx, 16)
                    icon.setBounds(0, 0, s, s)
                    icon.setTint(p.accentText)
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(ctx, 4)
                }
            }
            b.modeRow.addView(FrameLayout(ctx).apply {
                background = if (selected) rounded(ctx, p.surfaceTint, 12, p.withAlpha(0x88), 1)
                else rounded(ctx, ctx.getColor(R.color.surface), 12, ctx.getColor(R.color.stroke), 1)
                foreground = AppCompatResources.getDrawable(ctx, selectableBackground(ctx))
                addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                isSelected = selected
                contentDescription = getString(R.string.appearance_mode_option, names[i])
                radioRole(this, selected)
                setOnClickListener { pickMode(key) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(ctx, 8)
            })
        }
    }

    private fun pickMode(key: String) {
        if (key == Prefs.uiTheme) return
        Prefs.sp.edit { putString("ui_theme", key) }
        UiTheme.apply(key) // recreates this screen when light/dark actually flips
        bind()             // e.g. Dark → System on a dark phone: same colours, new selection
    }

    // ------------------------------------------------------------------ presets

    private fun pickTheme(preset: ThemePreset) {
        if (preset == Prefs.theme) return
        Prefs.setAccent(Realm.PLAY, preset)
        themeChanged()
    }

    private fun pickRealm(realm: Realm, preset: ThemePreset?) {
        Prefs.setAccent(realm, preset)
        themeChanged()
    }

    /** Applies a new preset choice to this screen (crossfade), the home-screen widget and, on resume, everything else. */
    private fun themeChanged() {
        val host = activity as? SettingsActivity
        if (host != null) host.onThemeChanged { if (_b != null) bind() } else bind()
        context?.let { SpeedDialWidget.refreshAll(it) }
    }

    private fun gridCell(ctx: Context) = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        setMargins(dp(ctx, 1), dp(ctx, 2), dp(ctx, 1), dp(ctx, 2))
    }

    // ------------------------------------------------------------------ realm accents

    private fun bindRealms(ctx: Context, night: Boolean) {
        b.realmRow.removeAllViews()
        val theme = Prefs.theme
        listOf(Realm.PLAY, Realm.WORK, Realm.GHOST).forEachIndexed { i, realm ->
            val rp = AppTheme.palette(Prefs.accentFor(realm), night)
            val (status, sub) = when (realm) {
                Realm.PLAY -> R.string.appearance_realm_status_theme to getString(R.string.appearance_realm_follows, theme.label)
                Realm.WORK -> if (Prefs.hasCustomAccent(realm)) {
                    R.string.appearance_realm_status_custom to getString(R.string.appearance_realm_picked, rp.preset.label)
                } else {
                    R.string.appearance_realm_status_auto to
                        if (theme == ThemePreset.FROST) getString(R.string.appearance_realm_auto, rp.preset.label, theme.label)
                        else rp.preset.label
                }
                Realm.GHOST -> R.string.appearance_realm_status_fixed to getString(R.string.appearance_realm_ghost)
            }
            b.realmRow.addView(realmTile(ctx, realm, rp, getString(status), sub),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(ctx, 8)
                })
        }
    }

    private fun realmTile(ctx: Context, realm: Realm, rp: ThemePalette, status: String, sub: String): View {
        val fixed = realm == Realm.GHOST
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, ctx.getColor(R.color.surface), 16, ctx.getColor(R.color.stroke), 1)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = getString(R.string.appearance_realm_tile, realm.label, status, sub)
            if (!fixed) {
                isClickable = true
                isFocusable = true
                foreground = AppCompatResources.getDrawable(ctx, selectableBackground(ctx))
                setOnClickListener { showRealmSheet(realm) }
            }
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), 0)
        }
        top.addView(FrameLayout(ctx).apply {
            background = rounded(ctx, rp.surfaceTint, 10)
            addView(ImageView(ctx).apply {
                setImageResource(realm.sealIcon)
                imageTintList = ColorStateList.valueOf(rp.color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)))
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(TextView(ctx).apply {
            text = status.uppercase(Locale.ROOT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            maxLines = 1
            val ink = if (fixed) ctx.getColor(R.color.text_secondary) else rp.accentText
            setTextColor(ink)
            background = rounded(ctx, 0, 8, if (fixed) ctx.getColor(R.color.stroke) else rp.withAlpha(0x88), 1)
            setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
            if (fixed) AppCompatResources.getDrawable(ctx, R.drawable.ic_lock)?.mutate()?.let { icon ->
                val s = dp(ctx, 9)
                icon.setBounds(0, 0, s, s)
                icon.setTint(ink)
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(ctx, 2)
            }
        })
        tile.addView(top)
        tile.addView(TextView(ctx).apply {
            text = realm.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.getColor(R.color.text_primary))
            setPaddingRelative(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), 0)
        })
        tile.addView(TextView(ctx).apply {
            text = sub
            setTextColor(ctx.getColor(R.color.text_secondary))
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP) // "Ember (Frost in use)"
            setPaddingRelative(dp(ctx, 10), 0, dp(ctx, 10), dp(ctx, 10))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 15 + 10)))
        tile.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, rp.gradient)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4)))
        return tile
    }

    /** "Play / Work realm accent": the same grid in a bottom sheet; Work can go back to AUTO. Ghost never opens one. */
    private fun showRealmSheet(realm: Realm) {
        val ctx = requireContext()
        val night = AppTheme.isNight(ctx)
        val dialog = BottomSheetDialog(ctx)
        val pad = dp(ctx, 20)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 12), pad, pad)
        }
        root.addView(View(ctx).apply { setBackgroundResource(R.drawable.bg_sheet_handle) },
            LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 16)
            })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.appearance_sheet_title, realm.label)
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        root.addView(TextView(ctx).apply {
            setText(if (realm == Realm.PLAY) R.string.appearance_sheet_play_note else R.string.appearance_sheet_work_note)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 4)
            bottomMargin = dp(ctx, 12)
        })
        val custom = realm == Realm.PLAY || Prefs.hasCustomAccent(realm)
        val selected = Prefs.accentFor(realm).takeIf { custom }
        val grid = GridLayout(ctx).apply { columnCount = 4 }
        ThemePreset.presets.forEach { preset ->
            grid.addView(presetCard(ctx, preset, preset == selected, night) {
                dialog.dismiss()
                if (it != selected) pickRealm(realm, it)
            }, gridCell(ctx))
        }
        root.addView(grid)
        if (realm == Realm.WORK) {
            val p = AppTheme.palette(Prefs.accentFor(realm), night)
            root.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.appearance_sheet_work_auto)
                isAllCaps = false
                val ink = if (custom) ctx.getColor(R.color.text_secondary) else p.accentText
                setTextColor(ink)
                strokeColor = ColorStateList.valueOf(if (custom) ctx.getColor(R.color.stroke) else p.withAlpha(0x88))
                if (!custom) {
                    setIconResource(R.drawable.ic_check)
                    iconTint = ColorStateList.valueOf(ink)
                }
                radioRole(this, !custom)
                setOnClickListener {
                    dialog.dismiss()
                    if (custom) pickRealm(realm, null)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 12)
            })
        }
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
        sheet = dialog
        dialog.show()
    }


    // ------------------------------------------------------------------ app icon

    private fun bindIcons(ctx: Context, p: ThemePalette) {
        val current = AppIcon.from(Prefs.appIconKey)
        b.iconGrid.removeAllViews()
        AppIcon.entries.forEach { icon ->
            b.iconGrid.addView(iconCard(ctx, icon, icon == current, p) { pickIcon(it) }, iconCell(ctx))
        }
    }

    private fun pickIcon(icon: AppIcon) {
        if (icon.key == Prefs.appIconKey) return
        AppIcon.apply(requireContext(), icon)
        Toast.makeText(requireContext(), R.string.appearance_icon_applied, Toast.LENGTH_SHORT).show()
        bind()
    }

    private fun iconCell(ctx: Context) = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
    }

    private fun iconCard(ctx: Context, icon: AppIcon, selected: Boolean, p: ThemePalette, onPick: (AppIcon) -> Unit): View {
        val surface = ctx.getColor(R.color.surface)
        val frame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = getString(R.string.appearance_icon_option, icon.label)
            isSelected = selected
            radioRole(this, selected)
            isClickable = true
            isFocusable = true
            foreground = AppCompatResources.getDrawable(ctx, selectableBackground(ctx))
            setOnClickListener { onPick(icon) }
            val pad = dp(ctx, 6)
            setPadding(pad, pad, pad, pad)
            background = if (selected) rounded(ctx, p.surfaceTint, 16, p.withAlpha(0x88), 1)
            else rounded(ctx, surface, 16, ctx.getColor(R.color.stroke), 1)
        }
        frame.addView(ImageView(ctx).apply {
            setImageResource(icon.previewRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            background = rounded(ctx, 0, 18)
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }, LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 64)))
        frame.addView(TextView(ctx).apply {
            text = icon.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (selected) p.accentText else ctx.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            setPadding(0, dp(ctx, 6), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return frame
    }

    // ------------------------------------------------------------------ swatch card

    /**
     * Swatch card (SPEC: surface, 16dp radius, 42dp gradient chip with the wolf in onAccent, name, hex pair). The
     * selected card gets a 2dp gradient border, a soft accent glow and a check badge.
     */
    private fun presetCard(ctx: Context, preset: ThemePreset, selected: Boolean, night: Boolean, onPick: (ThemePreset) -> Unit): View {
        val p = AppTheme.palette(preset, night)
        val surface = ctx.getColor(R.color.surface)
        // Outer frame leaves room for the glow and the badge that sits on the corner.
        val frame = FrameLayout(ctx).apply {
            val m = dp(ctx, 3)
            setPadding(m, m, m, m)
            clipToPadding = false
            clipChildren = false
            contentDescription = getString(R.string.appearance_preset, preset.label)
            isSelected = selected
            radioRole(this, selected)
            setOnClickListener { onPick(preset) }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 6)
            setPadding(pad, pad, pad, pad)
            background = if (selected) selectedCardBackground(ctx, p, surface)
            else rounded(ctx, surface, 16, ctx.getColor(R.color.stroke), 1)
            foreground = AppCompatResources.getDrawable(ctx, selectableBackground(ctx))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        card.addView(FrameLayout(ctx).apply {
            background = gradient(ctx, p, 10)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_beast_logo)
                imageTintList = ColorStateList.valueOf(p.onColor)
                alpha = 0.85f
            }, FrameLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(ctx, 5), dp(ctx, 5))
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 42)))
        card.addView(TextView(ctx).apply {
            text = preset.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(ctx.getColor(R.color.text_primary))
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP) // narrow phones: shrink, don't cut
            setPaddingRelative(dp(ctx, 2), dp(ctx, 6), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 22)))
        card.addView(TextView(ctx).apply {
            text = hexPair(preset)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.MONOSPACE
            setTextColor(ctx.getColor(R.color.text_secondary))
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(5, 9, 1, TypedValue.COMPLEX_UNIT_SP)
            setPaddingRelative(dp(ctx, 2), 0, 0, dp(ctx, 2))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 13)))
        frame.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        if (selected) frame.addView(ImageView(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color); setStroke(dp(ctx, 2), surface) }
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(p.onColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val ip = dp(ctx, 3)
            setPadding(ip, ip, ip, ip)
        }, FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20), Gravity.TOP or Gravity.END).apply {
            setMargins(0, -dp(ctx, 3), -dp(ctx, 3), 0)
        })
        return frame
    }

    /** Glow (accent at 27%, fading out over 4dp), then a 2dp gradient border around the surface. */
    private fun selectedCardBackground(ctx: Context, p: ThemePalette, surface: Int): Drawable {
        val r = 16
        val glowOuter = rounded(ctx, 0, r + 4, p.withAlpha(0x22), dp(ctx, 2), px = true)
        val glowInner = rounded(ctx, 0, r + 2, p.withAlpha(0x45), dp(ctx, 2), px = true)
        val border = gradient(ctx, p, r)
        val inner = rounded(ctx, surface, r - 2)
        val g = dp(ctx, 4); val gi = dp(ctx, 2); val bw = dp(ctx, 2)
        return LayerDrawable(arrayOf(glowOuter, glowInner, border, inner)).apply {
            setLayerInset(0, -g, -g, -g, -g)
            setLayerInset(1, -gi, -gi, -gi, -gi)
            setLayerInset(3, bw, bw, bw, bw)
        }
    }

    companion object {
        /** "FF2D55 · E040FB", as on the swatch cards. */
        fun hexPair(preset: ThemePreset): String =
            ThemeColor.hex(preset.accentStart).removePrefix("#") + " · " + ThemeColor.hex(preset.accentEnd).removePrefix("#")

        /** Summary of the Settings row: "Blood Moon · Dark mode". */
        fun rowSummary(f: Fragment): String {
            val keys = f.resources.getStringArray(R.array.ui_theme_keys)
            val names = f.resources.getStringArray(R.array.ui_theme_names)
            val mode = names.getOrElse(keys.indexOf(Prefs.uiTheme)) { names[0] }
            return f.getString(R.string.pref_appearance_summary, Prefs.theme.label, mode)
        }

        private fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeWidth: Int = 0, px: Boolean = false) =
            GradientDrawable().apply {
                cornerRadius = dp(ctx, radiusDp).toFloat()
                setColor(fill)
                if (strokeWidth > 0) setStroke(if (px) strokeWidth else dp(ctx, strokeWidth), stroke)
            }

        /** The 135° accentStart → accentEnd gradient (decorative fills only, SPEC "Theme tokens"). */
        private fun gradient(ctx: Context, p: ThemePalette, radiusDp: Int) =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, p.gradient).apply { cornerRadius = dp(ctx, radiusDp).toFloat() }

        /** Fill behind text: the gradient, or the solid accent where the gradient can't carry text (Ghost). */
        private fun fill(ctx: Context, p: ThemePalette, radiusDp: Int) =
            if (p.gradientCarriesText) gradient(ctx, p, radiusDp) else rounded(ctx, p.color, radiusDp)

        private fun ThemePalette.glow(): Int = withAlpha(0x55)

        private fun selectableBackground(ctx: Context): Int {
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            return tv.resourceId
        }

        /** Announced as a selectable option ("selected" / "not selected"), like a radio button. */
        private fun radioRole(v: View, checked: Boolean) {
            ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.setChecked(if (checked) AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE else AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE)
                }
            })
        }
    }
}
