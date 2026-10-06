package com.jamhowman.beastbrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jamhowman.beastbrowser.databinding.ItemMediaSaveBinding
import com.jamhowman.beastbrowser.databinding.SheetMediaSaveBinding
import com.jamhowman.beastbrowser.downloads.DlFormat
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.media.DetectedMedia
import com.jamhowman.beastbrowser.media.MediaSniffer
import org.mozilla.geckoview.GeckoSession

/** Bottom sheet listing detected videos for the current tab, with one-tap Save into Download Center. */
object MediaSaveSheet {
    fun show(activity: AppCompatActivity, session: GeckoSession, isPrivate: Boolean, pageUrl: String, accentColor: Int) {
        DownloadCenter.init(activity)
        val items = MediaSniffer.forSession(session)
        val b = SheetMediaSaveBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)

        b.mediaTitle.text = if (items.size == 1) "Save video" else "Save videos"
        b.mediaSubtitle.text = buildString {
            append(if (items.isEmpty()) "Nothing to save" else "${items.size} ${if (items.size == 1) "stream" else "streams"}")
            if (isPrivate) append(" · private (hidden vault)")
        }
        b.mediaEmpty.isVisible = items.isEmpty()
        b.mediaList.isVisible = items.isNotEmpty()
        b.mediaList.layoutManager = LinearLayoutManager(activity)
        b.mediaList.adapter = Adapter(items, accentColor) { media ->
            if (MediaSniffer.isDrmHost(media.url) || MediaSniffer.isDrmHost(pageUrl)) {
                Toast.makeText(activity, "Can't download from this site", Toast.LENGTH_SHORT).show()
                return@Adapter
            }
            val nameHint = media.title.ifBlank { null }
            val referrer = pageUrl.ifBlank { media.pageUrl }.ifBlank { null }
            if (media.kind == DetectedMedia.Kind.HLS) {
                // 2.3.6: HLS playlists are downloaded segment-by-segment and joined into one file.
                DownloadCenter.enqueueHls(media.url, isPrivate, referrer, nameHint)
            } else {
                DownloadCenter.enqueue(url = media.url, isPrivate = isPrivate, referrer = referrer, mime = media.mime)
            }
            val where = if (isPrivate) "Private downloads" else "Downloads"
            Toast.makeText(activity, "Saving to $where", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            activity.startActivity(android.content.Intent(activity, DownloadsActivity::class.java).apply {
                if (isPrivate) putExtra("open_private", true)
            })
        }
        dialog.show()
    }

    private class Adapter(
        private val items: List<DetectedMedia>,
        private val accent: Int,
        private val onSave: (DetectedMedia) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        class VH(val b: ItemMediaSaveBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMediaSaveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            val ext = when {
                m.kind == DetectedMedia.Kind.HLS -> "HLS"
                m.mime.contains("webm") -> "WebM"
                m.mime.contains("audio") -> "Audio"
                else -> "MP4"
            }
            holder.b.mediaQuality.text = listOf(m.qualityLabel, ext).filter { it.isNotBlank() }.joinToString(" · ")
            holder.b.mediaMeta.text = buildString {
                if (m.bytes > 0) append(DlFormat.bytes(m.bytes))
                if (m.width > 0 && m.height > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${m.width}×${m.height}")
                }
                val host = m.shortHost
                if (host.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(host)
                }
            }.ifBlank { m.kind.name.lowercase() }
            holder.b.mediaSaveBtn.setOnClickListener { onSave(m) }
        }
    }
}
