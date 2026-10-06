package com.jamhowman.beastbrowser.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.ThemePreset
import com.jamhowman.beastbrowser.databinding.ItemDownloadBinding
import com.jamhowman.beastbrowser.downloads.DlFormat
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadItem

enum class DlAction { PAUSE, RESUME, CANCEL, RETRY, OPEN, SHARE, DELETE, MORE }

/** Download cards. Progress-only changes rebind in place (no flicker) via a payload. */
class DownloadAdapter(
    private val accent: ThemePreset,
    private val onAction: (DownloadItem, DlAction, android.view.View) -> Unit,
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    class VH(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemId(position: Int) = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.dlProgress.setIndicatorColor(accent.color)
        b.dlType.setTextColor(accent.color)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) bindProgress(holder, getItem(position)) else onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = getItem(position)
        val b = holder.b
        val ctx = b.root.context
        b.dlName.text = d.fileName
        b.dlDomain.text = d.domain
        b.dlPrivate.isVisible = d.isPrivate
        b.dlType.text = d.extension.ifEmpty { "FILE" }
        b.root.setOnClickListener { if (d.status == DlStatus.DONE) onAction(d, DlAction.OPEN, it) else onAction(d, DlAction.MORE, b.dlMore) }
        b.dlMore.setOnClickListener { onAction(d, DlAction.MORE, it) }

        val (primary, secondary) = when (d.status) {
            DlStatus.DOWNLOADING -> DlAction.PAUSE to DlAction.CANCEL
            DlStatus.QUEUED -> null to DlAction.CANCEL
            DlStatus.PAUSED -> DlAction.RESUME to DlAction.CANCEL
            DlStatus.FAILED, DlStatus.CANCELLED -> DlAction.RETRY to DlAction.DELETE
            DlStatus.DONE -> DlAction.OPEN to DlAction.SHARE
        }
        bindButton(b.dlPrimary, primary, d, highlight = true)
        bindButton(b.dlSecondary, secondary, d, highlight = false)
        b.dlName.setTextColor(ctx.getColor(if (d.status == DlStatus.CANCELLED) R.color.text_hint else R.color.text_primary))
        bindProgress(holder, d)
    }

    private fun bindButton(btn: android.widget.ImageButton, action: DlAction?, d: DownloadItem, highlight: Boolean) {
        btn.isVisible = action != null
        action ?: return
        val (icon, label) = when (action) {
            DlAction.PAUSE -> R.drawable.ic_pause to "Pause"
            DlAction.RESUME -> R.drawable.ic_play to "Resume"
            DlAction.CANCEL -> R.drawable.ic_close to "Cancel"
            DlAction.RETRY -> R.drawable.ic_refresh to "Retry"
            DlAction.OPEN -> R.drawable.ic_open to "Open"
            DlAction.SHARE -> R.drawable.ic_share to "Share"
            DlAction.DELETE -> R.drawable.ic_delete to "Delete"
            DlAction.MORE -> R.drawable.ic_more to "More"
        }
        btn.setImageResource(icon)
        btn.contentDescription = label
        btn.imageTintList = ColorStateList.valueOf(if (highlight) accent.color else btn.context.getColor(R.color.text_secondary))
        btn.setOnClickListener { onAction(d, action, it) }
    }

    private fun bindProgress(holder: VH, d: DownloadItem) {
        val b = holder.b
        val ctx = b.root.context
        val p = b.dlProgress
        p.isVisible = d.status != DlStatus.CANCELLED
        val pct = d.percent
        val indeterminate = d.status == DlStatus.QUEUED || (d.status == DlStatus.DOWNLOADING && pct < 0)
        if (p.isIndeterminate != indeterminate) {
            p.visibility = android.view.View.INVISIBLE   // must be hidden to switch modes
            p.isIndeterminate = indeterminate
            p.visibility = android.view.View.VISIBLE
        }
        if (!indeterminate) p.setProgressCompat(if (pct < 0) 0 else pct, false)
        val barColor = when (d.status) {
            DlStatus.FAILED -> ctx.getColor(R.color.warn)
            DlStatus.PAUSED -> accent.withAlpha(0x88)
            else -> accent.color
        }
        p.setIndicatorColor(barColor)

        b.dlStatus.text = d.status.label.uppercase()
        val chip = (ctx.getDrawable(R.drawable.bg_chip)!!.mutate() as GradientDrawable)
        val (chipBg, chipFg) = when (d.status) {
            DlStatus.DOWNLOADING -> accent.color to accent.onColor
            DlStatus.DONE -> accent.withAlpha(0x33) to accent.color
            DlStatus.FAILED -> 0x33FFB020 to ctx.getColor(R.color.warn)
            else -> ctx.getColor(R.color.surface3) to ctx.getColor(R.color.text_secondary)
        }
        chip.setColor(chipBg); b.dlStatus.background = chip; b.dlStatus.setTextColor(chipFg)
        b.dlDetail.text = DlFormat.detail(d)
    }

    object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
        override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        /** Only bytes/speed changed -> partial rebind. */
        override fun getChangePayload(a: DownloadItem, b: DownloadItem): Any? =
            if (a.copy(downloaded = b.downloaded, speedBps = b.speedBps, total = b.total) == b) "progress" else null
    }
}
