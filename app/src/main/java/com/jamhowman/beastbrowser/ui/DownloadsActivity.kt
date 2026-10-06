package com.jamhowman.beastbrowser.ui

import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityDownloadsBinding
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import kotlinx.coroutines.launch

/**
 * Live list of active + finished downloads. State lives in [DownloadCenter] (process singleton), so the screen
 * survives rotation/recreation and simply re-collects the StateFlow.
 */
class DownloadsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDownloadsBinding
    private lateinit var adapter: DownloadAdapter
    private var clearItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val accent = Prefs.accent
        theme.applyStyle(accent.overlay, true)
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        accentLine(b.downloadsAccentLine, accent.color, 0x99)
        clearItem = b.toolbar.menu.add("Clear completed").apply {
            setIcon(R.drawable.ic_clear_all)
            icon?.setTint(getColor(R.color.text_primary))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener { confirmClearCompleted(); true }
        }
        b.toolbar.menu.add("Private downloads").apply {
            setIcon(R.drawable.ic_lock)
            icon?.setTint(getColor(R.color.text_primary))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                startActivity(Intent(this@DownloadsActivity, PrivateDownloadsActivity::class.java)); true
            }
        }
        styleEmptyState(b, accent.color)

        adapter = DownloadAdapter(accent) { item, action, anchor -> onAction(item, action, anchor) }
        b.downloadsList.layoutManager = LinearLayoutManager(this)
        b.downloadsList.adapter = adapter
        (b.downloadsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        if (intent?.getBooleanExtra("open_private", false) == true) {
            startActivity(Intent(this, PrivateDownloadsActivity::class.java))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCenter.items.collect { render(it) }
            }
        }
    }

    private fun render(list: List<DownloadItem>) {
        adapter.submitList(list)
        b.downloadsEmpty.isVisible = list.isEmpty()
        b.toolbar.subtitle = summary(list)
        clearItem?.isVisible = list.any { it.status.isFinished }
    }

    private fun onAction(d: DownloadItem, action: DlAction, anchor: View) {
        when (action) {
            DlAction.PAUSE -> DownloadCenter.pause(d.id)
            DlAction.RESUME -> DownloadCenter.resume(d.id)
            DlAction.CANCEL -> DownloadCenter.cancel(d.id)
            DlAction.RETRY -> DownloadCenter.retry(d.id)
            DlAction.OPEN -> open(d)
            DlAction.SHARE -> share(d)
            DlAction.DELETE -> confirmDelete(d)
            DlAction.MORE -> showMore(d, anchor)
        }
    }

    private fun open(d: DownloadItem) {
        if (!DownloadCenter.fileExists(d)) { missing(d); return }
        try { startActivity(DownloadCenter.openIntent(d) ?: return) }
        catch (e: Exception) { toast("No app can open ${d.fileName}") }
    }

    private fun share(d: DownloadItem) {
        if (!DownloadCenter.fileExists(d)) { missing(d); return }
        try { startActivity(DownloadCenter.shareIntent(d) ?: return) } catch (e: Exception) { toast("Can't share this file") }
    }

    private fun missing(d: DownloadItem) {
        Snackbar.make(b.root, "File was moved or deleted", Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.surface3)).setTextColor(getColor(R.color.text_primary))
            .setActionTextColor(Prefs.accent.color)
            .setAction("Remove") { DownloadCenter.remove(d.id) }.show()
    }

    private fun showMore(d: DownloadItem, anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        fun add(title: String, f: () -> Unit) = m.add(title).setOnMenuItemClickListener { f(); true }
        when (d.status) {
            DlStatus.DONE -> { add("Open") { open(d) }; add("Share") { share(d) } }
            DlStatus.DOWNLOADING -> add("Pause") { DownloadCenter.pause(d.id) }
            DlStatus.PAUSED -> add("Resume") { DownloadCenter.resume(d.id) }
            DlStatus.FAILED, DlStatus.CANCELLED -> add("Retry") { DownloadCenter.retry(d.id) }
            DlStatus.QUEUED -> {}
        }
        if (d.status.isActive || d.status == DlStatus.PAUSED) add("Cancel") { DownloadCenter.cancel(d.id) }
        add("Copy download link") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", d.url))
            toast("Link copied")
        }
        if (!d.status.isActive) {
            add(if (d.status == DlStatus.DONE) "Delete file" else "Delete") { confirmDelete(d) }
            if (d.status == DlStatus.DONE) add("Remove from list (keep file)") { DownloadCenter.remove(d.id) }
        }
        pm.show()
    }

    private fun confirmDelete(d: DownloadItem) {
        val done = d.status == DlStatus.DONE
        MaterialAlertDialogBuilder(this)
            .setTitle(if (done) "Delete ${d.fileName}?" else "Remove download?")
            .setMessage(if (done) "The file will be deleted from your device and removed from this list." else "Any partial file is deleted too.")
            .setPositiveButton("Delete") { _, _ -> DownloadCenter.delete(d.id) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmClearCompleted() {
        val n = DownloadCenter.items.value.count { it.status.isFinished }
        if (n == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear completed?")
            .setMessage("Removes $n finished, failed or cancelled ${if (n == 1) "entry" else "entries"} from this list. Downloaded files stay in Downloads/Beast.")
            .setPositiveButton("Clear") { _, _ -> DownloadCenter.clearFinished() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        fun summary(list: List<DownloadItem>): String? {
            if (list.isEmpty()) return null
            val active = list.count { it.status.isActive }
            val done = list.count { it.status == DlStatus.DONE }
            val paused = list.count { it.status == DlStatus.PAUSED }
            val failed = list.count { it.status == DlStatus.FAILED }
            return listOfNotNull(
                active.takeIf { it > 0 }?.let { "$it active" },
                paused.takeIf { it > 0 }?.let { "$it paused" },
                failed.takeIf { it > 0 }?.let { "$it failed" },
                done.takeIf { it > 0 }?.let { "$it completed" },
            ).joinToString(" · ").ifEmpty { null }
        }

        fun styleEmptyState(b: ActivityDownloadsBinding, accent: Int) {
            b.emptyArt.imageTintList = android.content.res.ColorStateList.valueOf(accent)
            glow(b.emptyArt, accent)
        }
    }
}
