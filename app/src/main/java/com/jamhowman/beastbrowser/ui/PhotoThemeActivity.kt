package com.jamhowman.beastbrowser.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.ImageSlot
import com.jamhowman.beastbrowser.data.PhotoImage
import com.jamhowman.beastbrowser.data.PhotoTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.RealmThemes
import com.jamhowman.beastbrowser.data.ThemeColor
import com.jamhowman.beastbrowser.data.ThemeContrast
import com.jamhowman.beastbrowser.data.ThemePacks
import com.jamhowman.beastbrowser.widget.SpeedDialWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2.11 Settings › Appearance › "Create theme from photo" (Designer's flow):
 * 1. system photo picker (no storage permission) → crop to the home slot, text zones shaded (top 12%, 22–70%);
 * 2. 4–6 accent swatches from the photo, unreadable ones greyed out ("too low contrast");
 * 3. live preview (home, toolbar, a settings row), darkening slider clamped at the readable minimum, colour tweaks
 *    auto-fixed to 4.5:1;
 * 4. name → save as a pack in app-private storage, optionally apply to the current realm or export (.ulfurtheme).
 * The original photo is never written anywhere; only the re-encoded crop is stored, inside the app's own files.
 */
class PhotoThemeActivity : AppCompatActivity() {
    private val pad get() = dp(this, 16)
    private val night get() = AppTheme.isNight(this)
    private var source: Bitmap? = null
    private var fx = 0.5f
    private var fy = 0.5f
    private var draft: PhotoTheme.Draft? = null
    private var swatches: List<PhotoTheme.Swatch> = emptyList()
    private lateinit var body: LinearLayout
    private var nameInput: EditText? = null

    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finish() else load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.theme_from_photo)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(body) })
        File(cacheDir, EXPORT_DIR).deleteRecursively() // old exports never linger
        if (savedInstanceState == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else finish() // nothing of the photo is persisted across process death, by design
    }

    override fun onDestroy() {
        super.onDestroy()
        source?.recycle(); source = null
    }

    private fun load(uri: Uri) {
        body.removeAllViews(); body.addView(text(getString(R.string.theme_from_photo_reading), 14f))
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val bytes = try { contentResolver.openInputStream(uri)?.use { PhotoImage.readCapped(it) } } catch (_: Exception) { ByteArray(0) }
                PhotoImage.decode(bytes)
            }
            when (r) {
                is PhotoImage.Result.Refused -> { Toast.makeText(this@PhotoThemeActivity, r.reason, Toast.LENGTH_LONG).show(); finish() }
                is PhotoImage.Result.Ok -> { source = r.bitmap; recrop(first = true) }
            }
        }
    }

    private fun recrop(first: Boolean = false) {
        val src = source ?: return
        lifecycleScope.launch {
            val img = withContext(Dispatchers.Default) { PhotoImage.homeImage(src, fx, fy) }
            if (img == null) { Toast.makeText(this@PhotoThemeActivity, "Photo can't be read", Toast.LENGTH_LONG).show(); finish(); return@launch }
            val name = nameInput?.text?.toString() ?: "My photo theme"
            val d = withContext(Dispatchers.Default) {
                val old = draft
                if (first || old == null) PhotoTheme.suggest(name, img.sample, image = img)
                else PhotoTheme.suggest(name, img.sample, old.accent, img).let { it.copy(scrim = maxOf(it.scrim, old.scrim)) }
            }
            swatches = PhotoTheme.swatches(img.sample, night)
            draft = d
            render()
        }
    }

    // ------------------------------------------------------------------ screen

    private fun render() {
        val d = draft ?: return
        body.removeAllViews()
        body.addView(header("1 · Crop"))
        body.addView(text("Drag to move the photo. Keep faces out of the shaded text areas.", 13f, secondary = true))
        body.addView(cropView(d), LinearLayout.LayoutParams(dp(this, 162), dp(this, 360)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = pad / 2 })

        body.addView(header("2 · Accent"))
        body.addView(HorizontalScrollView(this).apply {
            addView(LinearLayout(this@PhotoThemeActivity).apply {
                swatches.forEach { s -> addView(swatch(s, d), LinearLayout.LayoutParams(dp(this@PhotoThemeActivity, 64), -2)) }
            })
        })

        body.addView(header("3 · Preview"))
        body.addView(preview(d))
        val min = PhotoTheme.minScrim(d)
        body.addView(text("Darkening behind text: ${(PhotoTheme.clampScrim(d, d.scrim) * 100).toInt()}% (readable from ${(min * 100).toInt()}%)", 13f, secondary = true))
        body.addView(SeekBar(this).apply {
            max = (PhotoTheme.SCRIM_MAX * 100).toInt()
            progress = (PhotoTheme.clampScrim(d, d.scrim) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                    val c = (PhotoTheme.clampScrim(d, p / 100f) * 100).toInt()
                    if (c != p) sb.progress = c // clamped: can't go below the readable minimum
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { draft = d.copy(scrim = PhotoTheme.clampScrim(d, sb.progress / 100f)); render() }
            })
            contentDescription = "Darkening behind text"
        })
        body.addView(text("Colours (${if (night) "dark" else "light"} mode). Tap to change; unreadable choices are fixed automatically.", 13f, secondary = true))
        val m = if (night) d.dark else d.light
        PhotoTheme.EDITABLE.forEach { key -> body.addView(colourRow(key, m.getValue(key))) }

        body.addView(header("4 · Name and save"))
        val name = nameInput?.text?.toString() ?: d.name
        nameInput = EditText(this).apply {
            setText(name); hint = "Theme name"; inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(48))
        }
        body.addView(nameInput)
        val realm = Prefs.realm
        body.addView(button("Save") { save(apply = false) })
        body.addView(button("Save and use in ${realm.label} realm") { save(apply = true) })
        if (!realm.alwaysPrivate) body.addView(button("Export .ulfurtheme…") { export() })
        else body.addView(text("Export is off in Ghost: this theme stays inside Ulfur.", 12f, secondary = true))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun cropView(d: PhotoTheme.Draft): View {
        val img = d.image!!
        val bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Home crop" })
        frame.addView(object : View(this) {
            val p = Paint().apply { color = 0x66000000 }
            override fun onDraw(c: Canvas) {
                ImageSlot.HOME.textZones.forEach { z -> c.drawRect(z[0] * width, z[1] * height, z[2] * width, z[3] * height, p) }
            }
        })
        var lastX = 0f; var lastY = 0f
        frame.setOnTouchListener { v, e ->
            val src = source ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_UP -> {
                    // Dragging the photo moves the focal point the other way.
                    fx = (fx - (e.x - lastX) / v.width * (PhotoImage.HOME_W.toFloat() / src.width).coerceAtMost(1f)).coerceIn(0f, 1f)
                    fy = (fy - (e.y - lastY) / v.height * (PhotoImage.HOME_H.toFloat() / src.height).coerceAtMost(1f)).coerceIn(0f, 1f)
                    recrop()
                }
            }
            true
        }
        return frame
    }

    private fun swatch(s: PhotoTheme.Swatch, d: PhotoTheme.Draft): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(View(this@PhotoThemeActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(s.color)
                if (s.color == d.accent) setStroke(dp(this@PhotoThemeActivity, 3), getColor(R.color.text_primary))
            }
            alpha = if (s.readable) 1f else 0.3f
        }, LinearLayout.LayoutParams(dp(this@PhotoThemeActivity, 40), dp(this@PhotoThemeActivity, 40)))
        if (!s.readable) addView(text("too low contrast", 10f, secondary = true).apply { gravity = Gravity.CENTER })
        isEnabled = s.readable
        contentDescription = ThemeColor.hex(s.color) + if (s.readable) "" else ", too low contrast"
        if (s.readable) setOnClickListener {
            val img = d.image ?: return@setOnClickListener
            draft = PhotoTheme.suggest(d.name, img.sample, s.color, img).let { it.copy(scrim = maxOf(it.scrim, d.scrim)) }
            render()
        }
    }

    /** Mini home (photo + scrim + text), the toolbar and a settings row, painted with the draft's colours. */
    private fun preview(d: PhotoTheme.Draft): View {
        val m = if (night) d.dark else d.light
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            setBackgroundColor(m.getValue("toolbar")); setPadding(pad, pad / 2, pad, pad / 2)
            addView(text("ulfur.app", 14f).apply { setTextColor(m.getValue("onToolbar")) })
        })
        val home = FrameLayout(this)
        d.image?.let { img ->
            home.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        }
        home.addView(View(this).apply {
            setBackgroundColor(m.getValue("background")); alpha = PhotoTheme.clampScrim(d, d.scrim)
        }, FrameLayout.LayoutParams(-1, -1))
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            addView(text("ULFUR", 22f).apply { setTextColor(m.getValue("text")); typeface = Typeface.DEFAULT_BOLD })
            addView(text("Search or type address", 13f).apply {
                setTextColor(m.getValue("textSecondary"))
                background = GradientDrawable().apply { setColor(m.getValue("surfaceVariant")); cornerRadius = dp(this@PhotoThemeActivity, 20).toFloat() }
                setPadding(pad, pad / 2, pad, pad / 2)
            })
        })
        col.addView(home, LinearLayout.LayoutParams(-1, dp(this, 160)))
        col.addView(LinearLayout(this).apply {
            setBackgroundColor(m.getValue("surface")); setPadding(pad, pad / 2, pad, pad / 2); gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@PhotoThemeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Settings row", 15f).apply { setTextColor(m.getValue("text")) })
                addView(text("Secondary text", 12f).apply { setTextColor(m.getValue("textSecondary")) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(" ON ", 12f).apply {
                setTextColor(m.getValue("onAccent"))
                background = GradientDrawable().apply { setColor(m.getValue("accent")); cornerRadius = dp(this@PhotoThemeActivity, 12).toFloat() }
            })
        })
        return col
    }

    private fun colourRow(key: String, c: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(this@PhotoThemeActivity, 48)
        addView(View(this@PhotoThemeActivity).apply {
            background = GradientDrawable().apply { setColor(c); cornerRadius = dp(this@PhotoThemeActivity, 6).toFloat(); setStroke(1, 0x55888888) }
        }, LinearLayout.LayoutParams(dp(this@PhotoThemeActivity, 28), dp(this@PhotoThemeActivity, 28)))
        addView(text("  $key  ${ThemeColor.hex(c)}", 14f))
        isClickable = true; isFocusable = true
        setOnClickListener { pickColour(key, c) }
    }

    /** Simple picker: the photo's swatches, black/white, or a hex code. Readability is enforced on apply. */
    private fun pickColour(key: String, current: Int) {
        val d = draft ?: return
        val input = EditText(this).apply { setText(ThemeColor.hex(current)); inputType = InputType.TYPE_CLASS_TEXT }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val chips = LinearLayout(this)
        (swatches.map { it.color } + listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())).forEach { col ->
            chips.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(1, 0x55888888) }
                setOnClickListener { input.setText(ThemeColor.hex(col)) }
                contentDescription = ThemeColor.hex(col)
            }, LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)).apply { marginEnd = dp(this@PhotoThemeActivity, 6) })
        }
        row.addView(chips); row.addView(input)
        MaterialAlertDialogBuilder(this).setTitle(key).setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val col = runCatching { ThemeColor.parse(input.text.toString().trim()) }.getOrNull()
                if (col == null) { Toast.makeText(this, "Not a colour", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val (nd, fixed) = PhotoTheme.tweak(d, night, key, col)
                draft = nd
                if (fixed) Toast.makeText(this, "Adjusted so text stays readable (4.5:1)", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ save / export

    private fun currentPack() = draft?.let { PhotoTheme.toPack(it.copy(name = nameInput?.text?.toString().orEmpty())) }

    private fun save(apply: Boolean) {
        val pack = currentPack() ?: return
        val store = ThemePacks.store() ?: return
        // Last line of defence: the same readability check as import.
        if (listOf(true, false).any { ThemeContrast.warnings(pack, it).isNotEmpty() }) {
            Toast.makeText(this, "Some text would be hard to read; adjust the colours", Toast.LENGTH_LONG).show(); return
        }
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { store.install(pack) }
            if (apply) store.realms.set(Prefs.realm, RealmThemes.Choice.Pack(id))
            SpeedDialWidget.refreshAll(this@PhotoThemeActivity)
            Toast.makeText(this@PhotoThemeActivity, if (apply) "Saved and applied to ${Prefs.realm.label}" else "Saved “${pack.name}”", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK); finish()
        }
    }

    private fun export() {
        val pack = currentPack() ?: return
        if (Prefs.realm.alwaysPrivate) return
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, EXPORT_DIR).apply { mkdirs() }
                val safe = pack.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "theme" }.take(40)
                File(dir, safe + ".ulfurtheme").apply { writeBytes(PhotoTheme.export(pack)) }
            }
            val uri = FileProvider.getUriForFile(this@PhotoThemeActivity, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), pack.name))
        }
    }

    // ------------------------------------------------------------------ bits

    private fun header(t: String) = text(t, 17f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, pad, 0, pad / 3) }
    private fun text(t: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
    }
    private fun button(t: String, onClick: () -> Unit) = MaterialButton(this).apply { text = t; setOnClickListener { onClick() } }

    companion object {
        /** App-private cache only (FileProvider grant on share); never the shared gallery or shared storage. */
        const val EXPORT_DIR = "theme-exports"
    }
}
