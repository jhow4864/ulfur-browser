package com.jamhowman.beastbrowser.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.BuildConfig
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.data.BrowserDb
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Stats
import com.jamhowman.beastbrowser.data.TrackerTallyDb
import com.jamhowman.beastbrowser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var screen: ThemedScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        screen = ThemedScreen(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        // 2.5: sub-screens (Secure DNS, Site content, tracker tally) live on the back stack; back pops them first.
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) b.toolbar.setTitle(R.string.settings)
        }
        savedInstanceState?.getCharSequence(STATE_TITLE)?.let { b.toolbar.title = it }
        accentLine(b.settingsAccentLine, screen.palette.color, 0x99)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, SettingsFragment()).commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(STATE_TITLE, b.toolbar.title)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val name = pref.fragment ?: return false
        val f = supportFragmentManager.fragmentFactory.instantiate(classLoader, name).apply { arguments = pref.extras }
        supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, f).addToBackStack(pref.key).commit()
        b.toolbar.title = if (f is AppearanceFragment) getString(R.string.appearance_title) else pref.title
        return true
    }

    /**
     * 2.8 Settings > Appearance picked a preset: re-apply this screen's overlay in place (the realm's palette may not
     * have changed, e.g. a Work pick while browsing Play), let [rebind] recolour the views, and crossfade from a
     * snapshot of the old look for 200 ms (SPEC "Theme picker"). No-op animation when system animations are off.
     */
    fun onThemeChanged(rebind: () -> Unit) {
        val root = b.root
        val snapshot = if (root.isLaidOut && root.width > 0 && root.height > 0) root.drawToBitmap() else null
        if (screen.refresh()) accentLine(b.settingsAccentLine, screen.palette.color, 0x99)
        rebind()
        if (snapshot == null || !ValueAnimator.areAnimatorsEnabled()) return
        val old = snapshot.toDrawable(resources).apply { setBounds(0, 0, root.width, root.height) }
        root.overlay.add(old)
        ValueAnimator.ofInt(255, 0).apply {
            duration = 200
            addUpdateListener { old.alpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.overlay.remove(old)
                    snapshot.recycle()
                }
            })
        }.start()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onResume() {
            super.onResume()
            // Sub-screens change these; refresh their rows' summaries when coming back.
            findPreference<Preference>("secure_dns")?.summary = SecureDnsFragment.rowSummary(this)
            findPreference<Preference>("site_content")?.summary = SiteContentFragment.rowSummary(this)
            findPreference<Preference>("tracker_tally")?.summary = TrackerTallyFragment.rowSummary(this)
            findPreference<Preference>("appearance")?.summary = AppearanceFragment.rowSummary(this)
            refreshCrashReports()
        }

        private val backupUi = BackupUi(this)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = Prefs.FILE
            setPreferencesFromResource(R.xml.preferences, rootKey)

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
            findPreference<Preference>("video_download")?.setOnPreferenceChangeListener { _, on ->
                if (on == false) com.jamhowman.beastbrowser.media.MediaSniffer.clearAll() // 2.8: forget detected streams
                true
            }
            findPreference<Preference>("clear_now")?.setOnPreferenceClickListener { confirmClear(); true }
            findPreference<Preference>("backup_export")?.setOnPreferenceClickListener { backupUi.startExport(); true }
            findPreference<Preference>("backup_import")?.setOnPreferenceClickListener { backupUi.startImport(); true }
            // 2.5.1: opt-in crash reports. Turning it off offers to delete what's already saved.
            findPreference<SwitchPreferenceCompat>("crash_reports")?.setOnPreferenceChangeListener { _, on ->
                if (on == false) CrashReportUi.offerDeleteOnDisable(requireContext()) { refreshCrashReports() }
                true
            }
            findPreference<Preference>("crash_reports_saved")?.setOnPreferenceClickListener {
                CrashReportUi.showList(requireContext(), onReport = ::openInBrowser) { refreshCrashReports() }
                true
            }
            findPreference<Preference>("check_updates")?.let { com.jamhowman.beastbrowser.update.Updater.bindPreference(this, it) }
            findPreference<Preference>("about")?.apply {
                summary = "Version ${BuildConfig.VERSION_NAME} · GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION}"
                setOnPreferenceClickListener { showAbout(); true }
            }
        }

        private fun refreshCrashReports() {
            val ctx = context ?: return
            findPreference<Preference>("crash_reports_saved")?.apply {
                val n = CrashReporter.store(ctx).count()
                summary = CrashReportUi.summary(ctx, n)
                isEnabled = n > 0
            }
        }

        /** Opens [url] in a new tab of the browser, like the uBlock dashboard row. */
        private fun openInBrowser(url: String) {
            requireActivity().setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url))
            requireActivity().finish()
        }

        private fun confirmClear() {
            val labels = arrayOf("Browsing history", "Cookies & site data", "Cached images and files")
            val checked = booleanArrayOf(true, true, true)
            MaterialAlertDialogBuilder(requireContext()).setTitle("Clear browsing data")
                .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Clear") { _, _ ->
                    if (checked[0]) {
                        BrowserDb.get(requireContext()).clearHistory()
                        TrackerTallyDb.get(requireContext()).clear() // roadmap 10: per-site counts go with history
                    }
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

    private companion object { const val STATE_TITLE = "title" }
}
