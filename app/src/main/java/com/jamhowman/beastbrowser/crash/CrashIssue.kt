package com.jamhowman.beastbrowser.crash

import java.net.URLEncoder

/**
 * 2.5.1: the "Report on GitHub" link for a [CrashReport]. It only opens GitHub's new-issue page pre-filled; the user
 * reads and edits it there and nothing is filed until they press Submit.
 *
 * GitHub ignores `body` when `template` names an issue form, and fills the form's fields from parameters named
 * after their ids instead (.github/ISSUE_TEMPLATE/bug_report.yml: what, version, phone, android, extra). So the
 * report goes into those fields, and `body` carries the same text for when the form isn't used. Long links fail
 * (414 / proxy limits), so the trace is trimmed until the whole URL is at most [MAX_URL] characters.
 */
object CrashIssue {
    const val NEW_ISSUE_URL = "https://github.com/jhow4864/ulfur-browser/issues/new"
    const val TEMPLATE = "bug_report.yml"
    /** Same as bug_report.yml's own `labels`; GitHub drops labels the reporter may not set. */
    const val LABELS = "bug"
    const val MAX_URL = 7_000
    const val MAX_TITLE = 100
    const val TRIM_NOTE = "Trace trimmed to fit the link. The full report is still saved on the phone."

    fun title(r: CrashReport): String {
        val t = when (r.kind) {
            CrashReport.Kind.APP -> "Crash: ${r.shortException.ifBlank { "app crash" }}"
            CrashReport.Kind.CONTENT -> "Crash: web content process (GeckoView ${r.gecko.ifBlank { "?" }})"
        }
        return if (t.length > MAX_TITLE) t.take(MAX_TITLE - 1) + "…" else t
    }

    /** Device/build facts as a Markdown list (no URLs, titles or tab data exist in a report to begin with). */
    fun details(r: CrashReport): String = buildString {
        append("- Ulfur: ").append(r.appVersion).append(" (").append(r.versionCode).append(")\n")
        append("- Android: ").append(r.android).append(" (API ").append(r.api).append(")\n")
        append("- Device: ").append(r.device).append('\n')
        if (r.gecko.isNotBlank()) append("- GeckoView: ").append(r.gecko).append('\n')
        append("- Type: ").append(r.kind.label).append('\n')
        if (r.thread.isNotBlank()) append("- Thread: ").append(r.thread).append('\n')
        if (r.process.isNotBlank()) append("- Process: ").append(r.process).append('\n')
        append("- Time: ").append(CrashFormat.timeUtc(r.time)).append('\n')
    }

    private fun traceBlock(trace: String, trimmed: Boolean): String = buildString {
        if (trace.isBlank()) {
            append("No Java stack trace: the crash happened inside GeckoView's web content process.\n")
        } else {
            append("```\n").append(trace.replace("```", "'''")).append("\n```\n")
            if (trimmed) append('\n').append(TRIM_NOTE).append('\n')
        }
    }

    /** The bug form's "Screenshots or anything else" field: facts plus the trace. */
    fun extra(r: CrashReport, trace: String, trimmed: Boolean): String =
        "Crash report saved by Ulfur's opt-in crash reporter.\n\n" + details(r) + "\n" + traceBlock(trace, trimmed)

    /** Plain issue body (used when the form isn't). */
    fun body(r: CrashReport, trace: String, trimmed: Boolean): String =
        "**What happened?**\nUlfur crashed. What I was doing:\n\n\n**Crash report**\n" + details(r) + "\n" + traceBlock(trace, trimmed)

    fun url(r: CrashReport, maxLength: Int = MAX_URL): String {
        val full = build(r, r.trace, trimmed = false)
        if (full.length <= maxLength) return full

        // Keep the first k frames of every section (top exception, each "Caused by:"), largest k that fits.
        val sections = sections(r.trace)
        val maxFrames = sections.maxOfOrNull { it.frames.size } ?: 0
        var lo = 0
        var hi = maxFrames
        var best: String? = null
        while (lo <= hi) {
            val k = (lo + hi) / 2
            val u = build(r, keepFrames(sections, k), trimmed = true)
            if (u.length <= maxLength) { best = u; lo = k + 1 } else hi = k - 1
        }
        best?.let { return it }

        // Even the exception lines alone are too long (huge messages): cut the text itself.
        val headers = keepFrames(sections, 0)
        lo = 0
        hi = headers.length
        while (lo <= hi) {
            val n = (lo + hi) / 2
            val u = build(r, headers.take(n).trimEnd() + (if (n < headers.length) " …" else ""), trimmed = true)
            if (u.length <= maxLength) { best = u; lo = n + 1 } else hi = n - 1
        }
        return best ?: build(r, "", trimmed = true)
    }

    private fun build(r: CrashReport, trace: String, trimmed: Boolean): String {
        val params = listOf(
            "template" to TEMPLATE,
            "labels" to LABELS,
            "title" to title(r),
            "what" to "Ulfur crashed. What I was doing:\n",
            "version" to r.appVersion,
            "phone" to r.device,
            "android" to r.android,
            "extra" to extra(r, trace, trimmed),
            "body" to body(r, trace, trimmed),
        )
        return NEW_ISSUE_URL + "?" + params.joinToString("&") { (k, v) -> k + "=" + encode(v) }
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- trace trimming

    class Section(val header: List<String>, val frames: List<String>)

    /** Splits a trace into sections: exception/"Caused by:" lines, each followed by its "at …" frames. */
    fun sections(trace: String): List<Section> {
        val out = mutableListOf<Section>()
        var header = mutableListOf<String>()
        var frames = mutableListOf<String>()
        for (line in trace.lines()) {
            val t = line.trimStart()
            val isFrame = t.startsWith("at ") || (t.startsWith("... ") && line != t)
            if (isFrame) {
                frames.add(line)
            } else {
                if (frames.isNotEmpty()) { out.add(Section(header, frames)); header = mutableListOf(); frames = mutableListOf() }
                header.add(line)
            }
        }
        if (header.isNotEmpty() || frames.isNotEmpty()) out.add(Section(header, frames))
        return out
    }

    /** The trace with at most [k] frames per section; dropped frames are counted in a "... n frames trimmed" line. */
    fun keepFrames(sections: List<Section>, k: Int): String = buildString {
        for (s in sections) {
            s.header.forEach { append(it).append('\n') }
            s.frames.take(k).forEach { append(it).append('\n') }
            val dropped = s.frames.size - k
            if (dropped > 0) append("\t... ").append(dropped).append(if (dropped == 1) " frame trimmed\n" else " frames trimmed\n")
        }
    }.trimEnd('\n')
}
