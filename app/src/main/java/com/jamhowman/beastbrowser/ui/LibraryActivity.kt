package com.jamhowman.beastbrowser.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.jamhowman.beastbrowser.browser.UrlUtils
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Entry
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityLibraryBinding
import com.jamhowman.beastbrowser.databinding.ItemLibraryBinding
import com.jamhowman.beastbrowser.util.Domains

/** Bookmarks + history. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding
    private lateinit var db: BrowserDb
    private var page = 0
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Prefs.accent.overlay, true)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        db = BrowserDb.get(this)
        page = intent.getIntExtra(EXTRA_PAGE, 0)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.menu.add("Clear").setOnMenuItemClickListener { confirmClear(); true }
        val accent = Prefs.accent.color
        b.libraryTabs.setSelectedTabIndicatorColor(accent)
        b.libraryTabs.setTabTextColors(getColor(com.jamhowman.beastbrowser.R.color.text_secondary), accent)
        b.libraryTabs.addTab(b.libraryTabs.newTab().setText("Bookmarks"))
        b.libraryTabs.addTab(b.libraryTabs.newTab().setText("History"))
        b.libraryTabs.getTabAt(page)?.select()
        b.libraryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { page = tab.position; reload() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        b.libraryList.layoutManager = LinearLayoutManager(this)
        b.libraryList.adapter = adapter
        reload()
    }

    private fun reload() {
        b.toolbar.title = if (page == 0) "Bookmarks" else "History"
        b.toolbar.menu.getItem(0).isVisible = page == 1
        adapter.items = if (page == 0) db.bookmarks() else db.history()
        adapter.notifyDataSetChanged()
        b.libraryEmpty.isVisible = adapter.items.isEmpty()
        b.libraryEmpty.text = if (page == 0) "No bookmarks yet.\nUse Menu → Bookmark on any page." else "No history yet."
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this).setTitle("Clear history?")
            .setPositiveButton("Clear") { _, _ -> db.clearHistory(); reload() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Entry> = emptyList()
        inner class VH(val b: ItemLibraryBinding) : RecyclerView.ViewHolder(b.root)
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val host = Domains.display(UrlUtils.host(e.url))
            h.b.itemTitle.text = e.title.ifBlank { host.ifBlank { e.url } }
            h.b.itemSubtitle.text = if (page == 1)
                "$host · ${DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}" else host
            h.b.itemIcon.text = (host.firstOrNull() ?: '•').uppercase()
            h.b.itemIcon.setTextColor(Prefs.accent.color)
            h.b.root.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, e.url)); finish()
            }
            h.b.itemDelete.setOnClickListener {
                if (page == 0) db.deleteBookmark(e.id) else db.deleteHistory(e.id)
                reload()
            }
        }
    }

    companion object { const val EXTRA_PAGE = "page" }
}
