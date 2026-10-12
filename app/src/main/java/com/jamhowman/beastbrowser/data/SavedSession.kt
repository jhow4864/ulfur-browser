package com.jamhowman.beastbrowser.data

/**
 * 2.9: one realm's saved tab session as stored in [Prefs.saveTabs] (newline-joined parallel lists), parsed and
 * encoded in one place so safe start's "Restore everything" brings back exactly what was saved: the same tabs, in the
 * same order, with the same groups, parents and selected tab. Private tabs are never saved (by design), and each
 * realm (container) keeps its own session.
 */
data class SavedSession(val tabs: List<Entry>, val selected: Int) {
    data class Entry(val url: String, val group: String? = null, val parent: Int? = null)

    fun encode(): Encoded = Encoded(
        tabs = tabs.joinToString("\n") { it.url },
        groups = tabs.joinToString("\n") { it.group.orEmpty() },
        parents = tabs.joinToString("\n") { it.parent?.toString().orEmpty() },
        index = selected,
    )

    data class Encoded(val tabs: String, val groups: String, val parents: String, val index: Int)

    companion object {
        fun parse(tabs: String, groups: String, parents: String, index: Int): SavedSession {
            // Blank URL lines are dropped, so map the other lists through the kept positions.
            val rows = tabs.split('\n')
            val g = groups.split('\n')
            val p = parents.split('\n')
            val kept = rows.indices.filter { rows[it].isNotBlank() }
            val newIndex = kept.withIndex().associate { (n, old) -> old to n }
            val entries = kept.map { i ->
                Entry(
                    url = rows[i],
                    group = g.getOrNull(i)?.takeIf { it.isNotBlank() },
                    parent = p.getOrNull(i)?.toIntOrNull()?.let { newIndex[it] }?.takeIf { it != newIndex[i] },
                )
            }
            return SavedSession(entries, index.coerceIn(0, (entries.size - 1).coerceAtLeast(0)))
        }

        fun load(realm: Realm) = parse(Prefs.savedTabs(realm), Prefs.savedTabGroups(realm), Prefs.savedTabParents(realm), Prefs.savedTabIndex(realm))
    }
}
