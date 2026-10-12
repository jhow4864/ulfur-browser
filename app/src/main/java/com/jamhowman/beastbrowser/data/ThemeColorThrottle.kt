package com.jamhowman.beastbrowser.data

/**
 * 2.10 Ulfur #5 follow-up: a page can change its theme-color as often as it likes (some animate it). This lets at most
 * one change through every [intervalMs]; changes in between are coalesced and the latest (trailing) value is applied
 * when the window ends. The first change after a quiet period applies at once.
 * Pure: [now] and [schedule] are injected so it is unit-tested without a Looper (MainActivity passes a Handler).
 */
class ThemeColorThrottle(
    private val intervalMs: Long = INTERVAL_MS,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val apply: (String?) -> Unit,
) {
    private var lastApplied = Long.MIN_VALUE / 2
    private var scheduled = false
    private var pending: String? = null

    fun submit(value: String?) {
        val t = now()
        if (!scheduled && t - lastApplied >= intervalMs) {
            lastApplied = t
            apply(value)
            return
        }
        pending = value
        if (!scheduled) {
            scheduled = true
            schedule(lastApplied + intervalMs - t) {
                scheduled = false
                lastApplied = now()
                apply(pending)
            }
        }
    }

    companion object { const val INTERVAL_MS = 250L }
}
