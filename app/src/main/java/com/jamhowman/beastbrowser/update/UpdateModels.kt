package com.jamhowman.beastbrowser.update

/**
 * Pure helpers for the in-app updater (no Android dependencies, unit tested in UpdateLogicTest).
 */

/** A release asset as reported by the GitHub API. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    /** "sha256:<hex>" when GitHub provides it, else null. */
    val digest: String?,
)

/** The bits of a GitHub release we care about. */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/**
 * Minimal semantic version: MAJOR.MINOR.PATCH (missing parts = 0, extra numeric parts are kept),
 * optional "-prerelease" (sorts before the plain version), "+build" metadata ignored.
 * Accepts a leading "v"/"V" so release tags like "v2.4.1" parse.
 */
data class SemVer(val parts: List<Int>, val pre: String?) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        val n = maxOf(parts.size, other.parts.size, 3)
        for (i in 0 until n) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            pre == other.pre -> 0
            pre == null -> 1          // 2.4.0 > 2.4.0-beta
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    override fun toString() = parts.joinToString(".") + (pre?.let { "-$it" } ?: "")

    companion object {
        private val RE = Regex("""^[vV]?(\d+(?:\.\d+){0,3})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(raw: String?): SemVer? {
            val m = RE.matchEntire(raw?.trim() ?: return null) ?: return null
            val nums = m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return SemVer(nums, m.groupValues[2].ifEmpty { null })
        }

        /** SemVer 2.0 prerelease precedence: dot-separated identifiers, numeric < alphanumeric. */
        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.'); val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val xi = x[i].toIntOrNull(); val yi = y[i].toIntOrNull()
                val c = when {
                    xi != null && yi != null -> xi.compareTo(yi)
                    xi != null -> -1
                    yi != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}

object UpdateLogic {

    private val REPO_RE = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$""")

    /** True when [repo] looks like a real "owner/name" (not blank, not the OWNER/REPO placeholder). */
    fun isConfigured(repo: String?): Boolean {
        val r = repo?.trim().orEmpty()
        return r.isNotEmpty() && !r.equals("OWNER/REPO", ignoreCase = true) && REPO_RE.matches(r)
    }

    /** True if [tag] is a strictly newer version than [current]. Unparseable tag → false. */
    fun isNewer(tag: String, current: String): Boolean {
        val t = SemVer.parse(tag) ?: return false
        val c = SemVer.parse(current) ?: return true   // can't read our own version: let the user decide
        return t > c
    }

    /**
     * First asset ending in .apk, preferring one built for the device's primary ABI ([primaryAbi],
     * e.g. "arm64-v8a"), then one with "arm64" in the name, then the first .apk.
     */
    fun pickApk(assets: List<ReleaseAsset>, primaryAbi: String? = null): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.size <= 1) return apks.firstOrNull()
        val aliases = when (primaryAbi) {
            "arm64-v8a" -> listOf("arm64", "aarch64")
            "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "arm32")
            "x86_64" -> listOf("x86_64", "x64")
            else -> emptyList()
        }
        fun has(a: ReleaseAsset, keys: List<String>) = keys.any { a.name.contains(it, ignoreCase = true) }
        return apks.firstOrNull { has(it, aliases) }
            ?: apks.firstOrNull { has(it, listOf("arm64")) }
            ?: apks.first()
    }

    /** Release notes for the dialog: trimmed, capped so a huge changelog doesn't blow up the dialog. */
    fun notesForDialog(body: String, max: Int = 2000): String {
        val t = body.replace("\r\n", "\n").trim()
        if (t.isEmpty()) return "No release notes."
        return if (t.length <= max) t else t.take(max).trimEnd() + "…"
    }
}
