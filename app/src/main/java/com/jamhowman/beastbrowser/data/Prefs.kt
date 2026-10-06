package com.jamhowman.beastbrowser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Prefs {
    const val FILE = "beast_prefs"
    lateinit var sp: SharedPreferences
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    val blockAds get() = sp.getBoolean("block_ads", true)
    val cosmetic get() = sp.getBoolean("cosmetic", true)
    val httpsMode get() = sp.getString("https_mode", "all") ?: "all"
    val cookieMode get() = sp.getString("cookie_mode", "tcp") ?: "tcp"
    val fingerprinting get() = sp.getBoolean("fingerprinting", true)
    val ublockEnabled get() = sp.getBoolean("ublock", true)
    val sendDntGpc get() = sp.getBoolean("dnt_gpc", true)
    val safeBrowsing get() = sp.getBoolean("safe_browsing", true)
    val clearOnExit get() = sp.getBoolean("clear_on_exit", false)
    val darkPages get() = sp.getBoolean("dark_pages", true)
    /** dark | light | system — chrome theme (default dark). */
    val uiTheme get() = sp.getString("ui_theme", "dark") ?: "dark"
    val restoreTabs get() = sp.getBoolean("restore_tabs", true)
    val searchSuggestions get() = sp.getBoolean("search_suggestions", true)
    val searchEngine get() = SearchEngine.from(sp.getString("search_engine", null))
    /** Accent of the current [realm] (2.3.8: per-realm accents). */
    val accent: Accent get() = accentFor(realm)

    // ---- 2.3.8: Realms. Key names match 2.3.8 so upgraded installs keep their state. ----

    var realm: Realm
        get() = Realm.from(sp.getString("realm", null))
        set(v) = sp.edit { putString("realm", v.key) }

    /** Pref key holding [realm]'s accent: `accent` (Play), `accent_work`, or null (Ghost is fixed). */
    fun accentKey(realm: Realm): String? = when (realm) {
        Realm.PLAY -> "accent"
        Realm.WORK -> "accent_work"
        Realm.GHOST -> null
    }

    /** Play: GX Red. Work: Cyber Cyan, or Lava Orange if Play already uses Cyan. Ghost: Ultraviolet. */
    fun defaultAccent(realm: Realm): Accent = when (realm) {
        Realm.PLAY -> Accent.RED
        Realm.WORK -> if (Accent.from(sp.getString("accent", null)) == Accent.CYAN) Accent.ORANGE else Accent.CYAN
        Realm.GHOST -> Accent.PURPLE
    }

    fun accentFor(realm: Realm): Accent {
        val key = accentKey(realm) ?: return Accent.PURPLE
        return sp.getString(key, null)?.let { Accent.from(it) } ?: defaultAccent(realm)
    }

    var ghostHintShown: Boolean
        get() = sp.getBoolean("ghost_hint_shown", false)
        set(v) = sp.edit { putBoolean("ghost_hint_shown", v) }

    /** Play uses the plain key (same as pre-Realms builds); other realms add `_work` / `_ghost`. */
    private fun realmKey(base: String, realm: Realm) = if (realm == Realm.PLAY) base else "${base}_${realm.key}"

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0)
        set(v) = sp.edit { putLong("total_blocked", v) }

    var disabledSites: Set<String>
        get() = sp.getStringSet("shield_disabled_sites", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("shield_disabled_sites", HashSet(v)) }

    /**
     * Saved session of one realm, all newline-joined and parallel:
     * - `saved_tabs`: URL per normal tab (`beast://home` for the home page)
     * - `saved_tab_groups` (2.3.5): [TabGroup] id per tab, "" = none
     * - `saved_tab_parents` (2.3.8): index of the parent tab in the same list, "" = none
     * - `saved_tab_index`: selected tab
     */
    fun savedTabs(realm: Realm): String = sp.getString(realmKey("saved_tabs", realm), "") ?: ""
    fun savedTabGroups(realm: Realm): String = sp.getString(realmKey("saved_tab_groups", realm), "") ?: ""
    fun savedTabParents(realm: Realm): String = sp.getString(realmKey("saved_tab_parents", realm), "") ?: ""
    fun savedTabIndex(realm: Realm): Int = sp.getInt(realmKey("saved_tab_index", realm), 0)

    fun saveTabs(realm: Realm, tabs: String, groups: String, parents: String, index: Int) = sp.edit {
        putString(realmKey("saved_tabs", realm), tabs)
        putString(realmKey("saved_tab_groups", realm), groups)
        putString(realmKey("saved_tab_parents", realm), parents)
        putInt(realmKey("saved_tab_index", realm), index)
    }

    /** Set while browsing with "clear on exit" on; if the process dies before a clean exit we wipe on next start. */
    var pendingWipe: Boolean
        get() = sp.getBoolean("pending_wipe", false)
        set(v) = sp.edit(commit = true) { putBoolean("pending_wipe", v) }

    var speedDial: String?
        get() = sp.getString("speed_dial", null)
        set(v) = sp.edit { putString("speed_dial", v) }

    var extensionsInstalledFor: Int
        get() = sp.getInt("ext_installed_for", 0)
        set(v) = sp.edit { putInt("ext_installed_for", v) }
    var notificationsAsked: Boolean
        get() = sp.getBoolean("notif_asked", false)
        set(v) = sp.edit { putBoolean("notif_asked", v) }

    var privateWarningShown: Boolean
        get() = sp.getBoolean("private_warning_shown", false)
        set(v) = sp.edit { putBoolean("private_warning_shown", v) }
}
