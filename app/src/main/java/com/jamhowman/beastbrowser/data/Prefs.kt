package com.jamhowman.beastbrowser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.jamhowman.beastbrowser.browser.AutoplayPolicy

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
    /** 2.8: Media Radar (stream detection, media badge, save sheet) is opt-in. Off by default. */
    val mediaRadar get() = sp.getBoolean("media_radar", false)
    /** 2.7: hide cookie banners with uBO's cookie-notice lists (EasyList/uBO – Cookie Notices). Default on. */
    val cookieBanners get() = sp.getBoolean("cookie_banners", true)
    /**
     * The [cookieBanners] value last applied to uBO (null = never, e.g. fresh install or upgrade from 2.6). Only a
     * change is pushed, so lists picked by hand in uBO's dashboard aren't undone on every resume.
     */
    var cookieBannersApplied: Boolean?
        get() = if (sp.contains("cookie_banners_applied")) sp.getBoolean("cookie_banners_applied", true) else null
        set(v) = sp.edit { if (v == null) remove("cookie_banners_applied") else putBoolean("cookie_banners_applied", v) }
    val sendDntGpc get() = sp.getBoolean("dnt_gpc", true)
    val safeBrowsing get() = sp.getBoolean("safe_browsing", true)
    val clearOnExit get() = sp.getBoolean("clear_on_exit", false)
    val darkPages get() = sp.getBoolean("dark_pages", true)
    /** 2.5 (BETA): darken light sites with no dark theme (CSS filter in beast-siteprefs). Needs [darkPages]. */
    val forceDark get() = sp.getBoolean("force_dark", false)
    /** What the extension should do: forced dark only runs on top of "Prefer dark websites". */
    val forceDarkActive get() = darkPages && forceDark
    /** The one-time "Dark page" tip under the menu grid has been shown. */
    var forceDarkTipShown: Boolean
        get() = sp.getBoolean("force_dark_tip", false)
        set(v) = sp.edit { putBoolean("force_dark_tip", v) }
    /** Item 19: the new-tab wolf's idle pulse and eye flash (default on). The loading ring shows either way. */
    var wolfAnimation: Boolean
        get() = sp.getBoolean("wolf_animation", true)
        set(v) = sp.edit { putBoolean("wolf_animation", v) }
    /** 2.8: selected alternate app icon key ([AppIcon.key]); default brand = "default". */
    var appIconKey: String
        get() = sp.getString("app_icon", AppIcon.DEFAULT.key) ?: AppIcon.DEFAULT.key
        set(v) = sp.edit { putString("app_icon", v) }
    /** dark | light | system — chrome theme (default dark). */
    val uiTheme get() = sp.getString("ui_theme", "dark") ?: "dark"
    val restoreTabs get() = sp.getBoolean("restore_tabs", true)
    /** 2.5: picture-in-picture for playing fullscreen video (default on). */
    val pipEnabled get() = sp.getBoolean("pip", true)
    /** Fullscreen sessions that showed the PiP button (labelled the first 3 times). */
    var pipHintCount: Int
        get() = sp.getInt("pip_hint_count", 0)
        set(v) = sp.edit { putInt("pip_hint_count", v) }
    /** 2.5: Secure DNS ([SecureDns]). Raw keys (null = never chosen); [SecureDns.choice] applies defaults/fallbacks. */
    val dohMode: String? get() = sp.getString("doh_mode", null)
    val dohProvider: String? get() = sp.getString("doh_provider", null)
    val dohNextDnsId get() = sp.getString("doh_nextdns_id", "") ?: ""
    val dohCustomUrl get() = sp.getString("doh_custom_url", "") ?: ""
    val secureDnsChoice: SecureDns.Choice get() = SecureDns.choice(dohMode, dohProvider)
    val secureDns: SecureDns.Config get() = SecureDns.config(dohMode, dohProvider, dohNextDnsId, dohCustomUrl)
    /** 2.5: autoplay blocker ([AutoplayPolicy.Mode] key, default block_audible = the pre-2.5 behaviour). */
    val autoplay: AutoplayPolicy.Mode get() = AutoplayPolicy.Mode.from(sp.getString("autoplay", null))
    /** Mode whose answers Gecko's stored autoplay permissions reflect; null on the first 2.5 start. */
    var autoplayApplied: String?
        get() = sp.getString("autoplay_applied", null)
        set(v) = sp.edit { putString("autoplay_applied", v) }
    /**
     * 2.5.1: opt-in crash reports ([com.jamhowman.beastbrowser.crash.CrashReporter]), off by default. Deliberately
     * not in BackupManager.SETTINGS: consent is given on each device, never restored from a backup.
     */
    val crashReports get() = sp.getBoolean("crash_reports", false)
    /** Time of the newest crash report the launch prompt already offered (so each crash is offered once). */
    var crashReportsSeen: Long
        get() = sp.getLong("crash_reports_seen", 0)
        set(v) = sp.edit { putLong("crash_reports_seen", v) }
    val searchSuggestions get() = sp.getBoolean("search_suggestions", true)
    val searchEngine get() = SearchEngine.from(sp.getString("search_engine", null))
    /** Accent theme of the current [realm] (2.3.8: per-realm accents; 2.8: [ThemePreset]s). */
    val accent: ThemePreset get() = accentFor(realm)
    /** 2.8: the theme picked in Settings > Appearance (Play's accent, key `accent`). Work follows it while on AUTO. */
    val theme: ThemePreset get() = accentFor(Realm.PLAY)

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

    /**
     * Play: Blood Moon (GX Red's successor, same `red` key). Work: Frost, or Ember if Play already uses Frost
     * (the 2.3.8 Cyan/Orange rule, shown as AUTO). Ghost: the fixed dim Ghost palette (2.8; was Ultraviolet).
     */
    fun defaultAccent(realm: Realm): ThemePreset = when (realm) {
        Realm.PLAY -> ThemePreset.DEFAULT
        Realm.WORK -> if (accentFor(Realm.PLAY) == ThemePreset.FROST) ThemePreset.EMBER else ThemePreset.FROST
        Realm.GHOST -> ThemePreset.GHOST
    }

    fun accentFor(realm: Realm): ThemePreset {
        val key = accentKey(realm) ?: return ThemePreset.GHOST
        return ThemePreset.parse(sp.getString(key, null)) ?: defaultAccent(realm)
    }

    /** [realm] has a preset of its own (Work: CUSTOM rather than AUTO). Always false for Ghost. */
    fun hasCustomAccent(realm: Realm): Boolean {
        val key = accentKey(realm) ?: return false
        return ThemePreset.parse(sp.getString(key, null)) != null
    }

    /** Stores [preset] for [realm] under its [ThemePreset.storageKey]; null clears it (Work back to AUTO). Ghost is fixed. */
    fun setAccent(realm: Realm, preset: ThemePreset?) {
        val key = accentKey(realm) ?: return
        require(preset == null || preset.selectable) { "${preset?.key} can't be picked" }
        sp.edit { if (preset == null) remove(key) else putString(key, preset.storageKey) }
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
