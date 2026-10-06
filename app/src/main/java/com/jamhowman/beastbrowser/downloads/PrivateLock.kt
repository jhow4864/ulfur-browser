package com.jamhowman.beastbrowser.downloads

/**
 * Session lock for the private download vault. Unlocks after biometric/screen-lock
 * success and re-locks when the app goes to the background.
 */
object PrivateLock {
    @Volatile private var unlockedUntil = 0L
    private const val GRACE_MS = 30_000L

    fun unlock() {
        unlockedUntil = Long.MAX_VALUE
    }

    fun lock() {
        unlockedUntil = 0L
    }

    /** Soft lock: still unlocked for [GRACE_MS] so rotation / brief switches don't re-prompt. */
    fun softLock() {
        if (unlockedUntil == Long.MAX_VALUE) unlockedUntil = System.currentTimeMillis() + GRACE_MS
    }

    fun isUnlocked(): Boolean {
        val until = unlockedUntil
        if (until == Long.MAX_VALUE) return true
        if (until == 0L) return false
        if (System.currentTimeMillis() <= until) return true
        unlockedUntil = 0L
        return false
    }
}
