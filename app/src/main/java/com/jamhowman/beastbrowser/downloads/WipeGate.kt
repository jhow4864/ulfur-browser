package com.jamhowman.beastbrowser.downloads

/**
 * 2.10: "Wipe vault and key" only runs after a fresh fingerprint/face/PIN check, even if the vault screen is already
 * unlocked. [authenticate] must call back exactly once; only `true` runs [wipe], at most once. Pure, unit-tested.
 */
class WipeGate(private val authenticate: (onResult: (Boolean) -> Unit) -> Unit, private val wipe: () -> Unit) {
    private var done = false
    fun request() {
        authenticate { ok -> if (ok && !done) { done = true; wipe() } }
    }
}
