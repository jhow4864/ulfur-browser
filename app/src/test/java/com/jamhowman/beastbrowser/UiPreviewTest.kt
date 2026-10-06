package com.jamhowman.beastbrowser

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.data.Accent
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityMainBinding
import com.jamhowman.beastbrowser.databinding.ActivityDownloadsBinding
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadItem
import com.jamhowman.beastbrowser.ui.DownloadAdapter
import com.jamhowman.beastbrowser.ui.DownloadsActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jamhowman.beastbrowser.databinding.ItemMenuBinding
import com.jamhowman.beastbrowser.databinding.ItemTabCardBinding
import com.jamhowman.beastbrowser.databinding.SheetMenuBinding
import com.jamhowman.beastbrowser.databinding.SheetShieldsBinding
import com.jamhowman.beastbrowser.ui.SpeedDialAdapter
import com.jamhowman.beastbrowser.ui.SpeedDialStore
import com.jamhowman.beastbrowser.ui.accentLine
import com.jamhowman.beastbrowser.ui.dp
import com.jamhowman.beastbrowser.ui.glow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Opera GX-style layouts to PNG (layout-only preview: the GeckoView engine can't run under
 * Robolectric, so web content areas are empty). Output: ../beast-browser-screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiPreviewTest {
    private val out = File(System.getProperty("preview.dir") ?: "build/previews").apply { mkdirs() }

    private fun ctx(accent: Accent) = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Beast).apply {
        theme.applyStyle(accent.overlay, true)
        Prefs.init(this)
    }

    private fun render(v: View, w: Int, h: Int, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF0E0E12.toInt()); v.draw(this) }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(accent: Accent, file: String) {
        val c = ctx(accent)
        val b = ActivityMainBinding.inflate(LayoutInflater.from(c))
        val col = accent.color
        b.content.setPadding(0, dp(c, 24), 0, dp(c, 16))
        accentLine(b.accentLineTop, col, 0x99); accentLine(b.accentLineBottom, col, 0x55)
        b.shieldIcon.imageTintList = ColorStateList.valueOf(col)
        b.btnMenu.imageTintList = ColorStateList.valueOf(col)
        (c.getDrawable(R.drawable.bg_tab_count)!!.mutate() as GradientDrawable).let { it.setStroke(dp(c, 2), col); b.tabCount.background = it }
        b.tabCount.text = "3"
        b.reloadButton.isVisible = false
        b.home.logo.imageTintList = ColorStateList.valueOf(col)
        glow(b.home.logoGlow, col)
        b.home.wordmarkSub.setTextColor(col)
        b.home.homeSearchIcon.imageTintList = ColorStateList.valueOf(col)
        (c.getDrawable(R.drawable.bg_home_search)!!.mutate() as GradientDrawable).let { it.setStroke(dp(c, 1), accent.withAlpha(0x66)); b.home.homeSearch.background = it }
        listOf(b.home.statBlocked, b.home.statData, b.home.statTime, b.home.statsStatus).forEach { it.setTextColor(col) }
        b.home.statsShield.imageTintList = ColorStateList.valueOf(col)
        b.home.speedDialMarker.setBackgroundColor(col)
        b.home.statBlocked.text = "12,408"; b.home.statData.text = "485 MB"; b.home.statTime.text = "10m"; b.home.statsStatus.text = "ETP + uBO"
        b.home.speedDial.layoutManager = GridLayoutManager(c, 4)
        b.home.speedDial.adapter = SpeedDialAdapter(SpeedDialStore.load(), col, {}, {}, {})
        render(b.root, 1080, 2340, file)
    }

    @Test fun renderHomeScreens() {
        home(Accent.RED, "home_gx_red.png")
        home(Accent.CYAN, "home_cyber_cyan.png")
        home(Accent.PURPLE, "home_ultraviolet.png")
    }

    @Test fun renderMenuAndShields() {
        val accent = Accent.RED
        val c = ctx(accent)
        val m = SheetMenuBinding.inflate(LayoutInflater.from(c))
        m.menuShield.imageTintList = ColorStateList.valueOf(accent.color)
        m.menuTitle.text = "37 blocked on this page"; m.menuSubtitle.text = "12,408 ads & trackers blocked in total"
        val items = listOf(R.drawable.ic_add to "New tab", R.drawable.ic_incognito to "Private tab", R.drawable.ic_bookmark to "Bookmarks",
            R.drawable.ic_history to "History", R.drawable.ic_download to "Downloads", R.drawable.ic_bookmark_border to "Bookmark",
            R.drawable.ic_share to "Share", R.drawable.ic_find to "Find in page", R.drawable.ic_desktop to "Desktop site",
            R.drawable.ic_dashboard to "Add to Speed Dial", R.drawable.ic_settings to "Settings", R.drawable.ic_power to "Exit")
        items.forEachIndexed { i, (icon, label) ->
            val ib = ItemMenuBinding.inflate(LayoutInflater.from(c), m.menuGrid, false)
            val active = i == 8
            ib.menuIcon.setImageResource(icon)
            ib.menuIcon.imageTintList = ColorStateList.valueOf(if (active) accent.onColor else c.getColor(R.color.text_primary))
            (c.getDrawable(R.drawable.bg_menu_icon)!!.mutate() as GradientDrawable).let { it.setColor(if (active) accent.color else c.getColor(R.color.surface2)); ib.menuIcon.background = it }
            ib.menuLabel.text = label
            ib.root.layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) }
            m.menuGrid.addView(ib.root)
        }
        m.root.setBackgroundColor(c.getColor(R.color.surface))
        render(m.root, 1080, 1150, "menu_sheet.png")

        val s = SheetShieldsBinding.inflate(LayoutInflater.from(c))
        s.shieldsHost.text = "bbc.co.uk"
        s.shieldsIcon.imageTintList = ColorStateList.valueOf(accent.color)
        s.shieldsPageCount.setTextColor(accent.color)
        s.shieldsPageCount.text = "37"; s.shieldsTotal.text = "12,408"
        s.shieldsBreakdown.text = "uBlock Origin: 31 blocked   ·   Tracking Protection: 6 blocked"
        s.shieldsSwitch.isChecked = true
        s.shieldsSwitch.text = "Shields for this site"
        s.shieldsState.text = "uBlock Origin + Strict ETP (ads, analytics, social, cryptominers, fingerprinters) active on this site"
        s.shieldsList.text = "securepubads.g.doubleclick.net  ×4\nconnect.facebook.net\nwww.google-analytics.com  ×2\nbat.bing.com"
        s.shieldsListInfo.text = "Built-in uBlock Origin 1.75.0 + Firefox Enhanced Tracking Protection (Strict)"
        listOf(s.uboPanelButton, s.uboDashboardButton).forEach { it.setTextColor(accent.color); it.strokeColor = ColorStateList.valueOf(accent.withAlpha(0x88)) }
        s.root.setBackgroundColor(c.getColor(R.color.surface))
        render(s.root, 1080, 1700, "shields_sheet.png")

        val card = ItemTabCardBinding.inflate(LayoutInflater.from(c))
        card.card.strokeColor = accent.color; card.card.strokeWidth = dp(c, 2)
        card.title.text = "BBC News - Home"; card.placeholder.text = "B"; card.placeholder.setTextColor(accent.color)
        card.favicon.imageTintList = ColorStateList.valueOf(accent.color)
        render(card.root, 500, 640, "tab_card.png")
    }

    /** SAMPLE DATA ONLY - illustrates the Downloads screen; nothing here was really downloaded. */
    @Test fun renderDownloads() {
        val accent = Accent.RED
        val c = ctx(accent)
        val mb = 1024L * 1024
        val now = System.currentTimeMillis()
        val sample = listOf(
            DownloadItem(1, "https://example.com/sample-report.pdf", "sample-report.pdf", "application/pdf", "example.com", false,
                DlStatus.DOWNLOADING, downloaded = 21 * mb, total = 48 * mb, speedBps = (2.3 * mb).toLong(), createdAt = now),
            DownloadItem(2, "https://cdn.example.org/sample-video.mp4", "sample-video.mp4", "video/mp4", "cdn.example.org", true,
                DlStatus.QUEUED, createdAt = now - 1),
            DownloadItem(3, "https://media.example.net/sample-podcast-ep12.mp3", "sample-podcast-ep12.mp3", "audio/mpeg", "media.example.net", false,
                DlStatus.PAUSED, downloaded = 9 * mb, total = 31 * mb, createdAt = now - 2),
            DownloadItem(4, "https://files.example.io/sample-dataset.zip", "sample-dataset.zip", "application/zip", "files.example.io", false,
                DlStatus.FAILED, downloaded = 3 * mb, total = 220 * mb, error = "Server error (HTTP 503)", createdAt = now - 3),
            DownloadItem(5, "https://example.com/sample-wallpaper-4k.png", "sample-wallpaper-4k.png", "image/png", "example.com", false,
                DlStatus.DONE, downloaded = 6 * mb, total = 6 * mb, createdAt = now - 4),
            DownloadItem(6, "https://example.com/sample-installer.apk", "sample-installer.apk", "application/vnd.android.package-archive", "example.com", false,
                DlStatus.CANCELLED, createdAt = now - 5),
        )
        val b = ActivityDownloadsBinding.inflate(LayoutInflater.from(c))
        b.toolbar.subtitle = "SAMPLE DATA (preview) · " + DownloadsActivity.summary(sample)
        b.toolbar.menu.add("Clear completed").setIcon(R.drawable.ic_clear_all).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        b.toolbar.menu.getItem(0).icon?.setTint(c.getColor(R.color.text_primary))
        accentLine(b.downloadsAccentLine, accent.color, 0x99)
        val adapter = DownloadAdapter(accent) { _, _, _ -> }
        b.downloadsList.layoutManager = LinearLayoutManager(c)
        b.downloadsList.adapter = adapter
        adapter.submitList(sample)
        render(b.root, 1080, 2340, "downloads.png")

        val e = ActivityDownloadsBinding.inflate(LayoutInflater.from(c))
        accentLine(e.downloadsAccentLine, accent.color, 0x99)
        e.downloadsEmpty.isVisible = true
        DownloadsActivity.styleEmptyState(e, accent.color)
        render(e.root, 1080, 2340, "downloads_empty.png")
    }
}
