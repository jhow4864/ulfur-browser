package com.jamhowman.beastbrowser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/**
 * 2.9: installed theme packs (filesDir/theme-packs/<id>.json, canonical JSON re-written by Ulfur, never the
 * imported file) and the active one (pref `theme_pack`). The pack recolours the Play theme; its `base` preset is
 * stored in `accent` so XML-styled views and older builds (which ignore `theme_pack`) get a sensible preset.
 *
 * [apply] is the only thing that writes; it returns a [Snapshot] of the exact previous state, and [undo] puts that
 * back byte for byte (the pref values, and the installed file if Apply added it).
 */
class ThemePackStore(val dir: File, private val sp: SharedPreferences) {

    /** Previous state: raw `accent` and `theme_pack` values (null = absent) and whether Apply installed the file. */
    data class Snapshot(val accent: String?, val packId: String?, val installedId: String?)

    fun activeId(): String? = sp.getString(KEY_PACK, null)

    fun active(): ThemePack? {
        val id = activeId() ?: return null
        cached?.let { if (it.first == id) return it.second }
        val p = load(id)
        cached = id to p
        return p
    }

    @Volatile private var cached: kotlin.Pair<String, ThemePack?>? = null

    fun installed(): List<ThemePack> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && NAME.matches(it.name) }
        .sortedBy { it.lastModified() }
        .mapNotNull { load(it.name.removeSuffix(".json")) }

    fun load(id: String): ThemePack? {
        if (!ID.matches(id)) return null
        val f = File(dir, "$id.json")
        if (!f.isFile || f.length() > ThemePackParser.MAX_JSON_BYTES) return null
        return (ThemePackParser.parseJson(f.readBytes()) as? ThemePackResult.Ok)?.pack
    }

    fun apply(pack: ThemePack): Snapshot {
        val id = pack.id
        val snap = Snapshot(sp.getString(KEY_ACCENT, null), sp.getString(KEY_PACK, null), if (File(dir, "$id.json").isFile) null else id)
        dir.mkdirs()
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(pack.toJson().toString(2))
        if (!tmp.renameTo(File(dir, "$id.json"))) { tmp.delete(); error("could not store theme pack") }
        sp.edit(commit = true) { putString(KEY_ACCENT, pack.base.storageKey); putString(KEY_PACK, id) }
        cached = null
        return snap
    }

    fun undo(s: Snapshot) {
        sp.edit(commit = true) {
            if (s.accent == null) remove(KEY_ACCENT) else putString(KEY_ACCENT, s.accent)
            if (s.packId == null) remove(KEY_PACK) else putString(KEY_PACK, s.packId)
        }
        s.installedId?.let { if (it != s.packId) File(dir, "$it.json").delete() }
        cached = null
    }

    /** Back to presets only (picking a preset in the grid). */
    fun clearActive() { sp.edit { remove(KEY_PACK) }; cached = null }

    fun select(id: String) { if (load(id) != null) { sp.edit { putString(KEY_PACK, id) }; cached = null } }

    fun delete(id: String) {
        if (!ID.matches(id)) return
        if (activeId() == id) clearActive()
        File(dir, "$id.json").delete()
    }

    companion object {
        const val KEY_PACK = "theme_pack"
        const val KEY_ACCENT = "accent"
        const val DIR = "theme-packs"
        private val ID = Regex("[0-9a-f]{16}")
        private val NAME = Regex("[0-9a-f]{16}\\.json")
    }
}

object ThemePacks {
    @Volatile private var store: ThemePackStore? = null

    fun init(context: Context) {
        if (store == null) store = ThemePackStore(File(context.applicationContext.filesDir, ThemePackStore.DIR), Prefs.sp)
    }

    fun store(): ThemePackStore? = store

    /** Tests only: rebind to [context]'s filesDir and prefs. */
    fun resetForTests(context: Context) { store = null; init(context) }

    /** The pack painting the Play theme now, or null (none, or paused by safe start). */
    fun activeForPalette(): ThemePack? =
        if (com.jamhowman.beastbrowser.crash.SafeStart.themePaused) null else try { store?.active() } catch (_: Throwable) { null }
}
