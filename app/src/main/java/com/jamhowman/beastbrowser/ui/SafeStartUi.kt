package com.jamhowman.beastbrowser.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.ThemePacks

/**
 * 2.9 safe start screen and bar (design-drop-2.9.md §2). Shown by MainActivity when [SafeStart.active].
 * "Share crash report" opens the existing [CrashReportUi] viewer, which shows the saved text before anything goes
 * anywhere (and only opens a pre-filled GitHub issue page; the app sends nothing itself).
 */
object SafeStartUi {
    private const val BAR_TAG = "safe-start-bar"

    fun show(activity: MainActivity, topBar: View) {
        showBar(activity, topBar)
        if (SafeStart.awaitingChoice) showScreen(activity, topBar)
    }

    private fun showScreen(activity: MainActivity, topBar: View) {
        val ctx = activity
        val again = SafeStart.launch?.crashedAfterRestore == true
        val themeWasPack = ThemePacks.store()?.activeId() != null
        val pad = dp(ctx, 24)
        val dim = ctx.getColor(R.color.text_secondary)
        val toggles = mutableMapOf<String, CheckBox>()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_beast_logo)
                imageTintList = ColorStateList.valueOf(dim)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(TextView(ctx).apply {
                setText(R.string.safe_title); textSize = 20f; gravity = Gravity.CENTER
                setTextColor(ctx.getColor(R.color.text_primary)); setPadding(0, pad / 2, 0, pad / 4)
            })
            addView(TextView(ctx).apply { setText(R.string.safe_body); gravity = Gravity.CENTER; setTextColor(dim) })
            if (again) addView(TextView(ctx).apply {
                setText(R.string.safe_again); gravity = Gravity.CENTER; setTextColor(ctx.getColor(R.color.warn)); setPadding(0, pad / 3, 0, 0)
            })
            fun row(key: String, label: Int, status: Int) {
                if (again) {
                    addView(CheckBox(ctx).apply {
                        text = ctx.getString(R.string.safe_row_fmt, ctx.getString(label), ctx.getString(status)); isChecked = false
                        minHeight = dp(ctx, 48); setTextColor(ctx.getColor(R.color.text_primary))
                        toggles[key] = this
                    })
                } else addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.safe_row_fmt, ctx.getString(label), ctx.getString(status))
                    minHeight = dp(ctx, 40); gravity = Gravity.CENTER_VERTICAL; setTextColor(ctx.getColor(R.color.text_primary))
                })
            }
            addView(View(ctx), LinearLayout.LayoutParams(1, pad / 2))
            row("tabs", R.string.safe_row_tabs, R.string.safe_row_tabs_status)
            row("ext", R.string.safe_row_extensions, R.string.safe_row_extensions_status)
            if (themeWasPack) row("theme", R.string.safe_row_theme, R.string.safe_row_theme_status)
        }
        val dialog = MaterialAlertDialogBuilder(ctx).setView(android.widget.ScrollView(ctx).apply { addView(root) })
            .setCancelable(false).create()
        fun button(text: Int, style: Int, onTap: () -> Unit) = MaterialButton(ctx, null, style).apply {
            setText(text); minHeight = dp(ctx, 48)
            setOnClickListener { onTap() }
        }
        root.addView(button(if (again) R.string.safe_restore_selected else R.string.safe_restore, com.google.android.material.R.attr.materialButtonStyle) {
            dialog.dismiss()
            if (again) activity.restoreFromSafeStart(toggles["tabs"]?.isChecked == true, toggles["ext"]?.isChecked == true, toggles["theme"]?.isChecked == true)
            else activity.restoreFromSafeStart(tabs = true, extensions = true, theme = true)
            showBar(activity, topBar)
        }.apply { backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.gx_red)); setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        root.addView(button(R.string.safe_continue, com.google.android.material.R.attr.materialButtonOutlinedStyle) {
            dialog.dismiss(); SafeStart.continueSafe(); showBar(activity, topBar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(button(R.string.safe_share, com.google.android.material.R.attr.borderlessButtonStyle) { share(activity) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    /** Opens the newest saved report in the existing viewer (text shown first). Reports are opt-in. */
    private fun share(activity: MainActivity) {
        val store = CrashReporter.store(activity)
        val newest = if (Prefs.crashReports) store.reports().firstOrNull() else null
        if (newest == null) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.safe_no_report)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        CrashReportUi.showReport(activity, store, newest.file, onReport = { url -> activity.newTab(url) })
    }

    /** Slim amber bar under the address bar while anything is paused: "Safe start · Restore". Not swipeable. */
    private fun showBar(activity: MainActivity, topBar: View) {
        val parent = topBar.parent as? ViewGroup ?: return
        val existing = parent.findViewWithTag<TextView>(BAR_TAG)
        if (!SafeStart.active) { existing?.isVisible = false; return }
        val bar = existing ?: TextView(activity).apply {
            tag = BAR_TAG
            setBackgroundColor(activity.getColor(R.color.warn))
            setTextColor(0xFF0E0E12.toInt())
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(activity, 36)
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            parent.addView(this, parent.indexOfChild(topBar) + 1,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bar.isVisible = true
        bar.text = activity.getString(R.string.safe_row_fmt, activity.getString(R.string.safe_bar), activity.getString(R.string.safe_bar_restore))
        bar.setOnClickListener {
            activity.restoreFromSafeStart(tabs = true, extensions = true, theme = true)
            showBar(activity, topBar)
        }
    }
}
