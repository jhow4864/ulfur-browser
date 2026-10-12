package com.jamhowman.beastbrowser.ui

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.ThemeColor
import com.jamhowman.beastbrowser.data.ThemeContrast
import com.jamhowman.beastbrowser.data.ThemePack
import com.jamhowman.beastbrowser.data.ThemePackError
import com.jamhowman.beastbrowser.data.ThemePackParser
import com.jamhowman.beastbrowser.data.ThemePackResult
import com.jamhowman.beastbrowser.data.ThemePackStore
import com.jamhowman.beastbrowser.data.ThemePacks
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.widget.SpeedDialWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2.9 theme-pack preview (design-drop-2.9.md §1). Opened from Settings › Appearance › Import theme pack, or when
 * another app opens a .ulfurtheme file. The file is read (capped) and validated before anything is drawn; bad packs
 * get one plain error and the activity closes. Nothing is stored or changed until Apply; then a 10 s Undo puts the
 * exact previous state back ([ThemePackStore.undo]).
 */
class ThemePackPreviewActivity : AppCompatActivity() {
    private lateinit var screen: ThemedScreen
    private var pack: ThemePack? = null
    private var night = true
    private var applied: ThemePackStore.Snapshot? = null
    private val highlight = mutableMapOf<String, MutableList<View>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        screen = ThemedScreen(this)
        super.onCreate(savedInstanceState)
        ThemePacks.init(this)
        night = AppTheme.isNight(this)
        val uri = intent?.data ?: return finish()
        if (!acceptsUri(uri)) return showError(ThemePackError.NOT_A_THEME) // 2.9.1: content:// only
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { read(uri) }
            when (result) {
                is ThemePackResult.Ok -> { pack = result.pack; if (!result.pack.hasDark) night = false; if (!result.pack.hasLight) night = true; render() }
                is ThemePackResult.Bad -> showError(result.error)
            }
        }
    }

    private fun read(uri: Uri): ThemePackResult = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val cap = ThemePackParser.MAX_FILE_BYTES + 1
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < cap) {
                val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            ThemePackParser.parse(out.toByteArray())
        } ?: ThemePackResult.Bad(ThemePackError.NOT_A_THEME, "no data")
    } catch (e: Exception) {
        ThemePackResult.Bad(ThemePackError.NOT_A_THEME, e.javaClass.simpleName)
    }

    private fun showError(e: ThemePackError) {
        MaterialAlertDialogBuilder(this).setMessage(
            when (e) {
                ThemePackError.NOT_A_THEME -> R.string.theme_bad_file
                ThemePackError.TOO_LARGE -> R.string.theme_too_large
                ThemePackError.BAD_CONTENTS -> R.string.theme_bad_contents
            }
        ).setPositiveButton(android.R.string.ok, null).setOnDismissListener { finish() }.show()
    }

    private fun render() {
        val p = pack ?: return
        val pal = p.palette(night)
        highlight.clear()
        val pad = dp(this, 20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        col.addView(text(p.name, 22f, getColor(R.color.text_primary), bold = true))
        col.addView(text(listOf(p.author, p.version).filter { it.isNotBlank() }.joinToString(" · "), 14f, getColor(R.color.text_secondary)))
        col.addView(text(getString(R.string.theme_unverified), 13f, getColor(R.color.text_secondary)))
        if (p.hasDark && p.hasLight) {
            col.addView(MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true; isSelectionRequired = true
                val dark = MaterialButton(this@ThemePackPreviewActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); setText(R.string.theme_preview_dark) }
                val light = MaterialButton(this@ThemePackPreviewActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); setText(R.string.theme_preview_light) }
                addView(dark); addView(light)
                check(if (night) dark.id else light.id)
                addOnButtonCheckedListener { _, id, checked -> if (checked && (id == dark.id) != night) { night = id == dark.id; render() } }
            })
        }
        col.addView(mockup(pal), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        col.addView(swatches(p), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        // Contrast from the very palette Apply paints with, for every mode the pack sets.
        val low = listOfNotNull(if (p.hasDark) p.palette(true) else null, if (p.hasLight) p.palette(false) else null)
            .flatMap { ThemeContrast.lowContrast(it) }
        if (low.isNotEmpty()) col.addView(text(getString(R.string.theme_low_contrast), 14f, 0xFF0E0E12.toInt()).apply {
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            background = GradientDrawable().apply { setColor(getColor(R.color.warn)); cornerRadius = dp(this@ThemePackPreviewActivity, 8).toFloat() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(pad, pad / 2, pad, pad) }
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(if (applied == null) android.R.string.cancel else R.string.done); minHeight = dp(context, 48); setOnClickListener { finish() }
        })
        if (applied == null) bar.addView(MaterialButton(this).apply {
            setText(R.string.theme_apply); minHeight = dp(context, 48)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.gx_red))
            setOnClickListener { apply(p) }
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@ThemePackPreviewActivity).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
            setBackgroundColor(getColor(R.color.bg))
        }
        setContentView(root)
    }

    private fun apply(p: ThemePack) {
        val store = ThemePacks.store() ?: return
        applied = try { store.apply(p) } catch (_: Exception) { return showError(ThemePackError.BAD_CONTENTS) }
        themeChanged()
        val root = findViewById<View>(android.R.id.content)
        Snackbar.make(root, R.string.theme_applied, UNDO_MS).setAction(R.string.undo) {
            applied?.let { store.undo(it) }
            applied = null
            themeChanged()
        }.show()
    }

    private fun themeChanged() {
        SpeedDialWidget.refreshAll(this)
        val root = findViewById<View>(android.R.id.content)
        if (ValueAnimator.areAnimatorsEnabled()) { root.alpha = 0.4f; root.animate().alpha(1f).setDuration(200).start() }
        screen.refresh()
        render()
    }

    // ------------------------------------------------------------------ mock-up (≈70% scale)

    private fun mockup(p: ThemePalette): View {
        val s = 0.7f
        val bg = if (p.night) ThemeContrast.BG_DARK else ThemeContrast.BG_LIGHT
        val surface = if (p.night) ThemeContrast.SURFACE_DARK else ThemeContrast.SURFACE_LIGHT
        val ink = if (p.night) 0xFFF2F2F7.toInt() else 0xFF121218.toInt()
        val r = dp(this, 10).toFloat()
        fun mark(key: String, v: View) = v.also { highlight.getOrPut(key) { mutableListOf() } += it }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (dp(context, 12) * s).toInt()
            setPadding(pd, pd, pd, pd)
            background = GradientDrawable().apply { setColor(bg); cornerRadius = r; setStroke(dp(context, 1), p.withAlpha(0x55)) }
        }
        // Toolbar with address bar
        box.addView(mark("surfaceTint", LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(p.surfaceTint); cornerRadius = r * 2 }
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            addView(mark("accent", text("◆", 12f, p.color)))
            addView(text("  example.com", 12f, ink))
        }))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(this, 8)))
        // Tab card
        box.addView(mark("accentText", text(getString(R.string.theme_preview_tab), 12f, p.accentText).apply {
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply { setColor(surface); cornerRadius = r; setStroke(dp(context, 2), p.color) }
        }))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(this, 8)))
        // Menu sheet
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(surface); cornerRadius = r }
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            listOf("☰", "★", "⬇", "⚙").forEach { addView(mark("accent", text("$it   ", 13f, p.color))) }
            addView(mark("eye", text("●", 13f, p.eye)))
        })
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(this, 8)))
        // Filled button on the gradient
        box.addView(mark("onAccent", text(getString(R.string.theme_apply), 13f, p.onColor, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, p.gradient).apply { cornerRadius = r * 2 }
            highlight.getOrPut("accentStart") { mutableListOf() } += this
            highlight.getOrPut("accentEnd") { mutableListOf() } += this
        }))
        return box
    }

    private fun swatches(p: ThemePack): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pal = p.palette(night)
        val values = mapOf("accent" to pal.color, "accentStart" to pal.accentStart, "accentEnd" to pal.accentEnd,
            "onAccent" to pal.onColor, "accentText" to pal.accentText, "surfaceTint" to pal.surfaceTint, "eye" to pal.eye)
        ThemePackParser.COLOR_KEYS.forEach { key ->
            val c = values.getValue(key)
            val isDefault = p.colorFor(key, night) == null
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(context, 6), 0, dp(context, 6), 0)
                minimumWidth = dp(context, 64); minimumHeight = dp(context, 48)
                addView(View(context).apply {
                    background = GradientDrawable().apply { setColor(c); cornerRadius = dp(context, 18).toFloat(); setStroke(dp(context, 1), 0x33888888) }
                }, LinearLayout.LayoutParams(dp(this@ThemePackPreviewActivity, 36), dp(this@ThemePackPreviewActivity, 36)))
                addView(text(label(key), 11f, getColor(R.color.text_primary)))
                addView(text(ThemeColor.hex(c), 10f, getColor(R.color.text_secondary)))
                if (isDefault) addView(text(getString(R.string.theme_default_tag), 10f, getColor(R.color.text_secondary)))
                contentDescription = label(key) + " " + ThemeColor.hex(c)
                setOnClickListener { pulse(key) }
            })
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun pulse(key: String) = highlight[key].orEmpty().forEach { v ->
        v.animate().cancel()
        v.alpha = 0.25f
        v.animate().alpha(1f).setDuration(600).start()
    }

    private fun label(key: String) = when (key) {
        "accent" -> getString(R.string.theme_sw_accent)
        "accentStart" -> getString(R.string.theme_sw_start)
        "accentEnd" -> getString(R.string.theme_sw_end)
        "onAccent" -> getString(R.string.theme_sw_on_accent)
        "accentText" -> getString(R.string.theme_sw_text)
        "surfaceTint" -> getString(R.string.theme_sw_surface)
        else -> getString(R.string.theme_sw_eye)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    companion object {
        const val UNDO_MS = 10_000
        /** 2.9.1: only content:// (file pickers, file managers). file:// and anything else is refused. */
        fun acceptsUri(uri: Uri?): Boolean = uri?.scheme.equals("content", ignoreCase = true) && !uri?.authority.isNullOrEmpty()
    }
}
