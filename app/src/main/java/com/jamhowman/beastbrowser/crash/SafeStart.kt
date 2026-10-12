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

    /** Application.onCreate, main process only, before anything else. */
    fun begin(context: Context) = begin(LaunchGuard(File(context.filesDir, LaunchGuard.FILE)))

    fun begin(g: LaunchGuard): LaunchGuard.Launch {
        guard = g
        val l = g.onLaunch()
        launch = l
        tabsPaused = l.safeStart; extensionsPaused = l.safeStart; themePaused = l.safeStart
        awaitingChoice = l.safeStart
        return l
    }

    fun markStable() { guard?.markStable() }

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
    fun reset() { launch = null; guard = null; tabsPaused = false; extensionsPaused = false; themePaused = false; awaitingChoice = false }
}
