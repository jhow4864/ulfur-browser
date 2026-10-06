package com.jamhowman.beastbrowser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ActivityPasswordsBinding
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.SavedLogin

class PasswordsActivity : AppCompatActivity() {
    private lateinit var b: ActivityPasswordsBinding
    private val adapter = Adapter { login -> confirmDelete(login) }
    private var listener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val accent = Prefs.accent
        theme.applyStyle(accent.overlay, true)
        super.onCreate(savedInstanceState)
        PasswordVault.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityPasswordsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        accentLine(b.accentLine, accent.color, 0x99)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.unlockBtn.setOnClickListener { doUnlock() }
        b.toolbar.menu.add("Lock").setOnMenuItemClickListener {
            PasswordVault.lock(); render(); true
        }
        b.toolbar.menu.add("Reset vault").setOnMenuItemClickListener {
            confirmReset(); true
        }
        listener = { runOnUiThread { render() } }
        PasswordVault.addListener(listener!!)
        render()
        if (!PasswordVault.isUnlocked()) doUnlock()
    }


    override fun onDestroy() {
        listener?.let { PasswordVault.removeListener(it) }
        listener = null
        super.onDestroy()
    }

    private fun doUnlock() {
        PasswordVault.unlock(this) { ok, err ->
            if (!ok && err == PasswordVault.ERR_NEEDS_RESET) {
                confirmReset(fromUnlockFailure = true)
            } else if (!ok && err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
            if (ok && PasswordVault.needsPersistAuth()) PasswordVault.flushPending(this) { }
            render()
        }
    }

    private fun confirmReset(fromUnlockFailure: Boolean = false) {
        val msg = if (fromUnlockFailure) {
            "This vault can't be unlocked (fingerprints changed, or it was encrypted with an old key). Reset deletes all saved passwords so you can start fresh."
        } else {
            "Delete all saved passwords and the encryption key? This can't be undone."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset vault?")
            .setMessage(msg)
            .setPositiveButton("Reset") { _, _ ->
                PasswordVault.resetVault()
                Toast.makeText(this, "Vault reset", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render() {
        val open = PasswordVault.isUnlocked()
        b.unlockBtn.isVisible = !open
        b.list.isVisible = open && PasswordVault.fetchAll().isNotEmpty()
        b.empty.isVisible = open && PasswordVault.fetchAll().isEmpty()
        b.status.text = when {
            !open && PasswordVault.hasPasswords() -> "Vault locked · ${PasswordVault.count()} saved ${if (PasswordVault.count() == 1) "login" else "logins"}"
            !open -> "Vault locked · nothing saved yet"
            else -> "Unlocked · ${PasswordVault.count()} ${if (PasswordVault.count() == 1) "login" else "logins"} · encrypted with your fingerprint"
        }
        if (open) adapter.submit(PasswordVault.fetchAll())
    }

    private fun confirmDelete(login: SavedLogin) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete password?")
            .setMessage("${login.username}\n${login.origin}")
            .setPositiveButton("Delete") { _, _ ->
                PasswordVault.delete(login.guid)
                if (PasswordVault.needsPersistAuth()) PasswordVault.flushPending(this) { }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private class Adapter(val onDelete: (SavedLogin) -> Unit) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<SavedLogin> = emptyList()
        fun submit(list: List<SavedLogin>) { items = list; notifyDataSetChanged() }
        class VH(val root: TextView) : RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val t = TextView(parent.context).apply {
                setPadding(28, 28, 28, 28)
                setTextColor(parent.context.getColor(R.color.text_primary))
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            }
            return VH(t)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.root.text = "${s.username}\n${s.origin}"
            holder.root.setOnLongClickListener { onDelete(s); true }
        }
    }
}
