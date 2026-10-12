package com.jamhowman.beastbrowser.autofill

import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.util.WeakHashMap

/** Latest login-field context per tab, reported by the helper content script on focus (2.10 Ulfur #2). */
object FieldGuard {
    private val latest = WeakHashMap<GeckoSession, FillPolicy.FieldContext>()

    @Synchronized fun report(session: GeckoSession, o: JSONObject, nowMs: Long) {
        latest[session] = FillPolicy.FieldContext(
            topFrame = o.optBoolean("top", false),
            sameOriginFrame = o.optBoolean("sameOrigin", false),
            visible = o.optBoolean("visible", false),
            atMs = nowMs,
        )
    }

    @Synchronized fun get(session: GeckoSession): FillPolicy.FieldContext? = latest[session]
    @Synchronized fun clear(session: GeckoSession) { latest.remove(session) }
}
