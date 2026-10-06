package com.jamhowman.beastbrowser.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.TrackerTallyDb
import com.jamhowman.beastbrowser.data.WeeklySummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Roadmap 10: Settings > Shields > Weekly tracker tally. Last 7 days from [TrackerTallyDb]: total, top 5 sites,
 * blocks per category, a note that the tally stays on the phone, and "Clear tally".
 */
class TrackerTallyFragment : PreferenceFragmentCompat() {
    private val tally by lazy { TrackerTallyDb.get(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        setPreferencesFromResource(R.xml.preferences_tracker_tally, rootKey)
        findPreference<Preference>("tally_clear")?.setOnPreferenceClickListener { confirmClear(); true }
    }

    override fun onResume() {
        super.onResume()
        render(tally.weekly())
    }

    private fun render(w: WeeklySummary) {
        findPreference<Preference>("tally_total")?.apply {
            title = resources.getQuantityString(R.plurals.tally_total, w.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), fmt(w.total))
            summary = if (w.isEmpty) getString(R.string.tally_none_yet) else getString(R.string.tally_range, day(w.fromDay), day(w.toDay))
        }
        fill("tally_sites", w.topSites.map { (site, n) -> site to fmt(n) })
        fill("tally_categories", w.byCategory.map { (cat, n) -> getString(cat.label) to fmt(n) })
        findPreference<Preference>("tally_clear")?.isEnabled = !w.isEmpty
    }

    /** Replaces the rows of category [key] with read-only title / count rows; hides it when there are none. */
    private fun fill(key: String, rows: List<Pair<String, String>>) {
        val cat = findPreference<PreferenceCategory>(key) ?: return
        cat.removeAll()
        cat.isVisible = rows.isNotEmpty()
        for ((title, count) in rows) {
            cat.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isSelectable = false
                isIconSpaceReserved = false
                this.title = title
                summary = count
            })
        }
    }

    private fun day(epochDay: Long): String {
        val locale = Locale.getDefault()
        return LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tally_clear_confirm)
            .setPositiveButton(R.string.tally_clear) { _, _ ->
                tally.clear()
                render(tally.weekly())
                Toast.makeText(requireContext(), R.string.tally_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    companion object {
        /** Summary of the Settings row: "123 blocked in the last 7 days". */
        fun rowSummary(f: PreferenceFragmentCompat): String {
            val total = TrackerTallyDb.get(f.requireContext()).weekly().total
            return if (total == 0L) f.getString(R.string.pref_tally_summary_empty) else f.getString(R.string.pref_tally_summary, fmt(total))
        }
    }
}
