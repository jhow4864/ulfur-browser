package com.jamhowman.beastbrowser.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
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
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityPrivateDownloadsBinding
import com.jamhowman.beastbrowser.downloads.DlFormat
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import com.jamhowman.beastbrowser.downloads.PrivateAuth
import com.jamhowman.beastbrowser.downloads.PrivateLock
import kotlinx.coroutines.launch

/**
 * Biometric-gated list of files downloaded from private tabs. Files live in app-private storage
 * with a `.nomedia` marker so Gallery and Files never index them.
 */
class PrivateDownloadsActivity : AppCompatActivity() {
    private lateinit var b: ActivityPrivateDownloadsBinding
    private lateinit var adapter: DownloadAdapter
    private lateinit var prompt: BiometricPrompt
    private var clearItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val accent = Prefs.accent
        theme.applyStyle(accent.overlay, true)
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityPrivateDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        accentLine(b.accentLine, accent.color, 0x99)
        b.emptyArt.imageTintList = android.content.res.ColorStateList.valueOf(accent.color)
        clearItem = b.toolbar.menu.add("Clear all").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { confirmClear(); true }
        }
        adapter = DownloadAdapter(accent) { item, action, anchor -> onAction(item, action, anchor) }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        prompt = PrivateAuth.create(this,
            onUnlocked = { showUnlocked() },
            onFailed = { noLock, msg ->
                if (noLock) {
                    Toast.makeText(this, "Set a screen lock in Settings to use private downloads", Toast.LENGTH_LONG).show()
                    finish()
                } else if (msg.isNotBlank()) {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            })
        b.unlockBtn.setOnClickListener { PrivateAuth.prompt(prompt) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCenter.privateItems.collect { render(it) }
            }
        }
        if (PrivateLock.isUnlocked()) showUnlocked() else showLocked()
    }

    override fun onStart() {
        super.onStart()
        if (!PrivateLock.isUnlocked()) showLocked()
    }

    override fun onStop() {
        PrivateLock.softLock()
        super.onStop()
    }

    private fun showLocked() {
        b.locked.isVisible = true
        b.list.isVisible = false
        b.empty.isVisible = false
        b.warning.isVisible = false
        b.storageLine.isVisible = false
        clearItem?.isVisible = false
        if (PrivateAuth.isAvailable(this)) PrivateAuth.prompt(prompt)
    }

    private fun showUnlocked() {
        b.locked.isVisible = false
        b.warning.isVisible = true
        b.storageLine.isVisible = true
        render(DownloadCenter.privateItems.value)
    }

    private fun render(list: List<DownloadItem>) {
        if (!PrivateLock.isUnlocked()) return
        adapter.submitList(list)
        b.empty.isVisible = list.isEmpty()
        b.list.isVisible = list.isNotEmpty()
        clearItem?.isVisible = list.isNotEmpty()
        val n = list.size
        val bytes = DlFormat.bytes(DownloadCenter.privateVaultBytes())
        b.storageLine.text = if (n == 0) "Empty vault" else "$n ${if (n == 1) "file" else "files"} · $bytes"
        b.toolbar.subtitle = if (n == 0) null else "$n locked ${if (n == 1) "file" else "files"}"
    }

    private fun onAction(d: DownloadItem, action: DlAction, anchor: View) {
        when (action) {
            DlAction.OPEN -> open(d)
            DlAction.SHARE -> share(d)
            DlAction.DELETE -> confirmDelete(d)
            DlAction.MORE -> showMore(d, anchor)
            else -> {}
        }
    }

    private fun open(d: DownloadItem) {
        if (!DownloadCenter.fileExists(d)) {
            Toast.makeText(this, "File was deleted", Toast.LENGTH_SHORT).show()
            DownloadCenter.deletePrivate(d.id)
            return
        }
        try { startActivity(DownloadCenter.openIntent(d) ?: return) }
        catch (_: Exception) { Toast.makeText(this, "No app can open ${d.fileName}", Toast.LENGTH_SHORT).show() }
    }

    private fun share(d: DownloadItem) {
        if (!DownloadCenter.fileExists(d)) return
        try { startActivity(DownloadCenter.shareIntent(d) ?: return) }
        catch (_: Exception) { Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show() }
    }

    private fun showMore(d: DownloadItem, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Open").setOnMenuItemClickListener { open(d); true }
        pm.menu.add("Share").setOnMenuItemClickListener { share(d); true }
        pm.menu.add("Delete").setOnMenuItemClickListener { confirmDelete(d); true }
        pm.show()
    }

    private fun confirmDelete(d: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${d.fileName}?")
            .setMessage("Removes it from the private vault. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> DownloadCenter.deletePrivate(d.id) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmClear() {
        val n = DownloadCenter.privateVaultCount()
        if (n == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete all private downloads?")
            .setMessage("Permanently deletes $n ${if (n == 1) "file" else "files"} from the vault.")
            .setPositiveButton("Delete all") { _, _ -> DownloadCenter.clearPrivateVault() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
