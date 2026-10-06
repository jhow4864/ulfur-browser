package com.jamhowman.beastbrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.databinding.ItemSuggestBinding
import com.jamhowman.beastbrowser.search.SuggestItem
import com.jamhowman.beastbrowser.search.SuggestKind

class SuggestAdapter(
    private val onClick: (SuggestItem) -> Unit,
) : RecyclerView.Adapter<SuggestAdapter.VH>() {
    private var items: List<SuggestItem> = emptyList()

    fun submit(list: List<SuggestItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemSuggestBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSuggestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.b
        b.suggestTitle.text = item.title
        b.suggestSub.isVisible = !item.subtitle.isNullOrBlank()
        b.suggestSub.text = item.subtitle
        val icon = when (item.kind) {
            SuggestKind.SEARCH -> R.drawable.ic_search
            SuggestKind.HISTORY -> R.drawable.ic_history
            SuggestKind.BOOKMARK -> R.drawable.ic_bookmark
        }
        b.suggestIcon.setImageResource(icon)
        b.root.setOnClickListener { onClick(item) }
    }
}
