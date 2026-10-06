package com.jamhowman.beastbrowser.passwords

/**
 * Password changes made while the vault was open that never reached the encrypted file, because the per-use
 * Keystore key needs a fingerprint for every write and that prompt was cancelled or never shown.
 *
 * When the vault locks with changes pending, only the changed logins are kept (in memory) instead of being thrown
 * away; the next unlock replays them onto the decrypted vault so the next confirmed write saves them.
 * [upserts] are added or changed logins by guid; [deletes] are guids removed since the last write.
 */
data class UnsavedChanges(val upserts: List<SavedLogin>, val deletes: Set<String>) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()

    /** Applies the changes to [vault]: deleted guids removed, upserts replace the same guid or are appended. */
    fun applyTo(vault: List<SavedLogin>): List<SavedLogin> {
        val byGuid = upserts.associateBy { it.guid }
        val kept = vault.filterNot { it.guid in deletes }.map { byGuid[it.guid] ?: it }
        val present = kept.mapTo(HashSet()) { it.guid }
        return kept + upserts.filterNot { it.guid in present }
    }

    /** Folds newer changes on top of these (used if the vault locks again before the changes are written). */
    fun then(next: UnsavedChanges): UnsavedChanges {
        val merged = LinkedHashMap<String, SavedLogin>()
        upserts.filterNot { it.guid in next.deletes }.forEach { merged[it.guid] = it }
        next.upserts.forEach { merged[it.guid] = it }
        return UnsavedChanges(merged.values.toList(), (deletes - next.upserts.map { it.guid }.toSet()) + next.deletes)
    }

    companion object {
        val NONE = UnsavedChanges(emptyList(), emptySet())

        /** What changed between [written] (the vault as last read or written) and [current] (the open vault). */
        fun between(written: List<SavedLogin>, current: List<SavedLogin>): UnsavedChanges {
            val old = written.associateBy { it.guid }
            val now = current.mapTo(HashSet()) { it.guid }
            return UnsavedChanges(
                upserts = current.filter { old[it.guid] != it },
                deletes = old.keys.filterNotTo(LinkedHashSet()) { it in now },
            )
        }
    }
}
