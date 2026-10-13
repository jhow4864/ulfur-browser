package com.jamhowman.beastbrowser.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.SitePermissionController
import com.jamhowman.beastbrowser.browser.SitePermissions
import com.jamhowman.beastbrowser.browser.SitePermissions.Kind
import com.jamhowman.beastbrowser.browser.SitePermissions.State
import com.jamhowman.beastbrowser.browser.SitePermissionsEngine

/**
 * 2.12 Settings › Privacy › Site permissions (docs/design-drop-2.12.md §A). List screen (chips, search, most recently
 * used first) or, with [EXTRA_ORIGIN], one site's detail page (Allow · Ask · Block). Every change applies at once and
 * offers Undo for 10 s. Only normal browsing is shown: Private / Ghost grants are session-only.
 */
class SitePermissionsActivity : AppCompatActivity() {
    companion object { const val EXTRA_ORIGIN = "origin"; private const val UNDO_MS = 10_000 }

    private lateinit var body: LinearLayout
    private lateinit var root: FrameLayout
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }
    private val controller: SitePermissionController by lazy { SitePermissionsEngine.controller(this) }
    private var sites: List<SitePermissions.Site> = emptyList()
    private var chip = SitePermissions.Chip.ALL
    private var query = ""
    private val origin: String? get() = intent.getStringExtra(EXTRA_ORIGIN)
    private var list: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        title = origin?.substringAfter("://") ?: getString(R.string.site_perms_title)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(this).apply { addView(body) }
        root = FrameLayout(this).apply { addView(scroll); setBackgroundColor(getColor(R.color.bg)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        if (origin == null) buildListChrome()
        reload()
    }

    override fun onResume() { super.onResume(); reload() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (origin == null) menu.add(0, 1, 0, R.string.site_perms_reset_all)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        MaterialAlertDialogBuilder(this).setMessage(R.string.site_perms_reset_all_confirm)
            .setPositiveButton(R.string.site_perms_reset) { _, _ ->
                val all = sites.flatMap { it.entries }
                val undo = controller.apply(all, State.ASK)
                reloadSoon(); undoBar(getString(R.string.site_perms_reset_all_done), undo)
            }.setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun reload() = SitePermissionsEngine.load(this) { s -> sites = s; render() }
    /** Gecko writes are async; read back shortly after so the screen shows Gecko's stored state. */
    private fun reloadSoon() { render(); root.postDelayed({ reload() }, 250) }

    private fun text(t: CharSequence, size: Float = 15f, secondary: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
    }

    private fun buildListChrome() {
        val group = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        SitePermissions.Chip.entries.forEach { c ->
            group.addView(Chip(this).apply {
                text = c.label; isCheckable = true; isChecked = c == chip
                setOnClickListener { chip = c; render() }
            })
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(group) })
        body.addView(EditText(this).apply {
            hint = getString(R.string.site_perms_search); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render() }
            })
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
    }

    private fun render() { if (origin == null) renderList() else renderDetail(origin!!) }

    private fun favicon(origin: String, sizeDp: Int): View {
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        val letter = text(origin.substringAfter("://").removePrefix("www.").take(1).uppercase(), sizeDp * 0.5f).apply {
            gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_tile)
        }
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(px, px); addView(letter) }
        // Cache only: the dashboard never goes to the network for icons.
        QuickPickIconLoader.load(this, origin, allowNetwork = false, fromPrivate = false) { bmp ->
            if (bmp != null) { frame.removeAllViews(); frame.addView(ImageView(this).apply { setImageBitmap(bmp) }) }
        }
        return frame
    }

    private fun glyph(k: Kind) = when (k) {
        Kind.CAMERA -> "📷"; Kind.MIC -> "🎙"; Kind.LOCATION -> "📍"; Kind.NOTIFICATIONS -> "🔔"
        Kind.AUTOPLAY_AUDIBLE, Kind.AUTOPLAY_INAUDIBLE -> "▶"; Kind.DRM -> "🔒"; else -> "•"
    }

    private fun renderList() {
        val l = list ?: return
        l.removeAllViews()
        val shown = SitePermissions.filter(sites, chip, query)
        if (shown.isEmpty()) {
            l.addView(text("🛡", 40f).apply { gravity = Gravity.CENTER; setPadding(0, pad * 2, 0, pad / 2) })
            l.addView(text(getString(R.string.site_perms_empty), secondary = true).apply { gravity = Gravity.CENTER })
        }
        shown.forEach { s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (72 * resources.displayMetrics.density).toInt()
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { startActivity(Intent(this@SitePermissionsActivity, SitePermissionsActivity::class.java).putExtra(EXTRA_ORIGIN, s.origin)) }
            }
            row.addView(favicon(s.origin, 24))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad, 0) }
            col.addView(text(s.domain))
            val (g, more) = s.glyphs()
            // State in text too (never colour alone): "📷 Blocked · 🔔 Allowed".
            val summary = g.joinToString("  ") { "${glyph(it.kind)} ${stateWord(it.state)}" } + if (more > 0) "  +$more" else ""
            col.addView(text(summary.ifEmpty { getString(R.string.site_perms_all_ask) }, 13f, secondary = true))
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text("›", 22f, secondary = true))
            row.contentDescription = s.domain + ", " + s.entries.joinToString { "${it.kind.label} ${stateWord(it.state)}" }
            l.addView(row)
        }
        l.addView(text(getString(R.string.site_perms_session_note), 12f, secondary = true).apply { setPadding(0, pad, 0, 0) })
    }

    private fun stateWord(s: State) = when (s) { State.ALLOW -> "Allowed"; State.ASK -> "Ask"; State.BLOCK -> "Blocked" }

    private fun renderDetail(origin: String) {
        body.removeAllViews()
        val site = sites.firstOrNull { it.origin == origin }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(favicon(origin, 40))
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 0, 0, 0) }
        hc.addView(text(origin.substringAfter("://"), 18f))
        val last = site?.lastUsed ?: 0L
        hc.addView(text(if (last > 0) getString(R.string.site_perms_last_visited, DateUtils.getRelativeTimeSpanString(last)) else origin, 13f, secondary = true))
        head.addView(hc)
        body.addView(head)
        if (site == null || site.entries.isEmpty()) {
            body.addView(text(getString(R.string.site_perms_empty), secondary = true).apply { setPadding(0, pad, 0, 0) })
        } else site.entries.forEach { e -> body.addView(detailRow(e)) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, 0) }
        buttons.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.site_perms_reset_site); setTextColor(getColor(R.color.danger))
            isEnabled = site != null
            setOnClickListener {
                val undo = controller.apply(site!!.entries, State.ASK)
                reloadSoon(); undoBar(getString(R.string.site_perms_reset_site_done, site.domain), undo)
            }
        })
        buttons.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.site_perms_clear_data)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SitePermissionsActivity).setMessage(getString(R.string.site_perms_clear_data_confirm, origin.substringAfter("://")))
                    .setPositiveButton(R.string.site_perms_clear_data) { _, _ -> SitePermissionsEngine.clearSiteData(origin) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        })
        body.addView(buttons)
        body.addView(text(getString(R.string.site_perms_session_note), 12f, secondary = true).apply { setPadding(0, pad, 0, 0) })
    }

    private fun detailRow(e: SitePermissions.Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * resources.displayMetrics.density).toInt()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("${glyph(e.kind)}  ${e.kind.label}"))
        if (e.state == State.ALLOW && e.lastUsed > 0)
            col.addView(text(getString(R.string.site_perms_used, DateUtils.getRelativeTimeSpanString(e.lastUsed)), 12f, secondary = true))
        if (!e.kind.allowSupported) col.addView(text(getString(R.string.site_perms_allow_unsupported), 12f, secondary = true))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = e.state.label + " ▾"
            contentDescription = "${e.kind.label}: ${e.state.label}"
            setOnClickListener { v ->
                PopupMenu(this@SitePermissionsActivity, v).apply {
                    State.entries.forEach { s ->
                        menu.add(0, s.ordinal, s.ordinal, s.label).isEnabled = s != State.ALLOW || e.kind.allowSupported
                    }
                    setOnMenuItemClickListener { mi ->
                        val s = State.entries[mi.itemId]
                        if (s != e.state) {
                            val undo = controller.apply(e, s)
                            reloadSoon()
                            undoBar("${e.kind.label} ${stateWord(s).lowercase()} for ${e.origin.substringAfter("://")}", undo)
                        }
                        true
                    }
                }.show()
            }
        })
        return row
    }

    private fun undoBar(msg: String, undo: SitePermissionController.Undo) {
        Snackbar.make(root, msg, UNDO_MS).setAction(R.string.undo) { controller.undo(undo); reloadSoon() }.show()
    }
}
