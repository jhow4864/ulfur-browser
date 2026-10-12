package com.jamhowman.beastbrowser.crash

import java.util.concurrent.ConcurrentHashMap

/**
 * 2.9: strings from private sessions (titles, URLs, hosts) that must never reach a crash report. MainActivity
 * registers them while private tabs are open (memory only, never written anywhere); [redact] masks every one of them,
 * on top of [CrashFormat.scrub]'s generic URL / e-mail / IP masking, before a report is saved.
 */
object PrivateRedactor {
    const val MASK = "<private>"
    private const val MIN_LEN = 4
    private val secrets = ConcurrentHashMap.newKeySet<String>()

    fun register(vararg values: String?) {
        values.forEach { v ->
            val t = v?.trim().orEmpty()
            if (t.length < MIN_LEN) return@forEach
            secrets += t
            hostOf(t)?.let { h -> if (h.length >= MIN_LEN) secrets += h }
        }
    }

    fun clear() = secrets.clear()

    fun redact(text: String): String {
        if (text.isEmpty() || secrets.isEmpty()) return text
        // Longest first, so a URL is masked whole before its host.
        return secrets.sortedByDescending { it.length }.fold(text) { s, secret -> s.replace(secret, MASK, ignoreCase = true) }
    }

    private fun hostOf(url: String): String? =
        Regex("""^[a-z][a-z0-9+.\-]*://([^/?#:@]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
}
