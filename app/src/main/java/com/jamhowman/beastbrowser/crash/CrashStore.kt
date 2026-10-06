package com.jamhowman.beastbrowser.crash

import java.io.File

/**
 * 2.5.1: crash report files in app-private storage (filesDir/crash-reports/, see [CrashReporter.store]).
 * Keeps the newest [keep] reports, each at most [maxBytes]. Plain java.io so it runs in JVM unit tests.
 * Covered by data_extraction_rules.xml (domain="file"), so reports never leave the device in a backup.
 */
class CrashStore(val dir: File, private val keep: Int = KEEP, private val maxBytes: Int = CrashFormat.MAX_FILE_BYTES) {

    class Entry(val file: File, val report: CrashReport)

    /** Writes [r] (via a .tmp file, so a half-written report is never listed) and prunes old ones. */
    fun save(r: CrashReport): File? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var f = File(dir, CrashFormat.fileName(r))
        var n = 1
        while (f.exists()) f = File(dir, CrashFormat.fileName(r).removeSuffix(".txt") + "-${n++}.txt")
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(CrashFormat.format(r, maxBytes), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { tmp.delete(); return null }
        prune()
        return f
    }

    /** Report files, newest first. */
    fun files(): List<File> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && NAME.matches(it.name) }
        .sortedWith(compareByDescending<File> { timeOf(it) }.thenByDescending { it.name })

    /** Readable reports, newest first. Unreadable or oversized files are skipped (and removed by [prune]). */
    fun reports(): List<Entry> = files().mapNotNull { f -> read(f)?.let { Entry(f, it) } }

    fun read(f: File): CrashReport? {
        if (!f.isFile || f.length() > maxBytes) return null
        return runCatching { CrashFormat.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** Raw text of a report file, exactly as stored (what the user sees in "View"). */
    fun text(f: File): String? = if (owns(f) && f.isFile && f.length() <= maxBytes) runCatching { f.readText() }.getOrNull() else null

    fun delete(f: File): Boolean = owns(f) && f.delete()

    fun deleteAll() { (dir.listFiles() ?: emptyArray()).forEach { it.delete() } }

    fun count(): Int = files().size

    /** Keeps the newest [keep] reports; drops oversized files and leftover .tmp files from an interrupted save. */
    fun prune() {
        val now = System.currentTimeMillis()
        (dir.listFiles() ?: return).filter { f ->
            // Another process (Gecko content processes run BeastApp too) may be mid-save: spare fresh .tmp files.
            f.isFile && (if (f.name.endsWith(".tmp")) now - f.lastModified() > STALE_TMP_MS else !NAME.matches(f.name))
        }.forEach { it.delete() }
        files().filter { it.length() > maxBytes }.forEach { it.delete() }
        files().drop(keep).forEach { it.delete() }
    }

    /** Only files directly inside [dir]. */
    private fun owns(f: File) = f.parentFile?.canonicalPath == dir.canonicalPath && NAME.matches(f.name)

    companion object {
        const val KEEP = 5
        private const val STALE_TMP_MS = 60_000L
        private val NAME = Regex("""crash-(\d{1,19})-[a-z]+(?:-\d+)?\.txt""")
        fun timeOf(f: File): Long = NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
