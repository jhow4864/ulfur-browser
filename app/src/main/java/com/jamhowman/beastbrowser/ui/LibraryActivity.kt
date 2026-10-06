package com.jamhowman.beastbrowser.ui

import android.content.Intent
import android.content.res.ColorStateList
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
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.UrlUtils
import com.jamhowman.beastbrowser.data.BookmarkFolder
import com.jamhowman.beastbrowser.data.CustomFolder
import com.jamhowman.beastbrowser.data.CustomFolders
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Entry
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityLibraryBinding
import com.jamhowman.beastbrowser.databinding.ItemFolderPillBinding
import com.jamhowman.beastbrowser.databinding.ItemLibraryBinding
import com.jamhowman.beastbrowser.util.Domains

/** Bookmarks (with folder filter pills, 2.3.4) + history. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding
    private lateinit var db: BrowserDb
    private var page = 0
    /** [BookmarkFolder.id] or [CustomFolder.id] shown on the Bookmarks page; null = All. */
    private var selectedFolder: String? = null
    /** Custom folders (2.4.1, created by backup import), reloaded with the pills. */
    private var customFolders: List<CustomFolder> = emptyList()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemedScreen(this)
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
        b.libraryTabs.setTabTextColors(getColor(R.color.text_secondary), accent)
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
        buildFolderPills()
        b.libraryEmpty.emptyCta.isVisible = false
        reload()
    }

    private fun reload() {
        b.toolbar.title = if (page == 0) "Bookmarks" else "History"
        b.toolbar.menu.getItem(0).isVisible = page == 1
        b.folderStripScroll.isVisible = page == 0
        adapter.items = if (page == 0) db.bookmarks(selectedFolder) else db.history()
        adapter.notifyDataSetChanged()
        val empty = adapter.items.isEmpty()
        b.libraryEmpty.root.isVisible = empty
        if (empty) {
            val e = b.libraryEmpty
            e.emptyArt.setImageResource(R.drawable.img_empty_library)
            e.emptyTitle.setText(if (page == 0) R.string.empty_library_title else R.string.empty_history_title)
            e.emptyBody.setText(if (page == 0) R.string.empty_library_body else R.string.empty_history_body)
            e.emptyArt.imageTintList = ColorStateList.valueOf(Prefs.accent.color)
        }
    }

    /**
     * "All" + one pill per [BookmarkFolder], each in its folder accent, then the custom folders ("Parent / Child")
     * in the realm accent; the selected one is filled. Long-press a custom folder to delete it.
     */
    private fun buildFolderPills() {
        customFolders = CustomFolders.ordered(CustomFolders.load())
        if (selectedFolder != null && BookmarkFolder.from(selectedFolder) == null && customFolders.none { it.id == selectedFolder }) {
            selectedFolder = null
        }
        b.folderStrip.removeAllViews()
        fun addPill(id: String?, label: String, color: Int, onLong: (() -> Unit)? = null) {
            val pill = ItemFolderPillBinding.inflate(layoutInflater, b.folderStrip, false)
            pill.folderName.text = label
            pill.folderName.setTextColor(color)
            pill.folderPill.strokeColor = color
            pill.folderPill.setCardBackgroundColor(
                if (selectedFolder == id) ColorStateList.valueOf((color and 0x00FFFFFF) or 0x33000000)
                else ColorStateList.valueOf(getColor(R.color.surface2))
            )
            pill.root.setOnClickListener { selectedFolder = id; buildFolderPills(); reload() }
            if (onLong != null) pill.root.setOnLongClickListener { onLong(); true }
            b.folderStrip.addView(pill.root)
        }
        addPill(null, getString(R.string.folder_all), Prefs.accent.color)
        BookmarkFolder.entries.forEach { addPill(it.id, getString(it.labelRes), it.accent.color) }
        customFolders.forEach { f -> addPill(f.id, folderLabel(f.id) ?: f.name, Prefs.accent.color) { confirmDeleteFolder(f) } }
    }

    private fun folderLabel(id: String?): String? = CustomFolders.label(this, id, customFolders)

    /** Deleting a custom folder never deletes bookmarks: they (and sub-folders) move to its parent. */
    private fun confirmDeleteFolder(f: CustomFolder) {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.folder_delete_title, folderLabel(f.id) ?: f.name))
            .setMessage(R.string.folder_delete_body)
            .setPositiveButton(R.string.folder_delete) { _, _ ->
                CustomFolders.delete(f.id)
                db.moveFolderBookmarks(f.id, f.parentId?.takeIf { p -> BookmarkFolder.from(p) != null || customFolders.any { it.id == p } })
                if (selectedFolder == f.id) selectedFolder = null
                buildFolderPills(); reload()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Long-press a bookmark → "Move to folder" ("All" clears the folder). */
    private fun pickFolder(entry: Entry) {
        val choices: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to getString(R.string.folder_all)) +
                BookmarkFolder.entries.map { it.id to getString(it.labelRes) } +
                customFolders.map { it.id to (folderLabel(it.id) ?: it.name) }
        val checked = choices.indexOfFirst { it.first == entry.folderId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Move to folder")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), checked) { d, which ->
                db.setBookmarkFolder(entry.id, choices[which].first)
                d.dismiss()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
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
            val folder = BookmarkFolder.from(e.folderId)
            val folderName = if (page == 0) folderLabel(e.folderId) else null
            h.b.itemSubtitle.text = when {
                page == 1 -> "$host · ${DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
                folderName != null -> "$host · $folderName"
                else -> host
            }
            h.b.itemIcon.text = (host.firstOrNull() ?: '•').uppercase()
            h.b.itemIcon.setTextColor(folder?.accent?.color ?: Prefs.accent.color)
            h.b.root.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, e.url)); finish()
            }
            h.b.root.setOnLongClickListener {
                if (page != 0) return@setOnLongClickListener false
                pickFolder(e); true
            }
            h.b.itemDelete.setOnClickListener {
                if (page == 0) db.deleteBookmark(e.id) else db.deleteHistory(e.id)
                reload()
            }
        }
    }

    companion object { const val EXTRA_PAGE = "page" }
}
