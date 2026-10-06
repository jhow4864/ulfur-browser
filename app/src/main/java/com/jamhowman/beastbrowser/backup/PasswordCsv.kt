package com.jamhowman.beastbrowser.backup

import android.net.Uri
import com.jamhowman.beastbrowser.passwords.SavedLogin

/**
 * Parses password CSV exports:
 * - Chrome / Edge / Brave: `name,url,username,password[,note]`
 * - Firefox: `"url","username","password","httpRealm","formActionOrigin","guid","timeCreated","timeLastUsed","timePasswordChanged"`
 * - Generic: any header with url/origin/login_uri, username/login_username, password/login_password columns.
 * RFC 4180 quoting (commas, quotes and newlines inside quoted fields) is supported.
 */
object PasswordCsv {
    class CsvException(message: String) : Exception(message)

    fun parse(text: String): List<SavedLogin> {
        val rows = rows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) throw CsvException("empty file")
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val url = col("url", "origin", "login_uri", "website", "hostname")
        val user = col("username", "login_username", "user", "login")
        val pass = col("password", "login_password")
        if (url < 0 || pass < 0) throw CsvException("not a password export (needs url and password columns)")
        val realm = col("httprealm")
        val action = col("formactionorigin")
        val created = col("timecreated")
        val changed = col("timepasswordchanged")
        return rows.drop(1).mapNotNull { r ->
            val rawUrl = r.getOrNull(url)?.trim().orEmpty()
            val password = r.getOrNull(pass).orEmpty()
            val origin = originOf(rawUrl) ?: return@mapNotNull null
            if (password.isEmpty()) return@mapNotNull null
            SavedLogin(
                origin = origin,
                formActionOrigin = r.getOrNull(action)?.takeIf { it.isNotBlank() },
                httpRealm = r.getOrNull(realm)?.takeIf { it.isNotBlank() },
                username = if (user >= 0) r.getOrNull(user).orEmpty() else "",
                password = password,
                createdAt = r.getOrNull(created)?.toLongOrNull() ?: 0L,
                updatedAt = r.getOrNull(changed)?.toLongOrNull() ?: 0L,
            )
        }
    }

    /** `scheme://host[:port]` of an http(s) URL, or null (android://, empty, junk). */
    fun originOf(url: String): String? {
        if (url.isBlank()) return null
        val u = runCatching { Uri.parse(if ("://" in url) url else "https://$url") }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return "$scheme://$host" + if (u.port > 0) ":${u.port}" else ""
    }

    private fun rows(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() { endField(); if (row.any { it.isNotEmpty() }) out.add(row); row = ArrayList() }
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> endField()
                '\r' -> {}
                '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return out
    }
}
