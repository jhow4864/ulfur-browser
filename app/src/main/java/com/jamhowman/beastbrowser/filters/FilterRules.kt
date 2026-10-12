package com.jamhowman.beastbrowser.filters

import java.net.IDN
import java.net.URI

/**
 * 2.10 Ulfur #3: pure rules for filter lists and element-picker rules (unit-tested in FilterListsTest).
 *
 * Lists are fetched only over https, size-capped, and sanitised before uBO sees them: control characters, giant lines,
 * `!#include` (would fetch more), and rules uBO only allows from trusted sources (trusted-* scriptlets, `$replace=`,
 * `$urlskip=`, `$uritransform=`, AdGuard `#%#` JS) are dropped, because the managed block lives in uBO's own
 * "My filters", which uBO trusts. Nothing here throws on any input.
 *
 * Picker rules are always `host##selector` for exactly one valid hostname, so they can never become generic (`##sel`)
 * or spill to other sites; [appliesTo] mirrors uBO's hostname scoping (the host and its subdomains).
 */
object FilterRules {
    const val MAX_LIST_BYTES = 10L * 1024 * 1024
    const val MAX_LINE_CHARS = 4096
    const val MAX_RULES_PER_LIST = 300_000
    const val MAX_LISTS = 20
    const val MAX_SELECTOR_CHARS = 512

    /** null when [url] is allowed as a list address, else the reason. */
    fun checkListUrl(url: String?): String? {
        val s = url?.trim().orEmpty()
        if (s.isEmpty()) return "Enter a list address"
        if (s.length > 2048) return "Address is too long"
        val u = runCatching { URI(s) }.getOrNull() ?: return "Not a valid address"
        if (!u.scheme.equals("https", ignoreCase = true)) return "Lists can only be fetched over https"
        if (u.rawUserInfo != null) return "Addresses with a user name aren't allowed"
        val host = u.host ?: return "Not a valid address"
        if (host.equals("localhost", true) || host.all { it.isDigit() || it == '.' } || host.startsWith("[")) return "Lists must come from a public site"
        return null
    }

    data class Sanitized(val rules: List<String>, val dropped: Int, val truncated: Boolean)

    private val untrusted = listOf("+js(trusted-", "+js(trusted_", "\$replace=", ",replace=", "\$urlskip=", ",urlskip=",
        "\$uritransform=", ",uritransform=", "#%#", "#@%#", "#\$?#", "#@\$?#")

    fun sanitize(text: String?): Sanitized {
        if (text == null) return Sanitized(emptyList(), 0, false)
        val out = ArrayList<String>()
        var dropped = 0
        var truncated = false
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") && !line.startsWith("!#") || line.startsWith("[")) continue
            val bad = line.length > MAX_LINE_CHARS ||
                line.any { it < ' ' && it != '\t' || it == '\u007F' || it == '\u2028' || it == '\u2029' } ||
                line.startsWith("!#include") ||
                untrusted.any { line.contains(it, ignoreCase = true) }
            if (bad) { dropped++; continue }
            if (out.size >= MAX_RULES_PER_LIST) { truncated = true; break }
            out += line
        }
        return Sanitized(out, dropped, truncated)
    }

    // ------------------------------------------------------------------ element picker

    /** ASCII-lowercased hostname, or null if it isn't a single plain hostname (no wildcards, lists, ports, IPs ok). */
    fun normalizeHost(host: String?): String? {
        val h0 = host?.trim()?.trimEnd('.') ?: return null
        if (h0.isEmpty() || h0.length > 253) return null
        val h = runCatching { IDN.toASCII(h0, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
        if (!Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+""").matches(h)) return null
        return h
    }

    /** A CSS selector safe to put after `##`, or null. */
    fun cleanSelector(sel: String?): String? {
        val s = sel?.trim() ?: return null
        if (s.isEmpty() || s.length > MAX_SELECTOR_CHARS) return null
        if (s.any { it < ' ' || it == '\u007F' || it == '\u2028' || it == '\u2029' }) return null
        if (s.contains('{') || s.contains('}') || s.contains("##") || s.contains("#@#") || s.contains("#?#") ||
            s.contains("#$#") || s.contains("#%#") || s.startsWith("+js") || s.startsWith("^")) return null
        // uBO procedural/action operators can change or remove page content beyond hiding: not from the picker.
        if (Regex(""":(style|remove|remove-attr|remove-class|watch-attr|matches-path|xpath)\(""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
        if (s.startsWith(",") || s.endsWith(",") || s.startsWith(">")) return null
        return s
    }

    data class PickerRule(val host: String, val selector: String, val createdAt: Long = 0) {
        val filter: String get() = "$host##$selector"
    }

    /** The rule for [selector] made on [pageHost], or null if either is unsafe. */
    fun pickerRule(pageHost: String?, selector: String?, now: Long = 0): PickerRule? {
        val h = normalizeHost(pageHost) ?: return null
        val s = cleanSelector(selector) ?: return null
        return PickerRule(h, s, now)
    }

    /** Same scoping as uBO's `host##sel`: [pageHost] is the rule's host or one of its subdomains. */
    fun appliesTo(rule: PickerRule, pageHost: String?): Boolean {
        val p = normalizeHost(pageHost) ?: return false
        return p == rule.host || p.endsWith("." + rule.host)
    }
}
