package com.jamhowman.beastbrowser.passwords

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.jamhowman.beastbrowser.browser.HelperSessions
import com.jamhowman.beastbrowser.ui.UnlockToFill
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * Sole Gecko autocomplete store: every save/fetch/used callback goes through [PasswordVault].
 * Credit-card and address delegates stay empty (defaults).
 *
 * Save confirmation lives in [com.jamhowman.beastbrowser.ui.Prompts.onLoginSave] — Gecko only
 * calls [onLoginSave] here after that prompt is confirmed.
 *
 * While locked: returns empty to Gecko (no biometric spam on focus). If meta lists logins for
 * the origin, posts a one-shot "Unlock to fill" sheet; after unlock the login field of the current
 * tab ([sessionProvider]) is soft re-focused through Beast Helper so Gecko fetches logins again.
 */
class VaultStorageDelegate(
    private val activityProvider: () -> FragmentActivity?,
    private val sessionProvider: () -> GeckoSession? = { null },
    /** 2.10 Ulfur #2: true while the current tab is private or the realm is Ghost: nothing is ever offered then. */
    private val privateProvider: () -> Boolean = { false },
    /** The current tab's URL: Gecko's fetch domain has no scheme, so the scheme comes from here (FillPolicy.fetchOrigin). */
    private val pageUrlProvider: () -> String? = { sessionProvider()?.let { HelperSessions.currentUrl(it) } },
) : Autocomplete.StorageDelegate {

    /**
     * 2.10 Ulfur #2: Gecko only ever gets usernames. Passwords are blanked here so Gecko can't fill one on its own
     * (page-load autofill); the real password is put in by Prompts.onLoginSelect after the per-fill check.
     */
    internal fun forGecko(list: List<SavedLogin>): Array<Autocomplete.LoginEntry> =
        PasswordVault.toGeckoArray(list.map { it.copy(password = "") })

    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
        if (privateProvider()) return GeckoResult.fromValue(emptyArray())
        val origin = com.jamhowman.beastbrowser.autofill.FillPolicy.fetchOrigin(domain, pageUrlProvider())
            ?: return GeckoResult.fromValue(emptyArray())
        if (PasswordVault.isUnlocked()) {
            return GeckoResult.fromValue(forGecko(PasswordVault.fetchForOrigin(origin)))
        }
        maybeOfferUnlockToFill(origin)
        return GeckoResult.fromValue(emptyArray())
    }

    override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> {
        // No site to match against: never hand Gecko the whole vault (2.10 Ulfur #2).
        return GeckoResult.fromValue(emptyArray())
    }

    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread { ensureUnlockedThenSave(activity, login) }
    }

    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
        // Memory + meta only — never flush/encrypt here (that was prompting fingerprint on every submit).
        PasswordVault.markUsed(login)
    }

    /**
     * Locked + meta hit → one-shot unlock sheet (debounced; never stacks).
     * Still returns empty from [onLoginFetch] so Gecko does not hang on a pending result.
     */
    private fun maybeOfferUnlockToFill(domain: String) {
        if (UnlockToFill.isShowing()) return
        val meta = PasswordVault.metaForOrigin(domain)
        if (meta.isEmpty()) return
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            if (PasswordVault.isUnlocked() || UnlockToFill.isShowing()) return@runOnUiThread
            val again = PasswordVault.metaForOrigin(domain)
            if (again.isEmpty()) return@runOnUiThread
            UnlockToFill.show(activity, domain, again) { unlocked ->
                if (unlocked) sessionProvider()?.let { HelperSessions.softFocusLoginField(it) }
            }
        }
    }

    private fun ensureUnlockedThenSave(activity: FragmentActivity, login: Autocomplete.LoginEntry) {
        fun doSave() {
            PasswordVault.saveLogin(login)
            if (PasswordVault.needsPersistAuth()) {
                PasswordVault.flushPending(activity) { ok ->
                    Toast.makeText(
                        activity,
                        if (ok) "Password saved"
                        else "Couldn't write yet — confirm fingerprint when prompted, or open Settings → Passwords",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(activity, "Password saved", Toast.LENGTH_SHORT).show()
            }
        }
        if (PasswordVault.isUnlocked()) {
            doSave()
        } else {
            PasswordVault.unlock(activity) { ok, err ->
                if (ok) doSave()
                else if (!err.isNullOrBlank()) Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
            }
        }
    }
}
