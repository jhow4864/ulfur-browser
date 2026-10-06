package com.jamhowman.beastbrowser.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 2.5.1: one opt-in crash report, stored as a plain-text file in filesDir/crash-reports/ ([CrashStore]).
 * Holds device/build facts and a scrubbed stack trace only: never URLs, titles, history or anything about tabs.
 */
data class CrashReport(
    val time: Long,
    val kind: Kind,
    val appVersion: String,
    val versionCode: Long,
    val android: String,
    val api: Int,
    val device: String,
    val gecko: String,
    val process: String,
    val thread: String,
    /** First line of the trace ("java.lang.IllegalStateException: message"), already scrubbed. */
    val exception: String,
    /** Scrubbed, size-capped stack trace ([CrashFormat.traceOf]); empty for content process crashes. */
    val trace: String,
) {
    enum class Kind(val key: String, val label: String) {
        /** Uncaught Java/Kotlin exception in one of our processes. */
        APP("app", "App crash"),
        /** GeckoView content process crash (GeckoSession.ContentDelegate.onCrash): no Java stack to show. */
        CONTENT("content", "Web content process crash");

        companion object {
            fun from(key: String?) = entries.firstOrNull { it.key == key } ?: APP
        }
    }

    /** "IllegalStateException: boom" (package dropped) for titles and lists. */
    val shortException: String get() {
        val cls = exception.substringBefore(':').trim()
        val rest = exception.substring(cls.length.coerceAtMost(exception.length))
        return cls.substringAfterLast('.') + rest
    }
}

/** Text format, scrubbing and size caps for [CrashReport]. Pure Kotlin so it runs in JVM unit tests. */
object CrashFormat {
    const val HEADER = "Ulfur crash report"
    const val FORMAT = 1
    /** Longest stack trace kept in a report (the file is capped at [MAX_FILE_BYTES] on top of this). */
    const val MAX_TRACE_CHARS = 32_000
    /** Longest single trace line (huge exception messages, e.g. JSON dumps, are cut). */
    const val MAX_LINE_CHARS = 500
    /** Longest header value (device, process, thread, exception summary). */
    const val MAX_FIELD_CHARS = 200
    const val MAX_FILE_BYTES = 64 * 1024
    /** Raw stack trace text is cut to this before scrubbing so a giant message can't stall the crash handler. */
    private const val MAX_RAW_CHARS = 256 * 1024

    const val URL_MASK = "<url>"
    const val EMAIL_MASK = "<email>"
    const val IP_MASK = "<ip>"
    const val PATH_MASK = "<path>"

    // ---------------------------------------------------------------- scrubbing

    private const val END = """[^\s"'<>]"""
    private val SCRUBBERS: List<Pair<Regex, String>> = listOf(
        // Known schemes first, whole: about:blank, data:…, blob:https://…, javascript:…, file:///…, beast:…
        Regex("""(?i)\b(?:about|blob|data|javascript|mailto|tel|sms|view-source|intent|beast|content|file|moz-extension|resource|chrome|market):$END+""") to URL_MASK,
        // https://…, any other scheme://
        Regex("""(?i)\b[a-z][a-z0-9+.\-]{0,30}://$END*""") to URL_MASK,
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to EMAIL_MASK,
        Regex("""(?i)\bwww\d?\.$END+""") to URL_MASK,
        // Bare host names with a common TLD ("example.com/page"). The lookahead keeps package names like
        // java.io.IOException intact (a TLD followed by ".Something" is a package, not a host).
        Regex(
            """(?i)\b(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+""" +
                """(?:com|net|org|io|co|uk|de|fr|jp|ru|cn|br|nl|es|au|ca|info|biz|edu|gov|dev|app|xyz|eu|us|tv|ch|se|pl|onion)""" +
                """(?![a-z0-9\-]|\.[a-z0-9])(?:[:/?#]$END*)?"""
        ) to URL_MASK,
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""") to IP_MASK,
        // File paths can name downloads (including Private downloads): keep none of them.
        Regex("""(?:/storage/|/sdcard/|/mnt/|/data/user/\d+/|/data/data/)[^\s"'<>:]*""") to PATH_MASK,
    )

    /** A real stack frame ("\tat pkg.Class.method(File.kt:12)"): code locations only, so frames skip scrubbing. */
    private val FRAME = Regex("""^\s+at [A-Za-z0-9_$.<>/\-]+\((?:[A-Za-z0-9_$.\-]+(?::\d+)?|Native Method|Unknown Source)\)$""")
    private val TRAILER = Regex("""^\s+\.\.\. \d+ more$""")

    /** Replaces URL-like strings, e-mail and IP addresses and file paths in [text] (exception messages). */
    fun scrub(text: String): String = SCRUBBERS.fold(text) { s, (re, mask) -> re.replace(s, mask) }

    private fun isFrame(line: String) = FRAME.matches(line) || TRAILER.matches(line)

    /** Scrubs every line that isn't a plain stack frame (headers, "Caused by:" messages, multi-line messages). */
    fun scrubTrace(trace: String): String =
        trace.lineSequence().joinToString("\n") { if (isFrame(it)) it else scrub(it) }

    // ---------------------------------------------------------------- size caps

    /** Cuts each line to [maxLine] chars and the whole trace to [maxChars], noting how many lines were dropped. */
    fun truncateTrace(trace: String, maxChars: Int = MAX_TRACE_CHARS, maxLine: Int = MAX_LINE_CHARS): String {
        val lines = trace.trimEnd().lines().map { if (it.length > maxLine) it.take(maxLine) + " … (trimmed)" else it }
        if (lines.sumOf { it.length + 1 } - 1 <= maxChars) return lines.joinToString("\n")
        val out = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            val marker = "\t... (${lines.size - i} more lines trimmed)"
            // Room for this line plus a possible trim marker after it.
            val needed = line.length + 1 + (if (i == lines.lastIndex) 0 else marker.length + 1)
            if (out.length + needed > maxChars) {
                if (out.length + marker.length <= maxChars) out.append(marker) else if (out.isNotEmpty()) out.setLength(out.length - 1)
                return out.toString()
            }
            out.append(line).append('\n')
        }
        return out.toString().trimEnd('\n')
    }

    /** Scrubbed, capped stack trace of [t]; falls back to the class name if building the trace itself fails (OOM). */
    fun traceOf(t: Throwable): String {
        val raw = try { t.stackTraceToString() } catch (_: Throwable) { t.javaClass.name }
        return truncateTrace(scrubTrace(raw.take(MAX_RAW_CHARS)))
    }

    /** First line of a trace, for the report header and lists. */
    fun summary(trace: String): String = field(trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty())

    /** Single-line, capped header value. */
    fun field(v: String): String {
        val s = v.replace('\r', ' ').replace('\n', ' ').trim()
        return if (s.length > MAX_FIELD_CHARS) s.take(MAX_FIELD_CHARS - 1) + "…" else s
    }

    // ---------------------------------------------------------------- file format

    fun timeUtc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun fileName(r: CrashReport) = "crash-${r.time}-${r.kind.key}.txt"

    /** Text written to disk: "key: value" header lines, a blank line, then the trace. At most [maxBytes] UTF-8 bytes. */
    fun format(r: CrashReport, maxBytes: Int = MAX_FILE_BYTES): String {
        val head = buildString {
            append(HEADER).append('\n')
            fun kv(k: String, v: Any) { append(k).append(": ").append(field(v.toString())).append('\n') }
            kv("format", FORMAT)
            kv("time", r.time)
            kv("date", timeUtc(r.time))
            kv("kind", r.kind.key)
            kv("app", r.appVersion)
            kv("versionCode", r.versionCode)
            kv("android", r.android)
            kv("api", r.api)
            kv("device", r.device)
            kv("geckoview", r.gecko)
            kv("process", r.process)
            kv("thread", r.thread)
            kv("exception", r.exception)
            append('\n')
        }
        var trace = truncateTrace(r.trace)
        // Stack traces are ASCII in practice; this only bites for long non-ASCII messages.
        while (trace.isNotEmpty() && (head + trace).toByteArray(Charsets.UTF_8).size > maxBytes) {
            trace = truncateTrace(trace, maxChars = trace.length * 3 / 4)
        }
        return head + trace + "\n"
    }

    /** Reads [format] output back; null if it isn't a crash report. */
    fun parse(text: String): CrashReport? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return null
        val map = HashMap<String, String>()
        var i = 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            val sep = line.indexOf(": ")
            if (sep > 0) map[line.substring(0, sep)] = line.substring(sep + 2)
            i++
        }
        val time = map["time"]?.toLongOrNull() ?: return null
        return CrashReport(
            time = time,
            kind = CrashReport.Kind.from(map["kind"]),
            appVersion = map["app"].orEmpty(),
            versionCode = map["versionCode"]?.toLongOrNull() ?: 0,
            android = map["android"].orEmpty(),
            api = map["api"]?.toIntOrNull() ?: 0,
            device = map["device"].orEmpty(),
            gecko = map["geckoview"].orEmpty(),
            process = map["process"].orEmpty(),
            thread = map["thread"].orEmpty(),
            exception = map["exception"].orEmpty(),
            trace = lines.drop(i + 1).joinToString("\n").trimEnd(),
        )
    }
}
