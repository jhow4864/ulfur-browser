package com.jamhowman.beastbrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.Tab
import com.jamhowman.beastbrowser.databinding.ItemTabCardBinding

class TabCardAdapter(
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
) : RecyclerView.Adapter<TabCardAdapter.VH>() {

    var items: List<Tab> = emptyList()
    var currentId: Long = -1
    var accent: Int = 0

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
        h.b.root.setOnClickListener { onSelect(tab) }
        h.b.close.setOnClickListener { onClose(tab) }
    }
}
