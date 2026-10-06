package com.jamhowman.beastbrowser.backup

import com.jamhowman.beastbrowser.data.CustomFolder
import com.jamhowman.beastbrowser.passwords.SavedLogin
import java.util.UUID

/**
 * Merge rules for importing a backup or CSV into existing data. Nothing existing is ever deleted.
 * - Logins match on normalised origin + username: identical password → skipped; different password → the
 *   existing entry gets the imported password (guid / createdAt / timesUsed kept); otherwise added.
 * - Bookmarks, speed-dial tiles and reading-list articles are added only if their URL isn't present yet.
 * - Bookmark folders: built-in folders map to themselves; a backed-up custom folder maps to the device folder with
 *   the same id, else the one with the same name under the same parent, else it is created (nesting kept).
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

    /**
     * Bookmarks to insert: URL not already bookmarked (and first occurrence only). Each folder id is passed through
     * [resolveFolder] (backup folder id → device folder id; null = no folder).
     */
    fun newBookmarks(existingUrls: Set<String>, incoming: List<BackupBookmark>, resolveFolder: (String) -> String?): List<BackupBookmark> {
        val seen = HashSet(existingUrls)
        return incoming.filter { it.url.isNotBlank() && seen.add(it.url) }
            .map { b -> b.folder?.let { f -> resolveFolder(f).let { if (it == f) b else b.copy(folder = it) } } ?: b }
    }

    /** [create]: custom folders to add on the device (parents first). [map]: backup folder id → device folder id. */
    data class FolderPlan(val create: List<CustomFolder>, val map: Map<String, String>) {
        fun resolve(backupId: String): String? = map[backupId]
    }

    /**
     * Works out which folders the imported [bookmarks] need. [backupFolders] are the custom folders listed in the
     * backup (may be empty: 2.4.0 backups don't list any); a bookmark folder id that is neither built in nor listed
     * becomes a top-level folder named after the id. Only folders that are actually used (directly or as an
     * ancestor) are created. [newId] makes an id when a backup id is unusable (blank / built-in / already taken).
     */
    fun planFolders(
        existing: List<CustomFolder>,
        backupFolders: List<CustomFolder>,
        bookmarks: List<BackupBookmark>,
        isBuiltIn: (String) -> Boolean,
        newId: () -> String,
    ): FolderPlan {
        val byBackupId = backupFolders.associateBy { it.id }
        val existingById = existing.associateBy { it.id }
        val all = existing.toMutableList()
        val usedIds = existing.mapTo(HashSet()) { it.id }
        val create = ArrayList<CustomFolder>()
        val map = HashMap<String, String>()
        val resolving = HashSet<String>()

        fun key(name: String) = name.trim().lowercase()

        fun resolve(id: String, depth: Int): String? {
            if (id.isBlank()) return null
            if (isBuiltIn(id)) { map[id] = id; return id }
            map[id]?.let { return it }
            if (depth > MAX_FOLDER_DEPTH || !resolving.add(id)) return null // cycle / absurd depth → attach higher up
            val src = byBackupId[id]
            val name = src?.name?.trim()?.ifEmpty { null } ?: id
            val parent = src?.parentId?.let { resolve(it, depth + 1) }
            val target = existingById[id]?.id
                ?: all.firstOrNull { it.parentId == parent && key(it.name) == key(name) }?.id
                ?: run {
                    var newIdValue = id
                    while (newIdValue.isBlank() || isBuiltIn(newIdValue) || newIdValue in usedIds) newIdValue = newId()
                    CustomFolder(newIdValue, name, parent).also { usedIds += it.id; all += it; create += it }.id
                }
            resolving -= id
            map[id] = target
            return target
        }

        bookmarks.mapNotNull { it.folder }.distinct().forEach { resolve(it, 0) }
        return FolderPlan(create, map)
    }

    private const val MAX_FOLDER_DEPTH = 16

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
