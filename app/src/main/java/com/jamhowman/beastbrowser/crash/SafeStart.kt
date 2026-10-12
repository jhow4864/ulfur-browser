package com.jamhowman.beastbrowser.crash

import android.content.Context
import java.io.File

/**
 * 2.9 safe start (design-drop-2.9.md §2). After [LaunchGuard.THRESHOLD] launch crashes in a row, this run opens with
 * saved tabs, extensions (uBlock Origin) and an imported theme pack paused. Nothing is deleted: the saved session
 * isn't read or overwritten while [tabsPaused], uBO is only disabled for the session, and the pack stays installed.
 */
object SafeStart {
    @Volatile var launch: LaunchGuard.Launch? = null
        private set
    @Volatile private var guard: LaunchGuard? = null

    @Volatile var tabsPaused = false
    @Volatile var extensionsPaused = false
    @Volatile var themePaused = false
    /** The user hasn't picked Restore / Continue yet (the amber bar can't be dismissed). */
    @Volatile var awaitingChoice = false

    val active: Boolean get() = tabsPaused || extensionsPaused || themePaused

    @Volatile private var counted = false

    /**
     * Application.onCreate, main process only, before anything else (2.9.1). Only user-visible launches count:
     * - [foreground] (the process was started for an activity: importance ≤ VISIBLE): count right now, so a crash
     *   later in Application.onCreate still counts;
     * - otherwise (widget update, broadcast, job, service): count nothing yet; [onFirstActivity] counts if and when an
     *   activity of ours is created in this process. A background-only process never touches the counter.
     */
    fun begin(context: Context, foreground: Boolean) {
        guard = LaunchGuard(File(context.filesDir, LaunchGuard.FILE))
        counted = false
        if (foreground) count()
    }

    /** First activity created in this process (ActivityLifecycleCallbacks). No-op once counted. */
    fun onFirstActivity() { if (!counted) count() }

    private fun count() {
        val g = guard ?: return
        counted = true
        begin(g)
    }

    /** Whether this process has counted a launch (tests and the stable timer). */
    val launchCounted: Boolean get() = counted

    /** Tests: same as [begin] (context) with an explicit guard. */
    fun beginDeferred(g: LaunchGuard, foreground: Boolean) {
        guard = g; counted = false
        if (foreground) count()
    }

    fun begin(g: LaunchGuard): LaunchGuard.Launch {
        guard = g
        counted = true
        val l = g.onLaunch()
        launch = l
        tabsPaused = l.safeStart; extensionsPaused = l.safeStart; themePaused = l.safeStart
        awaitingChoice = l.safeStart
        return l
    }

    /** Only a counted launch can be marked; a background process has nothing armed. */
    fun markStable() { if (counted) guard?.markStable() }

    /** Turns the chosen items back on; the caller re-applies them (tabs, uBO, theme). */
    fun restore(tabs: Boolean = true, extensions: Boolean = true, theme: Boolean = true) {
        if (tabs) tabsPaused = false
        if (extensions) extensionsPaused = false
        if (theme) themePaused = false
        awaitingChoice = false
        guard?.markRestoring()
    }

    fun continueSafe() { awaitingChoice = false }

    /** Tests only. */
    fun reset() { counted = false; launch = null; guard = null; tabsPaused = false; extensionsPaused = false; themePaused = false; awaitingChoice = false }
}
