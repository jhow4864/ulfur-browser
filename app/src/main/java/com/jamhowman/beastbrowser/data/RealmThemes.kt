package com.jamhowman.beastbrowser.data

import android.content.SharedPreferences

/**
 * 2.10.1: per-realm theme packs. Each realm stores one choice:
 *  - absent  -> [Choice.Main]: follow the main (global) pack;
 *  - "off"   -> [Choice.Off]: theme colours only, no pack;
 *  - pack id -> [Choice.Pack]: that realm's own pack (falls back to main if the pack is gone).
 */
class RealmThemes(private val sp: SharedPreferences) {

    sealed class Choice {
        object Main : Choice()
        object Off : Choice()
        data class Pack(val id: String) : Choice()
    }

    fun choice(realm: Realm): Choice = when (val v = sp.getString(key(realm), null)) {
        null, "" -> Choice.Main
        OFF -> Choice.Off
        else -> Choice.Pack(v)
    }

    fun set(realm: Realm, c: Choice) {
        sp.edit().apply {
            when (c) {
                Choice.Main -> remove(key(realm))
                Choice.Off -> putString(key(realm), OFF)
                is Choice.Pack -> putString(key(realm), c.id)
            }
        }.commit()
    }

    /**
     * One-time upgrade from 2.10.0, where the main pack painted only Play: Work and Ghost become "theme colours
     * only" so nothing changes visually. Play keeps following the main pack. Choices already set are kept.
     */
    fun migrate() {
        if (sp.getBoolean(KEY_MIGRATED, false)) return
        val e = sp.edit()
        for (r in listOf(Realm.WORK, Realm.GHOST)) if (!sp.contains(key(r))) e.putString(key(r), OFF)
        e.putBoolean(KEY_MIGRATED, true).commit()
    }

    /** A pack was deleted: every realm that used it goes back to the main theme. */
    fun resetPack(id: String) {
        val e = sp.edit()
        Realm.entries.filter { sp.getString(key(it), null) == id }.forEach { e.remove(key(it)) }
        e.commit()
    }

    /** Id of the pack that paints [realm], or null (none). [exists] checks an installed pack id. */
    fun resolvedId(realm: Realm, mainId: String?, exists: (String) -> Boolean): String? = when (val c = choice(realm)) {
        Choice.Main -> mainId
        Choice.Off -> null
        is Choice.Pack -> if (exists(c.id)) c.id else mainId
    }

    companion object {
        const val OFF = "off"
        const val KEY_MIGRATED = "realm_packs_migrated"
        fun key(realm: Realm) = "realm_pack_${realm.key}"
    }
}
