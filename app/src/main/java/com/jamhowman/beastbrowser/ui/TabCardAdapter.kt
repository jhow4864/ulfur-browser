package com.jamhowman.beastbrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.Tab
import com.jamhowman.beastbrowser.data.TabGroup
import com.jamhowman.beastbrowser.databinding.ItemTabCardBinding

class TabCardAdapter(
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    /** Long-press → "Move to group" (2.3.5) / "Close branch" (2.3.8). */
    private val onGroup: (Tab) -> Unit = {},
) : RecyclerView.Adapter<TabCardAdapter.VH>() {

    var items: List<Tab> = emptyList()
    var currentId: Long = -1
    var accent: Int = 0
    /** 2.3.8: every tab of the realm by id (not just the filtered [items]) for Tab DNA lineage. */
    var byId: Map<Long, Tab> = emptyMap()

    class VH(val b: ItemTabCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id

    init { setHasStableIds(true) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab = items[position]
        val ctx = h.b.root.context
        val selected = tab.id == currentId
        h.b.card.strokeColor = if (selected) accent else ctx.getColor(R.color.stroke)
        h.b.card.strokeWidth = dp(ctx, if (selected) 2 else 1)
        h.b.title.text = tab.displayTitle
        h.b.favicon.setImageResource(if (tab.isPrivate) R.drawable.ic_incognito else R.drawable.ic_beast_logo)
        h.b.favicon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
        val thumb = tab.thumbnail
        h.b.thumb.setImageBitmap(thumb)
        h.b.placeholder.isVisible = thumb == null
        h.b.placeholder.text = tab.displayTitle.firstOrNull()?.uppercase() ?: "B"
        h.b.placeholder.setTextColor(accent)
        val group = TabGroup.from(tab.groupId)
        h.b.groupStrip.isVisible = group != null
        if (group != null) h.b.groupStrip.setBackgroundColor(group.accent.color)
        // 2.3.8 Tab DNA: lineage strip in the root's colour + "↳ from …" / "• N child tabs"
        val parent = tab.parentId?.let { byId[it] }
        val children = byId.values.count { it.parentId == tab.id }
        val linked = parent != null || children > 0
        h.b.dnaStrip.isVisible = linked
        if (linked) h.b.dnaStrip.setBackgroundColor((TabDnaDecoration.lineageColor(rootOf(tab)) and 0x00FFFFFF) or 0xCC000000.toInt())
        h.b.dnaLabel.isVisible = linked
        h.b.dnaLabel.text = when {
            parent != null -> "↳ from ${parent.displayTitle}"
            children == 1 -> "• 1 child tab"
            else -> "• $children child tabs"
        }
        h.b.root.setOnClickListener { onSelect(tab) }
        h.b.root.setOnLongClickListener { onGroup(tab); true }
        h.b.close.setOnClickListener { onClose(tab) }
    }

    /** Oldest ancestor still open in this realm (cycle-safe). */
    fun rootOf(tab: Tab): Tab {
        var t = tab
        val seen = HashSet<Long>()
        while (seen.add(t.id)) {
            t = t.parentId?.let { byId[it] } ?: break
        }
        return t
    }
}
