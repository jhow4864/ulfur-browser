package com.jamhowman.beastbrowser.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.jamhowman.beastbrowser.browser.Tab
import com.jamhowman.beastbrowser.data.Accent
import kotlin.math.abs

/**
 * Tab DNA (2.3.8): draws a curved connector from each parent card to its child cards in the tab
 * switcher grid, coloured per lineage (the root tab's [lineageColor]), with dots at both ends.
 */
class TabDnaDecoration(private val adapter: TabCardAdapter) : RecyclerView.ItemDecoration() {

    private data class Link(val sx: Float, val sy: Float, val ex: Float, val ey: Float, val horizontal: Boolean, val color: Int)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        line.strokeWidth = parent.resources.displayMetrics.density * 2f
        for (l in links(parent)) {
            line.color = (l.color and 0x00FFFFFF) or 0x80000000.toInt()
            path.reset()
            path.moveTo(l.sx, l.sy)
            if (l.horizontal) {
                val mx = (l.sx + l.ex) / 2f
                path.cubicTo(mx, l.sy, mx, l.ey, l.ex, l.ey)
            } else {
                val my = (l.sy + l.ey) / 2f
                path.cubicTo(l.sx, my, l.ex, my, l.ex, l.ey)
            }
            c.drawPath(path, line)
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val r = parent.resources.displayMetrics.density * 3f
        for (l in links(parent)) {
            dot.color = (l.color and 0x00FFFFFF) or 0xB0000000.toInt()
            c.drawCircle(l.sx, l.sy, r, dot)
            c.drawCircle(l.ex, l.ey, r, dot)
        }
    }

    /** Links for visible children whose parent card is also laid out. */
    private fun links(rv: RecyclerView): List<Link> {
        val items = adapter.items
        val lm = rv.layoutManager
        if (items.size < 2 || lm == null) return emptyList()
        val out = ArrayList<Link>()
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val pos = rv.getChildViewHolder(child)?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION
            val tab = items.getOrNull(pos) ?: continue
            val parentId = tab.parentId ?: continue
            val parentPos = items.indexOfFirst { it.id == parentId }
            if (parentPos < 0) continue
            val parentView = lm.findViewByPosition(parentPos) ?: continue
            out += link(parentView, child, lineageColor(adapter.rootOf(tab)))
        }
        return out
    }

    /** Side-to-side when the cards share a row, otherwise bottom/top edge to edge. */
    private fun link(from: View, to: View, color: Int): Link {
        val fx = from.left + from.translationX
        val fy = from.top + from.translationY
        val tx = to.left + to.translationX
        val ty = to.top + to.translationY
        val fcx = fx + from.width / 2f
        val fcy = fy + from.height / 2f
        val tcx = tx + to.width / 2f
        val tcy = ty + to.height / 2f
        return if (abs(fcy - tcy) < from.height / 2f) {
            if (tcx >= fcx) Link(fx + from.width, fcy, tx, tcy, true, color)
            else Link(fx, fcy, tx + to.width, tcy, true, color)
        } else if (tcy > fcy) {
            Link(fcx, fy + from.height, tcx, ty, false, color)
        } else {
            Link(fcx, fy, tcx, ty + to.height, false, color)
        }
    }

    companion object {
        /** Stable colour per lineage: picked from the accents by the root tab's id. */
        fun lineageColor(root: Tab): Int = Accent.entries[(root.id % Accent.entries.size).toInt()].color
    }
}
