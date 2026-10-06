package com.jamhowman.beastbrowser.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.crash.CrashFormat
import com.jamhowman.beastbrowser.crash.CrashIssue
import com.jamhowman.beastbrowser.crash.CrashReport
import com.jamhowman.beastbrowser.crash.CrashReporter
import com.jamhowman.beastbrowser.crash.CrashStore
import com.jamhowman.beastbrowser.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * 2.5.1 crash reports, user side: the launch snackbar after a crash, the report viewer (Report on GitHub / Delete)
 * and Settings › Crash reports › Saved crash reports. "Report on GitHub" only opens a pre-filled issue page in a tab
 * ([CrashIssue]); nothing is ever sent by the app.
 */
object CrashReportUi {
    private const val PROMPT_MS = 10_000

    /** MainActivity start: a snackbar (never a blocking dialog) if a crash was recorded since the last prompt. */
    fun promptOnLaunch(activity: MainActivity) {
        if (!Prefs.crashReports) return
        val store = CrashReporter.store(activity)
        activity.lifecycleScope.launch {
            val newest = withContext(Dispatchers.IO) { runCatching { store.reports().firstOrNull() }.getOrNull() } ?: return@launch
            if (newest.report.time <= Prefs.crashReportsSeen) return@launch
            Prefs.crashReportsSeen = newest.report.time
            activity.snack(activity.getString(R.string.crash_prompt), activity.getString(R.string.crash_prompt_view), PROMPT_MS) {
                showReport(activity, store, newest.file, onReport = { url -> activity.newTab(url) })
            }
        }
    }

    /** The saved text exactly as stored, with Report on GitHub / Delete / Close. */
    fun showReport(ctx: Context, store: CrashStore, file: File, onReport: (String) -> Unit, onChanged: () -> Unit = {}) {
        val raw = store.text(file)
        val report = raw?.let { CrashFormat.parse(it) }
        if (raw == null || report == null) {
            MaterialAlertDialogBuilder(ctx).setMessage(R.string.crash_report_unreadable)
                .setPositiveButton(android.R.string.ok, null).show()
            onChanged()
            return
        }
        val pad = dp(ctx, 20)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(ctx).apply {
                setText(R.string.crash_report_note)
                setTextColor(ctx.getColor(R.color.text_secondary))
                textSize = 13f
            })
            addView(TextView(ctx).apply {
                text = raw.trimEnd()
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(ctx.getColor(R.color.text_primary))
                setTextIsSelectable(true)
                setPadding(0, pad / 2, 0, pad / 2)
            })
        }
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.crash_report_title)
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton(R.string.crash_report_github) { _, _ -> onReport(CrashIssue.url(report)) }
            .setNeutralButton(R.string.crash_report_delete) { _, _ ->
                store.delete(file)
                Toast.makeText(ctx, R.string.crash_report_deleted, Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    /** Settings: newest first; tap one to view it. */
    fun showList(ctx: Context, onReport: (String) -> Unit, onChanged: () -> Unit) {
        val store = CrashReporter.store(ctx)
        val entries = store.reports()
        if (entries.isEmpty()) { onChanged(); return }
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val labels = entries.map { ctx.getString(R.string.crash_list_item, df.format(Date(it.report.time)), label(ctx, it.report)) }
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.pref_crash_saved_title)
            .setItems(labels.toTypedArray()) { _, i -> showReport(ctx, store, entries[i].file, onReport, onChanged) }
            .setNeutralButton(R.string.crash_reports_delete_all) { _, _ ->
                MaterialAlertDialogBuilder(ctx).setTitle(R.string.crash_reports_delete_all_title)
                    .setPositiveButton(R.string.crash_report_delete) { _, _ -> deleteAll(ctx, store, onChanged) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    /** Turning the switch off: offer to delete what's already saved (new crashes stop being recorded either way). */
    fun offerDeleteOnDisable(ctx: Context, onChanged: () -> Unit) {
        val store = CrashReporter.store(ctx)
        val n = store.count()
        if (n == 0) return
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.crash_reports_off_title)
            .setMessage(ctx.resources.getQuantityString(R.plurals.crash_reports_off_body, n, n))
            .setPositiveButton(R.string.crash_report_delete) { _, _ -> deleteAll(ctx, store, onChanged) }
            .setNegativeButton(R.string.crash_reports_keep, null).show()
    }

    /** "Saved crash reports" row summary. */
    fun summary(ctx: Context, count: Int = CrashReporter.store(ctx).count()): String =
        if (count == 0) ctx.getString(R.string.crash_saved_none)
        else ctx.resources.getQuantityString(R.plurals.crash_saved_count, count, count)

    private fun deleteAll(ctx: Context, store: CrashStore, onChanged: () -> Unit) {
        store.deleteAll()
        Toast.makeText(ctx, R.string.crash_reports_deleted_all, Toast.LENGTH_SHORT).show()
        onChanged()
    }

    private fun label(ctx: Context, r: CrashReport): String = when (r.kind) {
        CrashReport.Kind.CONTENT -> ctx.getString(R.string.crash_kind_content)
        CrashReport.Kind.APP -> r.shortException.substringBefore(':').trim().ifBlank { ctx.getString(R.string.crash_kind_app) }
    }
}
