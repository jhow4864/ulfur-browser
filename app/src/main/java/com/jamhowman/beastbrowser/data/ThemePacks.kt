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
class ThemePackStore(val dir: File, private val sp: SharedPreferences, private val codec: ThemeImageCodec? = null) {

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
        // 2.10: our own re-encoded images live in <id>/ (only names Ulfur wrote: images/<slot>.<png|jpg>).
        val imgDir = File(dir, id)
        val images = (imgDir.listFiles() ?: emptyArray())
            .filter { it.isFile && IMG.matches(it.name) && it.length() <= ThemePackParser.MAX_IMAGE_BYTES }
            .associate { ThemePackParser.IMAGES_DIR + it.name to it.readBytes() }
        return (ThemePackParser.parseJson(f.readBytes(), images, codec, sanitize = false) as? ThemePackResult.Ok)?.pack
    }

    fun apply(pack: ThemePack): Snapshot {
        val id = pack.id
        val snap = Snapshot(sp.getString(KEY_ACCENT, null), sp.getString(KEY_PACK, null), if (File(dir, "$id.json").isFile) null else id)
        dir.mkdirs()
        if (pack.images.isNotEmpty()) {
            val imgDir = File(dir, id).apply { mkdirs() }
            pack.images.forEach { (slot, img) -> File(imgDir, pack.storedName(slot).removePrefix(ThemePackParser.IMAGES_DIR)).writeBytes(img.bytes) }
        }
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
        s.installedId?.let { if (it != s.packId) { File(dir, "$it.json").delete(); File(dir, it).deleteRecursively() } }
        cached = null
    }

    /** Back to presets only (picking a preset in the grid). */
    fun clearActive() { sp.edit { remove(KEY_PACK) }; cached = null }

    fun select(id: String) { if (load(id) != null) { sp.edit { putString(KEY_PACK, id) }; cached = null } }

    fun delete(id: String) {
        if (!ID.matches(id)) return
        if (activeId() == id) clearActive()
        File(dir, "$id.json").delete()
        File(dir, id).deleteRecursively()
    }

    companion object {
        const val KEY_PACK = "theme_pack"
        const val KEY_ACCENT = "accent"
        const val DIR = "theme-packs"
        private val ID = Regex("[0-9a-f]{16}")
        private val NAME = Regex("[0-9a-f]{16}\\.json")
        private val IMG = Regex("(home|toolbar|switcher|header)\\.(png|jpg)")
    }
}

object ThemePacks {
    @Volatile private var store: ThemePackStore? = null

    fun init(context: Context) {
        if (store == null) store = ThemePackStore(File(context.applicationContext.filesDir, ThemePackStore.DIR), Prefs.sp, AndroidImageCodec)
    }

    fun store(): ThemePackStore? = store

    /** 2.10: decoded bitmaps of the active pack's images, cached per pack id (null when paused or none). */
    @Volatile private var bitmaps: kotlin.Pair<String, Map<ImageSlot, android.graphics.Bitmap>>? = null

    fun activeBitmap(slot: ImageSlot): android.graphics.Bitmap? {
        val p = activeForPalette() ?: return null
        if (p.images.isEmpty()) return null
        val id = store?.activeId() ?: return null
        val cached = bitmaps?.takeIf { it.first == id }?.second
            ?: p.images.mapNotNull { (s, img) -> AndroidImageCodec.bitmap(img)?.let { s to it } }.toMap().also { bitmaps = id to it }
        return cached[slot]
    }

    fun activePack(): ThemePack? = activeForPalette()

    /** Tests only: rebind to [context]'s filesDir and prefs. */
    fun resetForTests(context: Context) { store = null; init(context) }

    /** The pack painting the Play theme now, or null (none, or paused by safe start). */
    fun activeForPalette(): ThemePack? =
        if (com.jamhowman.beastbrowser.crash.SafeStart.themePaused) null else try { store?.active() } catch (_: Throwable) { null }
}
