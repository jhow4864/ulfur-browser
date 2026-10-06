package com.jamhowman.beastbrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.databinding.ActivityReadingListBinding
import com.jamhowman.beastbrowser.reader.SavedArticle
import com.jamhowman.beastbrowser.ui.ReadingAdapter
import com.jamhowman.beastbrowser.ui.accentLine
import com.jamhowman.beastbrowser.ui.glow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Layout preview of the Reading list screen (sample data). Output: preview.dir (../beast-browser-screens/). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderPreviewTest {
    private val out = File(System.getProperty("preview.dir") ?: "build/previews").apply { mkdirs() }

    private fun render(v: View, name: String) {
        val w = 1080; val h = 2340
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF0E0E12.toInt()); v.draw(this) }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderReadingList() {
        val accent = ThemePalette.of(ThemePreset.BLOOD_MOON, night = false)
        val c = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Beast).apply {
            theme.applyStyle(accent.preset.overlay, true); Prefs.init(this)
        }
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        fun a(id: Long, site: String, title: String, excerpt: String, by: String, ago: Long, words: Int, read: Boolean) =
            SavedArticle(id, "https://$site/sample-$id", title, by, site, excerpt, "", "en", "", "", now - ago, words, read)
        val sample = listOf(
            a(1, "example.org", "Sample article: How small browsers stay fast", "SAMPLE DATA. A preview entry showing title, excerpt and reading time.", "Sample Author", 2 * 60_000, 1840, false),
            a(2, "news.example.com", "Sample long read with a title that wraps onto a second line in the card", "Placeholder excerpt for the layout preview.", "", 3 * h, 4200, false),
            a(3, "blog.example.net", "Sample notes on offline reading", "Saved articles open in Reader view without a connection.", "Example Blog", 26 * h, 950, true),
            a(4, "example.io", "Sample: a short one", "", "", 5 * 24 * h, 300, true),
        )
        val b = ActivityReadingListBinding.inflate(LayoutInflater.from(c))
        b.toolbar.subtitle = "SAMPLE DATA · 4 saved · 2 unread"
        accentLine(b.readingAccentLine, accent.color, 0x99)
        val adapter = ReadingAdapter(accent, {}, {})
        adapter.items = sample
        b.readingList.layoutManager = LinearLayoutManager(c)
        b.readingList.adapter = adapter
        render(b.root, "reading_list.png")

        val e = ActivityReadingListBinding.inflate(LayoutInflater.from(c))
        accentLine(e.readingAccentLine, accent.color, 0x99)
        e.readingEmpty.isVisible = true
        e.readingEmptyArt.imageTintList = android.content.res.ColorStateList.valueOf(accent.color)
        glow(e.readingEmptyArt, accent.color)
        render(e.root, "reading_list_empty.png")
    }
}
