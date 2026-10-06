package com.jamhowman.beastbrowser.ui

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.AutoplayPolicy
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Prefs

/** 2.5 Settings > Browsing > Site content: autoplay (+ allowed sites) and picture-in-picture. */
class SiteContentFragment : PreferenceFragmentCompat() {
    private val db by lazy { BrowserDb.get(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        setPreferencesFromResource(R.xml.preferences_site_content, rootKey)
        findPreference<ListPreference>("autoplay")?.apply {
            summary = autoplaySummary(AutoplayPolicy.Mode.from(value))
            setOnPreferenceChangeListener { p, v -> p.summary = autoplaySummary(AutoplayPolicy.Mode.from(v as String)); true }
        }
        findPreference<Preference>("autoplay_allowed_sites")?.setOnPreferenceClickListener { showAllowedSites(); true }
        refreshAllowedSites()
    }

    private fun autoplaySummary(m: AutoplayPolicy.Mode) = getString(when (m) {
        AutoplayPolicy.Mode.BLOCK_ALL -> R.string.autoplay_block_all_summary
        AutoplayPolicy.Mode.BLOCK_AUDIBLE -> R.string.autoplay_block_audible_summary
        AutoplayPolicy.Mode.ALLOW_ALL -> R.string.autoplay_allow_all_summary
    })

    private fun allowedSites() = db.autoplaySites().filterValues { it == AutoplayPolicy.Mode.ALLOW_ALL.key }.keys.toList()

    private fun refreshAllowedSites() {
        val n = allowedSites().size
        findPreference<Preference>("autoplay_allowed_sites")?.summary =
            if (n == 0) getString(R.string.autoplay_allowed_sites_none)
            else resources.getQuantityString(R.plurals.autoplay_allowed_sites_count, n, n)
    }

    /** Hosts allowed to autoplay; tap one to remove it. */
    private fun showAllowedSites() {
        val sites = allowedSites()
        if (sites.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.autoplay_allowed_sites)
            .setItems(sites.toTypedArray()) { _, i ->
                val host = sites[i]
                MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.autoplay_remove_site, host))
                    .setMessage(R.string.autoplay_remove_site_body)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        db.setAutoplay(host, null)
                        Engine.resetAutoplayPermissions(host)
                        refreshAllowedSites()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    companion object {
        /** Summary of the Site content row: "Autoplay: Block audio only · Picture-in-picture: On". */
        fun rowSummary(f: PreferenceFragmentCompat): String {
            val autoplay = f.getString(when (Prefs.autoplay) {
                AutoplayPolicy.Mode.BLOCK_ALL -> R.string.autoplay_block_all
                AutoplayPolicy.Mode.BLOCK_AUDIBLE -> R.string.autoplay_block_audible
                AutoplayPolicy.Mode.ALLOW_ALL -> R.string.autoplay_allow_all
            })
            val pip = f.getString(if (Prefs.pipEnabled) R.string.pref_on else R.string.pref_off)
            return f.getString(R.string.pref_site_content_summary, autoplay, pip)
        }
    }
}
