package com.jamhowman.beastbrowser.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.AutoplayPolicy
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.browser.ForcedDark
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Prefs

/** 2.5 Settings > Browsing > Site content: autoplay (+ allowed sites), dark mode (+ forced dark exceptions), PiP. */
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

        findPreference<SwitchPreferenceCompat>("force_dark")?.apply {
            title = SpannableStringBuilder(getString(R.string.pref_force_dark_title)).append("  ").apply {
                val start = length
                append(getString(R.string.pref_force_dark_beta))
                for (span in listOf<Any>(ForegroundColorSpan(Prefs.accent.color), RelativeSizeSpan(0.75f), StyleSpan(Typeface.BOLD))) {
                    setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            setOnPreferenceChangeListener { _, v -> syncDarkList(forceDark = v as Boolean); true }
        }
        findPreference<SwitchPreferenceCompat>("dark_pages")?.setOnPreferenceChangeListener { _, v ->
            syncDarkList(darkPages = v as Boolean); true
        }
        findPreference<Preference>("force_dark_off_sites")?.setOnPreferenceClickListener { showDarkOffSites(); true }
        syncDarkList()
    }

    /** Applies a forced dark change to open tabs straight away (MainActivity re-syncs on resume too). */
    private fun syncDarkList(darkPages: Boolean = Prefs.darkPages, forceDark: Boolean = Prefs.forceDark) {
        val sites = db.forceDarkOffSites()
        findPreference<Preference>("force_dark_off_sites")?.apply {
            // SPEC: the "Never force dark on" list only appears while forced dark is on.
            isVisible = darkPages && forceDark
            summary = if (sites.isEmpty()) getString(R.string.force_dark_off_sites_none)
            else resources.getQuantityString(R.plurals.force_dark_off_sites_count, sites.size, sites.size) + " · " + sites.joinToString(", ")
        }
        Engine.syncForceDark(darkPages && forceDark, sites)
    }

    /** "Never force dark on": tap a site to remove it; "+ Add site" asks for a host. */
    private fun showDarkOffSites() {
        val sites = db.forceDarkOffSites()
        val b = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.force_dark_off_sites)
            .setNeutralButton(R.string.force_dark_add_site) { _, _ -> showAddDarkOffSite() }
            .setNegativeButton(R.string.close, null)
        if (sites.isEmpty()) b.setMessage(R.string.force_dark_off_sites_none)
        else b.setItems(sites.toTypedArray()) { _, i ->
            val host = sites[i]
            MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.force_dark_remove_site, host))
                .setPositiveButton(R.string.remove) { _, _ -> db.setForceDarkOff(host, false); syncDarkList() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        b.show()
    }

    private fun showAddDarkOffSite(prefill: String = "") {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            hint = getString(R.string.force_dark_add_site_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(prefill)
        }
        val box = FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.force_dark_off_sites).setView(box)
            .setPositiveButton(R.string.add) { _, _ ->
                val site = ForcedDark.normalizeSite(input.text.toString())
                if (site == null) {
                    MaterialAlertDialogBuilder(ctx).setMessage(R.string.force_dark_add_site_error)
                        .setPositiveButton(android.R.string.ok) { _, _ -> showAddDarkOffSite(input.text.toString()) }.show()
                } else {
                    db.setForceDarkOff(site, true); syncDarkList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
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
            val dark = f.getString(if (Prefs.forceDarkActive) R.string.pref_on else R.string.pref_off)
            val pip = f.getString(if (Prefs.pipEnabled) R.string.pref_on else R.string.pref_off)
            return f.getString(R.string.pref_site_content_summary, autoplay, dark, pip)
        }
    }
}
