package com.jamhowman.beastbrowser.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.BuildConfig
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.UiTheme
import com.jamhowman.beastbrowser.data.Stats
import com.jamhowman.beastbrowser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Prefs.accent.overlay, true)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        accentLine(b.settingsAccentLine, Prefs.accent.color, 0x99)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val backupUi = BackupUi(this)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = Prefs.FILE
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<androidx.preference.ListPreference>("ui_theme")?.setOnPreferenceChangeListener { _, value ->
                UiTheme.apply(value as String)
                true
            }
            // 2.3.8: the accent row edits the current realm's accent (`accent` / `accent_work`; Ghost is fixed).
            findPreference<AccentPreference>("accent")?.apply {
                val realm = Prefs.realm
                title = "Accent · ${realm.label} realm"
                fallback = Prefs.defaultAccent(realm)
                val key = Prefs.accentKey(realm)
                if (key == null) {
                    isEnabled = false
                    summary = "Ghost is always Ultraviolet"
                } else {
                    this.key = key
                }
                setOnPreferenceChangeListener { _, _ -> view?.post { activity?.recreate() }; true }
            }
            findPreference<Preference>("reset_counter")?.apply {
                summary = "${fmt(Stats.total.get())} blocked so far"
                setOnPreferenceClickListener { Stats.reset(); summary = "0 blocked so far"; true }
            }
            findPreference<Preference>("ublock_dashboard")?.setOnPreferenceClickListener {
                val url = Engine.ublock?.metaData?.optionsPageUrl
                if (url != null) {
                    requireActivity().setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url))
                    requireActivity().finish()
                } else {
                    MaterialAlertDialogBuilder(requireContext()).setMessage("uBlock Origin is still starting. Try again in a moment.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                true
            }
            findPreference<Preference>("passwords")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PasswordsActivity::class.java)); true
            }
            findPreference<Preference>("downloads")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DownloadsActivity::class.java)); true
            }
            findPreference<Preference>("clear_now")?.setOnPreferenceClickListener { confirmClear(); true }
            findPreference<Preference>("backup_export")?.setOnPreferenceClickListener { backupUi.startExport(); true }
            findPreference<Preference>("backup_import")?.setOnPreferenceClickListener { backupUi.startImport(); true }
            findPreference<Preference>("check_updates")?.let { com.jamhowman.beastbrowser.update.Updater.bindPreference(this, it) }
            findPreference<Preference>("about")?.apply {
                summary = "Version ${BuildConfig.VERSION_NAME} · GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION}"
                setOnPreferenceClickListener { showAbout(); true }
            }
        }

        private fun confirmClear() {
            val labels = arrayOf("Browsing history", "Cookies & site data", "Cached images and files")
            val checked = booleanArrayOf(true, true, true)
            MaterialAlertDialogBuilder(requireContext()).setTitle("Clear browsing data")
                .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Clear") { _, _ ->
                    if (checked[0]) BrowserDb.get(requireContext()).clearHistory()
                    Engine.clearSiteData(requireContext(), cookies = checked[1], cache = checked[2])
                    android.widget.Toast.makeText(requireContext(), "Cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }

        private fun showAbout() {
            val ubo = Engine.ublock?.metaData?.version ?: "not loaded"
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.app_name)
                .setMessage(
                    "Version ${BuildConfig.VERSION_NAME}\n\n" +
                    "Engine: Mozilla GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION} (MPL 2.0)\n" +
                    "Content blocker: uBlock Origin $ubo by Raymond Hill (GPLv3), bundled unmodified as a built-in extension\n\n" +
                    "No analytics, no telemetry, no accounts. Private tabs use Gecko private browsing."
                )
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }
}
