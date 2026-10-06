package com.jamhowman.beastbrowser.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
import com.google.android.material.snackbar.Snackbar
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityReadingListBinding
import com.jamhowman.beastbrowser.databinding.ItemReadingBinding
import com.jamhowman.beastbrowser.reader.ReaderMode
import com.jamhowman.beastbrowser.reader.ReadingListDb
import com.jamhowman.beastbrowser.reader.SavedArticle
import kotlin.concurrent.thread

/** Saved Reader-view articles (local, offline). Tapping one opens it in Reader view in the browser. */
class ReadingListActivity : AppCompatActivity() {
    private lateinit var b: ActivityReadingListBinding
    private lateinit var adapter: ReadingAdapter
    private lateinit var db: ReadingListDb

    override fun onCreate(savedInstanceState: Bundle?) {
        val accent = Prefs.accent
        theme.applyStyle(accent.overlay, true)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityReadingListBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        db = ReadingListDb.get(this)
        b.toolbar.setNavigationOnClickListener { finish() }
        accentLine(b.readingAccentLine, accent.color, 0x99)
        b.readingEmptyArt.imageTintList = ColorStateList.valueOf(accent.color)
        glow(b.readingEmptyArt, accent.color)
        adapter = ReadingAdapter(accent, onOpen = ::open, onDelete = ::delete)
        b.readingList.layoutManager = LinearLayoutManager(this)
        b.readingList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        thread {
            val list = runCatching { db.list() }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) render(list) }
        }
    }

    internal fun render(list: List<SavedArticle>) {
        adapter.items = list
        adapter.notifyDataSetChanged()
        b.readingEmpty.isVisible = list.isEmpty()
        val unread = list.count { !it.read }
        b.toolbar.subtitle = if (list.isEmpty()) null else "${list.size} saved" + if (unread > 0) " · $unread unread" else ""
    }

    private fun open(a: SavedArticle) {
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(ReaderMode.ACTION_OPEN_SAVED)
            .putExtra(ReaderMode.EXTRA_ARTICLE_ID, a.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun delete(a: SavedArticle) {
        val full = db.get(a.id)
        db.delete(a.id)
        reload()
        Snackbar.make(b.root, "Removed from reading list", Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.surface3)).setTextColor(getColor(R.color.text_primary))
            .setActionTextColor(snackActionColor(this, Prefs.accent.color))
            .setAction("Undo") { full?.let { db.save(it); reload() } }.show()
    }
}

class ReadingAdapter(
    private val accent: ThemePreset,
    private val onOpen: (SavedArticle) -> Unit,
    private val onDelete: (SavedArticle) -> Unit,
) : RecyclerView.Adapter<ReadingAdapter.VH>() {
    var items: List<SavedArticle> = emptyList()

    class VH(val b: ItemReadingBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemReadingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        val ctx = h.b.root.context
        val host = runCatching { java.net.URI(a.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
        val minutes = if (a.words > 0) "${maxOf(1, a.words / 230)} min read" else null
        h.b.readingSite.text = listOfNotNull(a.site.ifBlank { host }.ifBlank { null }, minutes).joinToString(" · ")
        h.b.readingSite.setTextColor(accent.color)
        h.b.readingTitle.text = a.title
        h.b.readingExcerpt.text = a.excerpt
        h.b.readingExcerpt.isVisible = a.excerpt.isNotBlank()
        val saved = DateUtils.getRelativeTimeSpanString(a.savedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        h.b.readingMeta.text = listOfNotNull("Saved $saved", a.byline.ifBlank { null }, if (a.read) null else "Unread").joinToString(" · ")
        h.b.readingDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (a.read) ctx.getColor(R.color.stroke) else accent.color)
        }
        h.b.readingTitle.alpha = if (a.read) 0.75f else 1f
        h.b.root.setOnClickListener { onOpen(a) }
        h.b.readingDelete.setOnClickListener { onDelete(a) }
    }
}
