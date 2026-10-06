package com.jamhowman.beastbrowser.ui

import android.os.Bundle
import android.text.InputType
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.SecureDns

/**
 * 2.5 Settings > Privacy & security > Secure DNS: Off / Automatic / Strict, then the provider (Cloudflare, Quad9,
 * NextDNS config ID, AdGuard, Custom https URL). Changes reach Gecko in Engine.applySettings() when the browser resumes.
 */
class SecureDnsFragment : PreferenceFragmentCompat() {
    private lateinit var mode: ListPreference
    private lateinit var provider: ListPreference
    private lateinit var nextDns: EditTextPreference
    private lateinit var custom: EditTextPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        setPreferencesFromResource(R.xml.preferences_secure_dns, rootKey)
        mode = findPreference("doh_mode")!!
        provider = findPreference("doh_provider")!!
        nextDns = findPreference("doh_nextdns_id")!!
        custom = findPreference("doh_custom_url")!!

        // Show the effective choice (defaults and removed-provider fallbacks applied) without persisting it.
        val choice = Prefs.secureDnsChoice
        mode.value = choice.mode.key
        provider.value = choice.provider.key
        sync()

        mode.setOnPreferenceChangeListener { _, v ->
            Prefs.sp.edit {
                putString("doh_mode", v as String)
                putString("doh_provider", provider.value) // pin the provider shown (e.g. after a fallback)
            }
            view?.post { sync() }
            true
        }
        provider.setOnPreferenceChangeListener { _, v ->
            Prefs.sp.edit { putString("doh_provider", v as String) }
            view?.post {
                sync()
                // Picking NextDNS / Custom without a usable value: ask for it straight away.
                val p = SecureDns.Provider.fromOrNull(v as String)
                if (p == SecureDns.Provider.NEXTDNS && SecureDns.normalizeNextDnsId(nextDns.text) == null) onDisplayPreferenceDialog(nextDns)
                if (p == SecureDns.Provider.CUSTOM && SecureDns.validateCustomUrl(custom.text) !is SecureDns.UrlCheck.Ok) onDisplayPreferenceDialog(custom)
            }
            true
        }

        nextDns.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.isSingleLine = true
            it.hint = "abc123"
            it.setSelection(it.text.length)
        }
        nextDns.setOnPreferenceChangeListener { _, v ->
            val id = SecureDns.normalizeNextDnsId(v as String)
            when {
                id == null -> { error(getString(R.string.doh_nextdns_id_error)) { onDisplayPreferenceDialog(nextDns) }; false }
                id != v -> { nextDns.text = id; sync(); false }
                else -> { view?.post { sync() }; true }
            }
        }

        custom.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.isSingleLine = true
            it.hint = getString(R.string.doh_custom_hint)
            it.setSelection(it.text.length)
        }
        custom.setOnPreferenceChangeListener { _, v ->
            when (val c = SecureDns.validateCustomUrl(v as String)) {
                is SecureDns.UrlCheck.Ok -> if (c.url != v) { custom.text = c.url; sync(); false } else { view?.post { sync() }; true }
                is SecureDns.UrlCheck.Invalid -> { error(dohErrorText(c.error)) { onDisplayPreferenceDialog(custom) }; false }
            }
        }
    }

    private fun sync() {
        val m = SecureDns.Mode.from(mode.value)
        val p = SecureDns.Provider.fromOrNull(provider.value) ?: SecureDns.DEFAULT_PROVIDER
        mode.summary = getString(when (m) {
            SecureDns.Mode.OFF -> R.string.doh_mode_off_summary
            SecureDns.Mode.DEFAULT -> R.string.doh_mode_automatic_summary
            SecureDns.Mode.MAX -> R.string.doh_mode_strict_summary
        })
        // The provider card is disabled while Off; the choice is remembered.
        findPreference<PreferenceCategory>("doh_provider_category")?.isEnabled = m != SecureDns.Mode.OFF
        provider.summary = providerSummary(p)
        nextDns.isVisible = p == SecureDns.Provider.NEXTDNS
        custom.isVisible = p == SecureDns.Provider.CUSTOM
        nextDns.summary = SecureDns.normalizeNextDnsId(nextDns.text)?.let { SecureDns.NEXTDNS_BASE + it }
            ?: getString(R.string.pref_doh_nextdns_summary_missing)
        custom.summary = (SecureDns.validateCustomUrl(custom.text) as? SecureDns.UrlCheck.Ok)?.url
            ?: getString(R.string.pref_doh_custom_summary_missing)
    }

    private fun providerSummary(p: SecureDns.Provider): String {
        val (name, desc) = when (p) {
            SecureDns.Provider.CLOUDFLARE -> R.string.doh_cloudflare to R.string.doh_cloudflare_summary
            SecureDns.Provider.QUAD9 -> R.string.doh_quad9 to R.string.doh_quad9_summary
            SecureDns.Provider.NEXTDNS -> R.string.doh_nextdns to R.string.doh_nextdns_summary
            SecureDns.Provider.ADGUARD -> R.string.doh_adguard to R.string.doh_adguard_summary
            SecureDns.Provider.CUSTOM -> R.string.doh_custom to R.string.doh_custom_summary
        }
        return getString(name) + " · " + getString(desc) + (p.uri?.let { "\n$it" } ?: "")
    }

    private fun error(msg: String, retry: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> retry() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun dohErrorText(e: SecureDns.UrlError) = getString(when (e) {
        SecureDns.UrlError.EMPTY -> R.string.doh_error_empty
        SecureDns.UrlError.NOT_HTTPS -> R.string.doh_error_not_https
        SecureDns.UrlError.NO_HOST -> R.string.doh_error_no_host
        SecureDns.UrlError.MALFORMED -> R.string.doh_error_malformed
        SecureDns.UrlError.TOO_LONG -> R.string.doh_error_too_long
    })

    companion object {
        /** Summary for the Secure DNS row in Privacy & security: Off · Automatic · X · Strict · X · not set. */
        fun rowSummary(f: PreferenceFragmentCompat): String {
            val c = Prefs.secureDnsChoice
            if (c.mode == SecureDns.Mode.OFF) return f.getString(R.string.pref_doh_summary_off)
            if (Prefs.secureDns.customInvalid) return f.getString(
                if (c.provider == SecureDns.Provider.NEXTDNS) R.string.pref_doh_nextdns_summary_missing else R.string.pref_doh_custom_summary_missing
            )
            val name = f.getString(when (c.provider) {
                SecureDns.Provider.CLOUDFLARE -> R.string.doh_cloudflare
                SecureDns.Provider.QUAD9 -> R.string.doh_quad9
                SecureDns.Provider.NEXTDNS -> R.string.doh_nextdns
                SecureDns.Provider.ADGUARD -> R.string.doh_adguard
                SecureDns.Provider.CUSTOM -> R.string.doh_custom
            })
            return f.getString(if (c.mode == SecureDns.Mode.MAX) R.string.pref_doh_summary_strict else R.string.pref_doh_summary_automatic, name)
        }
    }
}
