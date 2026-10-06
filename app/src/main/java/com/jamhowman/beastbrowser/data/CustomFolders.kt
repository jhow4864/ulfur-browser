package com.jamhowman.beastbrowser.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user bookmark folder (2.4.1), alongside the five fixed [BookmarkFolder]s. Created by backup import when a
 * bookmark's folder doesn't exist on this device. [id] is stored in `bookmarks.folder_id` exactly like a
 * [BookmarkFolder.id]; [parentId] is another custom folder, a [BookmarkFolder] id, or null (top level).
 */
data class CustomFolder(val id: String, val name: String, val parentId: String? = null)

/**
 * Custom bookmark folders, kept as JSON in prefs (`bookmark_custom_folders`) so `beast.db` stays at schema 3
 * and older builds simply see these bookmarks under "All".
 */
object CustomFolders {
    const val PREF = "bookmark_custom_folders"
    /** Prefix for generated ids; can never equal a [BookmarkFolder] id. */
    const val ID_PREFIX = "c-"
    private const val MAX_DEPTH = 16

    fun newId(): String = ID_PREFIX + UUID.randomUUID().toString().take(12)

    fun load(): List<CustomFolder> = parse(Prefs.sp.getString(PREF, null))

    fun save(list: List<CustomFolder>) = Prefs.sp.edit { putString(PREF, serialize(list)) }

    fun add(folders: List<CustomFolder>) {
        if (folders.isEmpty()) return
        val have = load()
        val ids = have.mapTo(HashSet()) { it.id }
        save(have + folders.filter { ids.add(it.id) })
    }

    /** Removes [id]: its sub-folders move up to its parent. Bookmarks are moved by the caller. */
    fun delete(id: String): CustomFolder? {
        val all = load()
        val gone = all.firstOrNull { it.id == id } ?: return null
        save(all.filter { it.id != id }.map { if (it.parentId == id) it.copy(parentId = gone.parentId) else it })
        return gone
    }

    fun parse(raw: String?): List<CustomFolder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() && BookmarkFolder.from(it) == null } ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                CustomFolder(id, o.optString("name").ifBlank { id }, o.optString("parent").takeIf { it.isNotBlank() && it != "null" && it != id })
            }
        }.getOrDefault(emptyList())
    }

    fun serialize(list: List<CustomFolder>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).apply { it.parentId?.let { p -> put("parent", p) } }
    }).toString()

    /**
     * "Parent / Child" label for [id] (custom or built-in), or null if unknown. [builtInLabel] names a
     * [BookmarkFolder]; cycles and very deep chains are cut off.
     */
    fun path(id: String?, all: List<CustomFolder>, builtInLabel: (BookmarkFolder) -> String): String? {
        if (id == null) return null
        BookmarkFolder.from(id)?.let { return builtInLabel(it) }
        val byId = all.associateBy { it.id }
        val parts = ArrayList<String>()
        val seen = HashSet<String>()
        var cur: String? = id
        while (cur != null && seen.add(cur) && parts.size < MAX_DEPTH) {
            val builtIn = BookmarkFolder.from(cur)
            if (builtIn != null) { parts += builtInLabel(builtIn); break }
            val f = byId[cur] ?: break
            parts += f.name
            cur = f.parentId
        }
        return parts.takeIf { it.isNotEmpty() }?.asReversed()?.joinToString(" / ")
    }

    fun label(context: Context, id: String?, all: List<CustomFolder> = load()): String? =
        path(id, all) { context.getString(it.labelRes) }

    /** Custom folders in display order: depth-first by name, parents before children. */
    fun ordered(all: List<CustomFolder>): List<CustomFolder> {
        val ids = all.mapTo(HashSet()) { it.id }
        val children = all.groupBy { f -> f.parentId?.takeIf { it in ids } }
        val out = ArrayList<CustomFolder>(all.size)
        val seen = HashSet<String>()
        fun walk(parent: String?) {
            children[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach { if (seen.add(it.id)) { out += it; walk(it.id) } }
        }
        walk(null)
        all.filter { it.id !in seen }.sortedBy { it.name.lowercase() }.forEach { out += it } // cycles: still listed
        return out
    }
}
