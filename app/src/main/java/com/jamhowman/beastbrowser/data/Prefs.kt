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
    val accent get() = Accent.from(sp.getString("accent", null))

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0)
        set(v) = sp.edit { putLong("total_blocked", v) }

    var disabledSites: Set<String>
        get() = sp.getStringSet("shield_disabled_sites", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("shield_disabled_sites", HashSet(v)) }

    /** Saved normal tabs (one URL per line) + selected index. */
    var savedTabs: String
        get() = sp.getString("saved_tabs", "") ?: ""
        set(v) = sp.edit { putString("saved_tabs", v) }
    var savedTabIndex: Int
        get() = sp.getInt("saved_tab_index", 0)
        set(v) = sp.edit { putInt("saved_tab_index", v) }

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
