package com.jamhowman.beastbrowser.backup

import com.jamhowman.beastbrowser.passwords.SavedLogin
import java.util.UUID

/**
 * Merge rules for importing a backup or CSV into existing data. Nothing existing is ever deleted.
 * - Logins match on normalised origin + username: identical password → skipped; different password → the
 *   existing entry gets the imported password (guid / createdAt / timesUsed kept); otherwise added.
 * - Bookmarks, speed-dial tiles and reading-list articles are added only if their URL isn't present yet;
 *   bookmark folders are kept when known, otherwise the bookmark lands in "All".
 */
object BackupMerge {

    data class LoginResult(val merged: List<SavedLogin>, val added: Int, val updated: Int, val skipped: Int)

    fun mergeLogins(
        existing: List<SavedLogin>,
        incoming: List<SavedLogin>,
        normalizeOrigin: (String) -> String,
        now: Long = System.currentTimeMillis(),
    ): LoginResult {
        val merged = existing.toMutableList()
        val guids = existing.mapTo(HashSet()) { it.guid }
        var added = 0; var updated = 0; var skipped = 0
        for (inc in incoming) {
            if (inc.origin.isBlank() || inc.password.isEmpty()) { skipped++; continue }
            val key = normalizeOrigin(inc.origin)
            val idx = merged.indexOfFirst { normalizeOrigin(it.origin) == key && it.username == inc.username }
            if (idx >= 0) {
                val old = merged[idx]
                if (old.password == inc.password) { skipped++; continue }
                merged[idx] = old.copy(
                    password = inc.password,
                    formActionOrigin = old.formActionOrigin ?: inc.formActionOrigin,
                    httpRealm = old.httpRealm ?: inc.httpRealm,
                    updatedAt = now,
                )
                updated++
            } else {
                var guid = inc.guid
                while (!guids.add(guid)) guid = UUID.randomUUID().toString()
                merged += inc.copy(
                    guid = guid,
                    createdAt = inc.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = inc.updatedAt.takeIf { it > 0 } ?: now,
                )
                added++
            }
        }
        return LoginResult(merged, added, updated, skipped)
    }

    /** Bookmarks to insert: URL not already bookmarked (and first occurrence only); unknown folders → null. */
    fun newBookmarks(existingUrls: Set<String>, incoming: List<BackupBookmark>, knownFolder: (String) -> Boolean): List<BackupBookmark> {
        val seen = HashSet(existingUrls)
        return incoming.filter { it.url.isNotBlank() && seen.add(it.url) }
            .map { if (it.folder != null && !knownFolder(it.folder)) it.copy(folder = null) else it }
    }

    /** Existing tiles first, then imported tiles whose URL isn't on the speed dial yet. */
    fun mergeSpeedDial(existing: List<BackupTile>, incoming: List<BackupTile>): Pair<List<BackupTile>, Int> {
        val seen = existing.mapTo(HashSet()) { it.url }
        val add = incoming.filter { it.url.isNotBlank() && seen.add(it.url) }
        return (existing + add) to add.size
    }

    fun newArticles(existingUrls: Set<String>, incoming: List<BackupArticle>): List<BackupArticle> {
        val seen = HashSet(existingUrls)
        return incoming.filter { it.url.isNotBlank() && seen.add(it.url) }
    }
}
