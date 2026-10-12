package com.jamhowman.beastbrowser.crash

import java.io.File
import java.io.FileOutputStream

/**
 * 2.9 safe start: counts launches in a row that never became stable.
 *
 * [onLaunch] runs first thing in Application.onCreate (before Prefs, before the crash reporter), so a crash anywhere
 * later in onCreate, MainActivity or the first seconds of browsing still counts. It bumps the counter on disk (synced)
 * *before* anything else can fail; [markStable] sets it back to 0 once the launch is stable. A launch that dies
 * before [markStable] therefore leaves the counter raised, and the next launch sees it.
 *
 * Counting never depends on the opt-in crash reporter (no stack, no report: just a number in filesDir).
 * Plain java.io, so it runs in JVM unit tests.
 */
class LaunchGuard(private val file: File, val threshold: Int = THRESHOLD) {

    data class State(val unstable: Int = 0, val afterRestore: Boolean = false)

    /** What [onLaunch] found: [previousUnstable] launches in a row before this one never became stable. */
    data class Launch(val previousUnstable: Int, val safeStart: Boolean, val crashedAfterRestore: Boolean)

    fun read(): State = try {
        val parts = file.readText().trim().split(' ')
        if (parts.size == 3 && parts[0] == "v1") State(parts[1].toInt().coerceIn(0, MAX), parts[2] == "1") else State()
    } catch (_: Throwable) { State() }

    /** Call once per main-process start, as early as possible. */
    fun onLaunch(): Launch {
        val s = read()
        write(State((s.unstable + 1).coerceAtMost(MAX), s.afterRestore))
        val safe = s.unstable >= threshold
        return Launch(s.unstable, safe, safe && s.afterRestore)
    }

    /** This launch is stable (first page load, a while in the foreground, or the user left normally): reset. */
    fun markStable() = write(State())

    /**
     * "Restore everything" (or a partial restore) from safe start: if the process now dies before it's stable, the
     * very next launch goes straight back to safe start and says it crashed again after restoring.
     */
    fun markRestoring() = write(State(threshold, afterRestore = true))

    private fun write(s: State) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write("v1 ${s.unstable} ${if (s.afterRestore) 1 else 0}\n".toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Throwable) { }
    }

    companion object {
        const val THRESHOLD = 2
        const val FILE = "launch-guard"
        /** A launch counts as stable after this long in the foreground, if no page finished loading first. */
        const val STABLE_MS = 12_000L
        private const val MAX = 99
    }
}
