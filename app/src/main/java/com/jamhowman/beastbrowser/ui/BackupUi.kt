package com.jamhowman.beastbrowser.ui

import android.net.Uri
import android.text.Editable
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.backup.BackupCrypto
import com.jamhowman.beastbrowser.backup.BackupCrypto.BackupException
import com.jamhowman.beastbrowser.backup.BackupManager
import com.jamhowman.beastbrowser.backup.BackupManager.Sections
import com.jamhowman.beastbrowser.backup.BackupPayload
import com.jamhowman.beastbrowser.backup.PasswordCsv
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.UiTheme
import com.jamhowman.beastbrowser.databinding.DialogBackupPassphraseBinding
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.reader.ReadingListDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Backup & restore. Export: pick sections → unlock the vault (if passwords) → passphrase twice →
 * encrypt ([BackupCrypto]) → save via SAF. Import: open via SAF → passphrase (or plain password CSV) →
 * pick sections → unlock (if passwords) → merge ([BackupManager.apply]) → write the vault → summary.
 *
 * Must be created while the fragment is being constructed (it registers activity-result launchers).
 */
class BackupUi(private val fragment: Fragment) {

    /** Encrypted bytes waiting for the user to choose where to save them (never plaintext). */
    private var pendingExport: ByteArray? = null
    private var pendingExportSummary: String = ""

    private val createDoc = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri == null || bytes == null) return@registerForActivityResult
        writeExport(uri, bytes)
    }

    private val openDoc = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readImport(uri)
    }

    private val activity: FragmentActivity get() = fragment.requireActivity()
    private fun str(id: Int, vararg args: Any) = fragment.getString(id, *args)
    private fun plural(id: Int, n: Int) = fragment.resources.getQuantityString(id, n, n)

    // ------------------------------------------------------------------ export

    fun startExport() {
        val ctx = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                listOf(
                    PasswordVault.count(),
                    BrowserDb.get(ctx).bookmarks().size,
                    SpeedDialStore.load().size,
                    ReadingListDb.get(ctx).count(),
                )
            }
            val labels = arrayOf(
                str(R.string.backup_item_passwords, counts[0]),
                str(R.string.backup_item_bookmarks, counts[1]),
                str(R.string.backup_item_speed_dial, counts[2]),
                str(R.string.backup_item_reading_list, counts[3]),
                str(R.string.backup_item_settings),
            )
            val checked = booleanArrayOf(counts[0] > 0, true, true, true, true)
            MaterialAlertDialogBuilder(ctx).setTitle(R.string.backup_choose_title)
                .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(R.string.backup_continue) { _, _ ->
                    val s = Sections(checked[0], checked[1], checked[2], checked[3], checked[4])
                    if (!s.logins || PasswordVault.isUnlocked()) askNewPassphrase(s, vaultFailed = false)
                    else PasswordVault.unlock(activity) { ok, err ->
                        if (!ok && err != null) toast(err)
                        askNewPassphrase(s, vaultFailed = !ok)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun askNewPassphrase(s: Sections, vaultFailed: Boolean) {
        val d = DialogBackupPassphraseBinding.inflate(fragment.layoutInflater)
        d.passphraseMessage.text = if (vaultFailed) str(R.string.backup_passphrase_message) + "\n\n" + str(R.string.backup_vault_locked)
            else str(R.string.backup_passphrase_message)
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.backup_passphrase_title).setView(d.root)
            .setPositiveButton(R.string.backup_continue, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = d.passphrase.text
                val p2 = d.passphraseConfirm.text
                d.passphraseLayout.error = null; d.passphraseConfirmLayout.error = null
                when {
                    (p1?.length ?: 0) < BackupCrypto.MIN_PASSPHRASE ->
                        d.passphraseLayout.error = str(R.string.backup_passphrase_too_short, BackupCrypto.MIN_PASSPHRASE)
                    p1.toString() != p2.toString() -> d.passphraseConfirmLayout.error = str(R.string.backup_passphrase_mismatch)
                    else -> {
                        val pass = takeChars(p1!!)
                        takeChars(p2!!).fill('\u0000')
                        dialog.dismiss()
                        encryptAndSave(s, pass)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun encryptAndSave(s: Sections, pass: CharArray) {
        val ctx = fragment.requireContext().applicationContext
        val progress = progress(R.string.backup_encrypting)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val payload = BackupManager.collect(ctx, s)
                    BackupCrypto.encrypt(payload.toJson().toByteArray(Charsets.UTF_8), pass) to payload
                }.also { pass.fill('\u0000') }
            }
            progress.dismiss()
            result.onSuccess { (bytes, payload) ->
                pendingExport = bytes
                pendingExportSummary = describe(payload)
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                createDoc.launch("ulfur-backup-$date.ulfur")
            }.onFailure { toast(str(R.string.backup_failed)) }
        }
    }

    private fun writeExport(uri: Uri, bytes: ByteArray) {
        val ctx = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
            }
            if (ok) MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.backup_saved_title)
                .setMessage(str(R.string.backup_saved, pendingExportSummary))
                .setPositiveButton(android.R.string.ok, null).show()
            else toast(str(R.string.backup_failed))
        }
    }

    private fun describe(p: BackupPayload): String = listOfNotNull(
        p.logins?.let { plural(R.plurals.backup_n_passwords, it.size) },
        p.bookmarks?.let { plural(R.plurals.backup_n_bookmarks, it.size) },
        p.speedDial?.let { plural(R.plurals.backup_n_tiles, it.size) },
        p.readingList?.let { plural(R.plurals.backup_n_articles, it.size) },
        p.settings?.let { plural(R.plurals.backup_n_settings, it.size) },
    ).joinToString(", ")

    // ------------------------------------------------------------------ import

    fun startImport() = openDoc.launch(arrayOf("*/*"))

    private fun readImport(uri: Uri) {
        val ctx = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            check(out.size() <= MAX_IMPORT_BYTES) { "too large" }
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            when {
                bytes == null -> toast(str(R.string.backup_read_failed))
                BackupCrypto.looksLikeBackup(bytes) -> askPassphrase(bytes)
                else -> {
                    val logins = withContext(Dispatchers.Default) { runCatching { PasswordCsv.parse(String(bytes, Charsets.UTF_8)) }.getOrNull() }
                    bytes.fill(0)
                    if (logins == null) error(str(R.string.backup_not_a_backup))
                    else chooseImport(BackupPayload(logins = logins), fromCsv = true)
                }
            }
        }
    }

    private fun askPassphrase(file: ByteArray, retry: Boolean = false) {
        val d = DialogBackupPassphraseBinding.inflate(fragment.layoutInflater)
        d.passphraseMessage.text = str(R.string.backup_unlock_message)
        d.passphraseConfirmLayout.isVisible = false
        if (retry) d.passphraseLayout.error = str(R.string.backup_wrong_passphrase)
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.backup_unlock_title).setView(d.root)
            .setPositiveButton(R.string.backup_continue, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = d.passphrase.text
                if (text.isNullOrEmpty()) return@setOnClickListener
                val pass = takeChars(text)
                dialog.dismiss()
                decrypt(file, pass)
            }
        }
        dialog.show()
    }

    private fun decrypt(file: ByteArray, pass: CharArray) {
        val progress = progress(R.string.backup_decrypting)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { BackupPayload.fromJson(String(BackupCrypto.decrypt(file, pass), Charsets.UTF_8)) }
                    .also { pass.fill('\u0000') }
            }
            progress.dismiss()
            result.onSuccess { file.fill(0); chooseImport(it, fromCsv = false) }.onFailure { e ->
                when (e) {
                    is BackupException.WrongPassphraseOrDamaged -> askPassphrase(file, retry = true)
                    is BackupException.BadFormat -> error(str(R.string.backup_unreadable, e.message.orEmpty()))
                    else -> error(str(R.string.backup_wrong_passphrase))
                }
            }
        }
    }

    private fun chooseImport(p: BackupPayload, fromCsv: Boolean) {
        data class Item(val label: String, val pick: (Sections, Boolean) -> Sections)
        val items = listOfNotNull(
            p.logins?.let { Item(str(R.string.backup_item_passwords, it.size)) { s, on -> s.copy(logins = on) } },
            p.bookmarks?.let { Item(str(R.string.backup_item_bookmarks, it.size)) { s, on -> s.copy(bookmarks = on) } },
            p.speedDial?.let { Item(str(R.string.backup_item_speed_dial, it.size)) { s, on -> s.copy(speedDial = on) } },
            p.readingList?.let { Item(str(R.string.backup_item_reading_list, it.size)) { s, on -> s.copy(readingList = on) } },
            p.settings?.let { Item(str(R.string.backup_item_settings)) { s, on -> s.copy(settings = on) } },
        )
        if (items.isEmpty()) { error(str(R.string.backup_imported_nothing)); return }
        val checked = BooleanArray(items.size) { true }
        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.backup_import_choose_title)
            .setMultiChoiceItems(items.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.backup_continue) { _, _ ->
                var s = Sections(false, false, false, false, false)
                items.forEachIndexed { i, it -> s = it.pick(s, checked[i]) }
                if (s.logins && !p.logins.isNullOrEmpty() && !PasswordVault.isUnlocked()) {
                    PasswordVault.unlock(activity) { ok, err -> if (!ok && err != null) toast(err); applyImport(p, s, fromCsv) }
                } else applyImport(p, s, fromCsv)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun applyImport(p: BackupPayload, s: Sections, fromCsv: Boolean) {
        val ctx = fragment.requireContext().applicationContext
        val progress = progress(R.string.backup_importing)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val sum = withContext(Dispatchers.IO) { runCatching { BackupManager.apply(ctx, p, s) }.getOrNull() }
            progress.dismiss()
            if (sum == null) { error(str(R.string.backup_read_failed)); return@launch }
            if (sum.themeChanged) UiTheme.apply()
            val show = { saved: Boolean -> showSummary(sum, saved, fromCsv) }
            if (PasswordVault.needsPersistAuth()) PasswordVault.flushPending(activity) { ok -> show(ok) } else show(true)
        }
    }

    private fun showSummary(sum: BackupManager.ImportSummary, vaultSaved: Boolean, fromCsv: Boolean) {
        val parts = listOfNotNull(
            sum.loginsAdded.takeIf { it > 0 }?.let { plural(R.plurals.backup_n_passwords, it) },
            sum.bookmarks.takeIf { it > 0 }?.let { plural(R.plurals.backup_n_bookmarks, it) },
            sum.folders.takeIf { it > 0 }?.let { plural(R.plurals.backup_n_folders, it) },
            sum.speedDial.takeIf { it > 0 }?.let { plural(R.plurals.backup_n_tiles, it) },
            sum.articles.takeIf { it > 0 }?.let { plural(R.plurals.backup_n_articles, it) },
            sum.settings.takeIf { it > 0 }?.let { plural(R.plurals.backup_n_settings, it) },
        )
        val lines = buildList {
            add(if (parts.isEmpty() && sum.loginsUpdated == 0) str(R.string.backup_imported_nothing) else if (parts.isEmpty()) "" else str(R.string.backup_imported, parts.joinToString(", ")))
            if (sum.loginsUpdated > 0) add(plural(R.plurals.backup_n_passwords_updated, sum.loginsUpdated))
            if (sum.loginsSkipped > 0) add(plural(R.plurals.backup_n_passwords_skipped, sum.loginsSkipped))
            if (sum.loginsLocked) add(str(R.string.backup_passwords_locked))
            if (!vaultSaved && sum.loginsAdded + sum.loginsUpdated > 0) add(str(R.string.backup_passwords_not_saved))
            if (fromCsv) add(str(R.string.backup_csv_warning))
        }.filter { it.isNotEmpty() }
        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.backup_import_done_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok) { _, _ -> if (sum.settings > 0) activity.recreate() }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    /** Copies the passphrase out of the EditText's buffer and clears the field. */
    private fun takeChars(e: Editable): CharArray = CharArray(e.length) { e[it] }.also { e.clear() }

    private fun progress(msgRes: Int): AlertDialog {
        val ctx = fragment.requireContext()
        val pad = dp(ctx, 24)
        val row = LinearLayout(ctx).apply {
            setPadding(pad, pad, pad, pad / 2)
            addView(CircularProgressIndicator(ctx).apply { isIndeterminate = true })
        }
        return MaterialAlertDialogBuilder(ctx).setTitle(msgRes).setView(row).setCancelable(false).show()
    }

    private fun error(msg: String) {
        MaterialAlertDialogBuilder(fragment.requireContext()).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun toast(msg: String) = Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val MAX_IMPORT_BYTES = 128 * 1024 * 1024
    }
}
