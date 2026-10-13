package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.browser.SitePermissions.Kind
import com.jamhowman.beastbrowser.browser.SitePermissions.State

/**
 * 2.12.1: the per-site camera / mic decision, pure so it is unit-tested.
 * Order: stored site decision (Block wins) → prompt when nothing is stored ("Ask") → only once the site is allowed,
 * the Android runtime permission (requested at that moment, never at launch). Denial anywhere = the site is denied.
 * Private / Ghost decisions go to [SessionPermissions] (memory), never to [SiteMediaStore].
 */
object PermissionFlow {
    enum class Decision { ALLOW_ALWAYS, ALLOW_ONCE, BLOCK, DISMISSED }

    enum class Stored { ALLOW, BLOCK, PROMPT }

    /** The only Android permissions 2.12.1 adds (Apk Tester gate: no background location, no MODIFY_AUDIO_SETTINGS). */
    val ADDED_ANDROID_PERMISSIONS = listOf(
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION")

    /** Any Block → BLOCK; all Allow → ALLOW; otherwise the user is asked. */
    fun combine(states: List<State?>): Stored = when {
        states.any { it == State.BLOCK } -> Stored.BLOCK
        states.isNotEmpty() && states.all { it == State.ALLOW } -> Stored.ALLOW
        else -> Stored.PROMPT
    }

    /** What a prompt answer leaves stored (null = nothing; "Only this time" and dismiss keep it at Ask). */
    fun remembered(d: Decision): State? = when (d) {
        Decision.ALLOW_ALWAYS -> State.ALLOW; Decision.BLOCK -> State.BLOCK; else -> null
    }

    fun allows(d: Decision) = d == Decision.ALLOW_ALWAYS || d == Decision.ALLOW_ONCE

    fun androidPermissions(kinds: Collection<Kind>): List<String> = kinds.flatMap {
        when (it) {
            Kind.CAMERA -> listOf("android.permission.CAMERA")
            Kind.MIC -> listOf("android.permission.RECORD_AUDIO")
            Kind.LOCATION -> listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
            else -> emptyList()
        }
    }.distinct()

    /** Location: coarse alone is enough; camera / mic need their own permission. */
    fun androidSatisfied(requested: List<String>, granted: Set<String>): Boolean {
        val loc = requested.filter { it.endsWith("_LOCATION") }
        val other = requested - loc.toSet()
        return other.all { it in granted } && (loc.isEmpty() || loc.any { it in granted })
    }

    /**
     * Which of [perms] to show the Android dialog for: not held, and not already refused this session (refusals are
     * remembered so a site retrying can't loop the system dialog; the site is just denied).
     */
    fun toRequest(perms: List<String>, held: Set<String>, refusedThisSession: Set<String>): List<String> =
        perms.filter { it !in held && it !in refusedThisSession }

    interface Env {
        fun stored(origin: String, kind: Kind, private: Boolean): State?
        fun remember(origin: String, kind: Kind, state: State, private: Boolean)
        fun prompt(origin: String, kinds: List<Kind>, allowOnce: Boolean, done: (Decision) -> Unit)
        /** Requests (if needed) the Android permissions; [done] with the set actually granted. */
        fun ensureAndroid(perms: List<String>, done: (Set<String>) -> Unit)
    }

    /** Media (camera / mic) request: [done] true = grant the stream. */
    fun media(env: Env, origin: String?, kinds: List<Kind>, private: Boolean, done: (Boolean) -> Unit) {
        if (origin == null || kinds.isEmpty()) return done(false)
        fun android() {
            val perms = androidPermissions(kinds)
            env.ensureAndroid(perms) { g -> done(androidSatisfied(perms, g)) }
        }
        when (combine(kinds.map { env.stored(origin, it, private) })) {
            Stored.BLOCK -> done(false)
            Stored.ALLOW -> android()
            Stored.PROMPT -> env.prompt(origin, kinds, true) { d ->
                remembered(d)?.let { s -> kinds.forEach { env.remember(origin, it, s, private) } }
                if (allows(d)) android() else done(false)
            }
        }
    }

    /** Capture must stop when the tab is closed, hidden (switched away) or the app goes to the background. */
    fun shouldStopCapture(capturing: Boolean, tabVisible: Boolean, appForeground: Boolean): Boolean =
        capturing && !(tabVisible && appForeground)
}
