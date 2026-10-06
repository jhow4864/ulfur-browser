package com.jamhowman.beastbrowser.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ItemSpeedDialBinding
import org.json.JSONArray
import org.json.JSONObject

data class Tile(val title: String, val url: String)

object SpeedDialStore {
    private val DEFAULTS = listOf(
        Tile("YouTube", "https://m.youtube.com"),
        Tile("Twitch", "https://www.twitch.tv"),
        Tile("Reddit", "https://www.reddit.com"),
        Tile("Wikipedia", "https://en.wikipedia.org"),
        Tile("BBC News", "https://www.bbc.co.uk/news"),
        Tile("GitHub", "https://github.com"),
        Tile("Steam", "https://store.steampowered.com"),
        Tile("DuckDuckGo", "https://duckduckgo.com"),
    )

    fun load(): MutableList<Tile> {
        val raw = Prefs.speedDial ?: return DEFAULTS.toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> arr.getJSONObject(i).let { Tile(it.getString("t"), it.getString("u")) } }
        } catch (e: Exception) { DEFAULTS.toMutableList() }
    }

    fun save(tiles: List<Tile>) {
        Prefs.speedDial = JSONArray(tiles.map { JSONObject().put("t", it.title).put("u", it.url) }).toString()
    }
}

class SpeedDialAdapter(
    var tiles: MutableList<Tile>,
    var accent: Int,
    private val onClick: (Tile) -> Unit,
    private val onLongClick: (Tile) -> Unit,
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<SpeedDialAdapter.VH>() {

    class VH(val b: ItemSpeedDialBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = tiles.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSpeedDialBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.b.root.context
        val bg = (ctx.getDrawable(R.drawable.bg_tile)!!.mutate() as GradientDrawable)
        h.b.tileIcon.background = bg
        if (position == tiles.size) {
            h.b.tileIcon.text = "+"
            h.b.tileIcon.setTextColor(ctx.getColor(R.color.text_secondary))
            bg.setStroke(dp(ctx, 1), ctx.getColor(R.color.stroke))
            h.b.tileLabel.text = "Add"
            h.b.root.setOnClickListener { onAdd() }
            h.b.root.setOnLongClickListener(null)
            return
        }
        val t = tiles[position]
        h.b.tileIcon.text = t.title.firstOrNull()?.uppercase() ?: "?"
        h.b.tileIcon.setTextColor(accent)
        bg.setStroke(dp(ctx, 1), (accent and 0x00FFFFFF) or (0x55 shl 24))
        h.b.tileLabel.text = t.title
        h.b.root.setOnClickListener { onClick(t) }
        h.b.root.setOnLongClickListener { onLongClick(t); true }
        h.b.tileIcon.backgroundTintList = null
        h.b.tileIcon.foregroundTintList = ColorStateList.valueOf(accent)
    }
}
