package com.jamhowman.beastbrowser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
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
import com.jamhowman.beastbrowser.data.ThemePalette
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
    private companion object { const val KEY_PROMPTING = "prompting" }
    private lateinit var b: ActivityPrivateDownloadsBinding
    private lateinit var adapter: DownloadAdapter
    private lateinit var prompt: BiometricPrompt
    private var clearItem: MenuItem? = null
    private var selectAllItem: MenuItem? = null
    /** The vault's palette (private override, if any), for tint code outside onCreate. */
    private lateinit var palette: ThemePalette
    /** Ids waiting for the Android 8-9 storage permission before the confirm dialog. */
    private var pendingMove: Set<Long> = emptySet()
    private var moving = false
    /**
     * True while the fingerprint/screen-lock prompt is up (bug 7). onCreate and onStart both used to call
     * showLocked(), so the first open asked twice; the screen-lock fallback on Android 9-10 is its own activity,
     * so its onStop/onStart round trip could ask again too. Kept across rotation because the prompt's fragment
     * re-shows itself.
     */
    private var prompting = false

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val ids = pendingMove; pendingMove = emptySet()
        if (ok) confirmMove(ids)
        else Toast.makeText(this, R.string.vault_move_needs_storage, Toast.LENGTH_LONG).show()
    }

    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelection(emptySet())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val accent = ThemedScreen(this, private = true).palette // the vault: private-mode override (item 11), if any
        palette = accent
        super.onCreate(savedInstanceState)
        prompting = savedInstanceState?.getBoolean(KEY_PROMPTING) ?: false
        DownloadCenter.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityPrivateDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { if (adapter.selecting) setSelection(emptySet()) else finish() }
        onBackPressedDispatcher.addCallback(this, exitSelection)
        accent.systemBars?.let { b.toolbar.setBackgroundColor(it) } // item 11: the vault's bars are private violet
        accentLine(b.accentLine, accent.color, 0x99)
        b.emptyArt.imageTintList = android.content.res.ColorStateList.valueOf(accent.color)
        clearItem = b.toolbar.menu.add("Clear all").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { confirmClear(); true }
        }
        selectAllItem = b.toolbar.menu.add(R.string.vault_select_all).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            setOnMenuItemClickListener { setSelection(DownloadCenter.privateItems.value.map { it.id }.toSet()); true }
        }
        adapter = DownloadAdapter(accent,
            onAction = { item, action, anchor -> onAction(item, action, anchor) },
            onLongPress = { item -> if (!moving) toggle(item.id) })
        b.moveBtn.setOnClickListener { requestMove(adapter.selected) }
        b.selShare.setOnClickListener { shareSelected() }
        b.selDelete.setOnClickListener { confirmDeleteSelected() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        prompt = PrivateAuth.create(this,
            onUnlocked = { prompting = false; showUnlocked() },
            onFailed = { noLock, msg ->
                prompting = false
                if (noLock) {
                    Toast.makeText(this, "Set a screen lock in Settings to use private downloads", Toast.LENGTH_LONG).show()
                    finish()
                } else if (msg.isNotBlank()) {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            })
        // The button always asks, even if a prompt was lost (e.g. not restored after rotation).
        b.unlockBtn.setOnClickListener { prompting = false; askToUnlock() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCenter.privateItems.collect { render(it) }
            }
        }
        // Locked: onStart (which always follows) shows the locked screen and asks once.
        if (PrivateLock.isUnlocked()) showUnlocked()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PROMPTING, prompting)
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
        setSelection(emptySet())
        if (PrivateAuth.isAvailable(this)) askToUnlock()
    }

    private fun askToUnlock() {
        if (prompting || PrivateLock.isUnlocked()) return
        prompting = true
        PrivateAuth.prompt(prompt)
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
        val n = list.size
        val bytes = DlFormat.bytes(DownloadCenter.privateVaultBytes())
        b.storageLine.text = if (n == 0) "Empty vault" else "$n ${if (n == 1) "file" else "files"} · $bytes"
        // Drop selected ids that left the vault (moved, deleted elsewhere).
        val ids = list.map { it.id }.toSet()
        if (adapter.selected.any { it !in ids }) setSelection(adapter.selected intersect ids) else renderChrome()
    }

    // ------------------------------------------------------------ selection mode (2.5.1)

    private fun toggle(id: Long) =
        setSelection(if (id in adapter.selected) adapter.selected - id else adapter.selected + id)

    private fun setSelection(ids: Set<Long>) {
        if (!::adapter.isInitialized) return
        adapter.selected = ids
        renderChrome()
    }

    /** Toolbar + bottom bar for normal vs selection mode (mockups-2.5.1 move-2-select). */
    private fun renderChrome() {
        val list = DownloadCenter.privateItems.value
        val sel = adapter.selected
        val selecting = sel.isNotEmpty() && PrivateLock.isUnlocked()
        exitSelection.isEnabled = selecting
        b.selectBar.isVisible = selecting && !moving
        b.movingBar.isVisible = moving
        selectAllItem?.isVisible = selecting && sel.size < list.size
        clearItem?.isVisible = !selecting && list.isNotEmpty() && PrivateLock.isUnlocked()
        if (selecting) {
            b.toolbar.setNavigationIcon(R.drawable.ic_close)
            b.toolbar.title = getString(R.string.vault_selected, sel.size)
            b.toolbar.subtitle = DlFormat.bytes(list.filter { it.id in sel }.sumOf { maxOf(it.total, it.downloaded, 0L) })
        } else {
            b.toolbar.setNavigationIcon(R.drawable.ic_back)
            b.toolbar.title = "Private downloads"
            val n = list.size
            b.toolbar.subtitle = if (n == 0) null else "$n locked ${if (n == 1) "file" else "files"}"
        }
    }

    private fun selectedItems() = DownloadCenter.privateItems.value.filter { it.id in adapter.selected }

    private fun shareSelected() {
        val items = selectedItems().filter { DownloadCenter.fileExists(it) }
        if (items.isEmpty()) return
        if (items.size == 1) { share(items[0]); return }
        val uris = ArrayList(items.mapNotNull { DownloadCenter.uriFor(it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, null)) }
        catch (_: Exception) { Toast.makeText(this, "Can't share these files", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDeleteSelected() {
        val items = selectedItems(); val n = items.size
        if (n == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete $n ${if (n == 1) "file" else "files"}?")
            .setMessage("Removes them from the private vault. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> items.forEach { DownloadCenter.deletePrivate(it.id) }; setSelection(emptySet()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ------------------------------------------------------------ move to Downloads (2.5.1, roadmap item 1)

    private fun requestMove(ids: Set<Long>) {
        if (ids.isEmpty() || moving) return
        // Android 8-9 need the legacy storage permission to write into public Downloads/Ulfur.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingMove = ids
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        confirmMove(ids)
    }

    /** Always confirm, for one file or many; the Gallery warning sits in its own tinted box (move-3-confirm). */
    private fun confirmMove(ids: Set<Long>) {
        val n = DownloadCenter.privateItems.value.count { it.id in ids }
        if (n == 0) return
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_vault_move, null)
        content.findViewById<TextView>(R.id.moveBody).setText(R.string.vault_move_body)
        content.findViewById<TextView>(R.id.moveWarning).setText(R.string.vault_move_warning)
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_drive_file_move)
            .setTitle(resources.getQuantityString(R.plurals.vault_move_title, n, n))
            .setView(content)
            .setPositiveButton(R.string.vault_move_confirm) { _, _ -> startMove(ids, n) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun startMove(ids: Set<Long>, n: Int) {
        moving = true
        b.movingText.text = resources.getQuantityString(R.plurals.vault_moving, n, n)
        renderChrome()
        // FLAG_SECURE stays on for the whole move: the dialog and snackbar live inside the vault screen.
        DownloadCenter.moveToDownloads(ids) { moved, failed ->
            moving = false
            setSelection(emptySet())
            if (isFinishing || isDestroyed) return@moveToDownloads
            val msg = if (failed.isEmpty()) resources.getQuantityString(R.plurals.vault_moved, moved.size, moved.size)
                else resources.getQuantityString(R.plurals.vault_move_partial, moved.size, moved.size,
                    failed.joinToString(", ") { it.fileName })
            val bar = Snackbar.make(b.root, msg, if (failed.isEmpty()) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE)
            if (moved.isNotEmpty()) bar.setAction(R.string.vault_move_open) { openDownloadsFolder() }
            else bar.setAction(android.R.string.ok) { }
            bar.setActionTextColor(snackActionColor(this, palette.color))
            bar.show()
        }
    }

    /** Opens Downloads/Ulfur in the system Files app; falls back to the in-app Downloads list. */
    private fun openDownloadsFolder() {
        val folder = getString(R.string.downloads_folder)
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$folder")
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(view) } catch (_: Exception) {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
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
        pm.menu.add(R.string.vault_move).setOnMenuItemClickListener { requestMove(setOf(d.id)); true }
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
