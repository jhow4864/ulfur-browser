package com.jamhowman.beastbrowser.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.databinding.ItemUnlockFillBinding
import com.jamhowman.beastbrowser.databinding.SheetUnlockFillBinding
import com.jamhowman.beastbrowser.passwords.MetaLogin
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.util.Domains

/**
 * One-shot "Unlock to fill" sheet shown when Gecko asks for logins while the vault is locked
 * but meta lists saved usernames for the origin.
 */
object UnlockToFill {
    @Volatile private var showing = false

    fun isShowing(): Boolean = showing

    /**
     * @param onDone called on the main thread after success (vault unlocked) or cancel/dismiss.
     *               [unlocked] is true only after a successful biometric unlock.
     */
    fun show(
        activity: FragmentActivity,
        domain: String,
        meta: List<MetaLogin>,
        onDone: (unlocked: Boolean) -> Unit,
    ) {
        if (meta.isEmpty()) {
            onDone(false)
            return
        }
        if (showing) {
            onDone(false)
            return
        }
        if (PasswordVault.isUnlocked()) {
            onDone(true)
            return
        }
        showing = true
        var finished = false
        var awaitingUnlock = false
        fun finish(unlocked: Boolean) {
            if (finished) return
            finished = true
            showing = false
            onDone(unlocked)
        }

        val accent = Prefs.accent
        val dialog = BottomSheetDialog(activity)
        val sheet = SheetUnlockFillBinding.inflate(activity.layoutInflater)
        val host = runCatching { Uri.parse(if ("://" in domain) domain else "https://$domain").host }
            .getOrNull()?.removePrefix("www.") ?: domain
        sheet.unlockHost.text = Domains.display(host)

        meta.forEach { entry ->
            val row = ItemUnlockFillBinding.inflate(LayoutInflater.from(activity), sheet.unlockRows, false)
            val letter = entry.username.trim().firstOrNull()?.uppercaseChar()?.toString()
                ?: host.firstOrNull()?.uppercaseChar()?.toString()
                ?: "?"
            row.unlockAvatar.text = letter
            row.unlockAvatar.setTextColor(accent.color)
            (row.unlockAvatar.background?.mutate() as? GradientDrawable)?.setColor(
                activity.getColor(R.color.surface2),
            )
            row.unlockUsername.text = entry.username.ifBlank { "(no username)" }
            row.unlockAction.setTextColor(accent.color)
            row.unlockFingerprint.imageTintList = ColorStateList.valueOf(accent.color)
            row.root.setOnClickListener {
                // Dismiss sheet first, but don't treat that as cancel — biometric follows.
                awaitingUnlock = true
                dialog.dismiss()
                PasswordVault.unlock(activity) { ok, err ->
                    if (ok) {
                        Toast.makeText(activity, R.string.unlock_to_fill_toast, Toast.LENGTH_SHORT).show()
                        finish(true)
                    } else {
                        if (!err.isNullOrBlank()) {
                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                        }
                        finish(false)
                    }
                }
            }
            sheet.unlockRows.addView(row.root)
        }

        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener { if (!awaitingUnlock) finish(false) }
        dialog.show()
    }
}
