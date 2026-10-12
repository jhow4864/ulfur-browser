package com.jamhowman.beastbrowser.autofill

import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.WeakHashMap

/**
 * Latest login-field context per tab, reported by the helper content script on focus (2.10 Ulfur #2).
 * Bound to the page: each report remembers the tab URL it was made on, is cleared on every navigation
 * ([clear] from HelperSessions.onLocationChange), and [get] only returns it while the tab is still on that URL.
 */
object FieldGuard {
    private class Entry(val pageUrl: String, val ctx: FillPolicy.FieldContext)
    private val latest = WeakHashMap<Any, Entry>()

    private fun key(url: String?) = url?.substringBefore('#')

    @Synchronized fun report(session: GeckoSession, o: JSONObject, nowMs: Long, pageUrl: String?) = reportFor(session, o, nowMs, pageUrl)

    @Synchronized internal fun reportFor(session: Any, o: JSONObject, nowMs: Long, pageUrl: String?) {
        val url = key(pageUrl) ?: return
        latest[session] = Entry(url, FillPolicy.FieldContext(
            topFrame = o.optBoolean("top", false),
            sameOriginFrame = o.optBoolean("sameOrigin", false),
            visible = o.optBoolean("visible", false),
            atMs = nowMs,
        ))
    }

    @Synchronized fun get(session: Any, currentUrl: String?): FillPolicy.FieldContext? {
        val e = latest[session] ?: return null
        val u = key(currentUrl) ?: return null
        return if (e.pageUrl == u) e.ctx else null
    }

    @Synchronized fun clear(session: Any) { latest.remove(session) }
}
