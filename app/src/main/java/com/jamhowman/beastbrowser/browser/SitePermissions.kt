package com.jamhowman.beastbrowser.browser

import com.jamhowman.beastbrowser.webapp.WebApps
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

/**
 * 2.12 Site permissions dashboard (docs/design-drop-2.12.md §A). Pure JVM model so grouping, filtering, apply and undo
 * are unit-tested. Two sources of truth:
 *  - Gecko's own stored content permissions (location, notifications, autoplay, DRM, …) via StorageController. Changing
 *    one here writes Gecko's stored value, so open tabs see it on their next request.
 *  - [SiteMediaStore] for camera / mic, which GeckoView never stores; TabCallbacks reads it on every media request.
 * Private / Ghost permissions are session-only: never listed, never written to disk ([SessionPermissions]).
 */
object SitePermissions {
    enum class Kind(val label: String, val chip: Chip) {
        CAMERA("Camera", Chip.CAMERA), MIC("Microphone", Chip.MIC), LOCATION("Location", Chip.LOCATION),
        NOTIFICATIONS("Notifications", Chip.NOTIFICATIONS), AUTOPLAY_AUDIBLE("Autoplay with sound", Chip.ALL),
        AUTOPLAY_INAUDIBLE("Muted autoplay", Chip.ALL), DRM("Protected video (DRM)", Chip.ALL),
        STORAGE_ACCESS("Cross-site storage", Chip.ALL), XR("Virtual reality", Chip.ALL),
        LOCAL_NETWORK("Local network", Chip.ALL), LOCAL_DEVICE("Local devices", Chip.ALL),
        PERSISTENT_STORAGE("Persistent storage", Chip.ALL), OTHER("Other", Chip.ALL);

        /** Ulfur holds no Android camera / mic / location permission, so "Allow" can't take effect for these. */
        val allowSupported: Boolean get() = this != CAMERA && this != MIC && this != LOCATION

        companion object {
            fun fromGecko(p: Int): Kind = when (p) {
                PermissionDelegate.PERMISSION_GEOLOCATION -> LOCATION
                PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> NOTIFICATIONS
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> AUTOPLAY_AUDIBLE
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> AUTOPLAY_INAUDIBLE
                PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> DRM
                PermissionDelegate.PERMISSION_STORAGE_ACCESS -> STORAGE_ACCESS
                PermissionDelegate.PERMISSION_XR -> XR
                PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> LOCAL_NETWORK
                PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> LOCAL_DEVICE
                PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> PERSISTENT_STORAGE
                else -> OTHER
            }
        }
    }

    /** Filter chips from the spec. Pop-ups / Downloads / Clipboard have no per-site permission in Ulfur yet: always empty. */
    enum class Chip(val label: String) {
        ALL("All"), CAMERA("Camera"), MIC("Mic"), LOCATION("Location"), NOTIFICATIONS("Notifications"),
        POPUPS("Pop-ups"), DOWNLOADS("Downloads"), CLIPBOARD("Clipboard")
    }

    enum class State(val label: String) {
        ALLOW("Allow"), ASK("Ask"), BLOCK("Block");
        val gecko: Int get() = when (this) {
            ALLOW -> ContentPermission.VALUE_ALLOW; ASK -> ContentPermission.VALUE_PROMPT; BLOCK -> ContentPermission.VALUE_DENY
        }
        companion object {
            fun fromGecko(v: Int) = when (v) {
                ContentPermission.VALUE_ALLOW -> ALLOW; ContentPermission.VALUE_DENY -> BLOCK; else -> ASK
            }
            fun from(s: String?) = entries.firstOrNull { it.name == s } ?: ASK
        }
    }

    /** One stored permission. [token] is the Gecko ContentPermission (null for camera / mic, which live in [SiteMediaStore]). */
    data class Entry(val origin: String, val kind: Kind, val state: State, val lastUsed: Long = 0L, val token: Any? = null)

    data class Site(val origin: String, val entries: List<Entry>) {
        val lastUsed: Long get() = entries.maxOfOrNull { it.lastUsed } ?: 0L
        val domain: String get() = origin.substringAfter("://")
        /** Spec: max 5 glyphs, then "+n". Only allowed/blocked decisions get a glyph (Ask is the default). */
        fun glyphs(max: Int = 5): Pair<List<Entry>, Int> {
            val decided = entries.filter { it.state != State.ASK }
            return decided.take(max) to (decided.size - max).coerceAtLeast(0)
        }
    }

    /** Origin = scheme://host[:port], so http and https of the same host are separate sites. */
    fun origin(uri: String?): String? = uri?.let { WebApps.origin(it) }

    /** The tracking-protection exception is the Shields switch, managed there, not here. */
    fun shownInDashboard(geckoPermission: Int) = geckoPermission != PermissionDelegate.PERMISSION_TRACKING

    /** Private and Ghost permissions are session-only and never listed. */
    fun listable(privateMode: Boolean, contextId: String?, ghostContextId: String?): Boolean =
        !privateMode && (ghostContextId == null || contextId != ghostContextId)

    /** Group by origin, most recently used first, then by name; entries inside a site in [Kind] order. */
    fun group(entries: List<Entry>): List<Site> = entries.groupBy { it.origin }
        .map { (o, e) -> Site(o, e.sortedBy { it.kind.ordinal }) }
        .sortedWith(compareByDescending<Site> { it.lastUsed }.thenBy { it.domain }.thenBy { it.origin })

    /**
     * Camera / mic: the stored decision is read on every request, so a Block applies to the next request with no
     * reload. Grants need Ulfur to hold the Android permission too, which it doesn't (yet), so only Block/Ask matter.
     */
    fun mediaAllowed(state: State?, androidGranted: Boolean): Boolean = state == State.ALLOW && androidGranted

    fun filter(sites: List<Site>, chip: Chip, query: String): List<Site> {
        val q = query.trim().lowercase()
        return sites.filter { s ->
            (q.isEmpty() || s.domain.contains(q)) && (chip == Chip.ALL || s.entries.any { it.kind.chip == chip })
        }
    }
}

/** Key-value persistence so [SiteMediaStore] is testable without Android (production: a SharedPreferences file). */
interface KeyValue {
    fun get(): String?
    fun put(value: String?)
}

/**
 * Camera / mic decisions per origin plus last-request times, for normal (non-private) browsing only. JSON:
 * {origin: {"CAMERA": "BLOCK", "t_CAMERA": 123, ...}}. Also records "last used" for Gecko kinds (MRU sort).
 */
class SiteMediaStore(private val kv: KeyValue) {
    private val lock = Any()
    private fun read(): JSONObject = runCatching { JSONObject(kv.get() ?: "{}") }.getOrDefault(JSONObject())

    fun state(origin: String, kind: SitePermissions.Kind): SitePermissions.State? = synchronized(lock) {
        read().optJSONObject(origin)?.optString(kind.name, "")?.takeIf { it.isNotEmpty() }?.let { SitePermissions.State.from(it) }
    }

    fun lastUsed(origin: String, kind: SitePermissions.Kind): Long = synchronized(lock) {
        read().optJSONObject(origin)?.optLong("t_${kind.name}", 0L) ?: 0L
    }

    /** null [state] removes the decision (and the site once empty). */
    fun set(origin: String, kind: SitePermissions.Kind, state: SitePermissions.State?) = synchronized(lock) {
        val all = read()
        val o = all.optJSONObject(origin) ?: JSONObject()
        if (state == null) o.remove(kind.name) else o.put(kind.name, state.name)
        if (state == null && !o.has(kind.name)) o.remove("t_${kind.name}")
        if (o.length() == 0) all.remove(origin) else all.put(origin, o)
        kv.put(all.toString())
    }

    /** Never call for private / Ghost sessions (TabCallbacks checks). */
    fun touch(origin: String, kind: SitePermissions.Kind, now: Long) = synchronized(lock) {
        val all = read()
        val o = all.optJSONObject(origin) ?: JSONObject()
        o.put("t_${kind.name}", now)
        if ((kind == SitePermissions.Kind.CAMERA || kind == SitePermissions.Kind.MIC) && !o.has(kind.name)) o.put(kind.name, "ASK")
        all.put(origin, o); kv.put(all.toString())
    }

    fun mediaEntries(): List<SitePermissions.Entry> = synchronized(lock) {
        val all = read()
        all.keys().asSequence().flatMap { origin ->
            val o = all.getJSONObject(origin)
            listOf(SitePermissions.Kind.CAMERA, SitePermissions.Kind.MIC).filter { o.has(it.name) }.map {
                SitePermissions.Entry(origin, it, SitePermissions.State.from(o.getString(it.name)), o.optLong("t_${it.name}", 0L))
            }
        }.toList()
    }

    fun clearSite(origin: String) = synchronized(lock) { val a = read(); a.remove(origin); kv.put(a.toString()) }
    fun clearAll() = synchronized(lock) { kv.put(null) }
}

/** Session-only decisions made in Private / Ghost tabs: memory only, gone with the process or a Ghost burn. */
object SessionPermissions {
    private val map = java.util.concurrent.ConcurrentHashMap<String, SitePermissions.State>()
    private fun key(origin: String, kind: SitePermissions.Kind) = "$origin|${kind.name}"
    fun get(origin: String, kind: SitePermissions.Kind) = map[key(origin, kind)]
    fun set(origin: String, kind: SitePermissions.Kind, s: SitePermissions.State) { map[key(origin, kind)] = s }
    fun clear() = map.clear()
}

/** Where Gecko-stored permissions are written. Production: StorageController.setPermission; tests: a fake. */
interface GeckoPermissionWriter {
    fun write(entry: SitePermissions.Entry, state: SitePermissions.State)
}

/**
 * Applies dashboard changes immediately and keeps the exact prior state for the 10 s Undo. Gecko kinds go to Gecko's
 * stored permission (shared by every open tab of that origin); camera / mic go to [SiteMediaStore], which
 * TabCallbacks consults on each request, so a Block takes effect on the next request without a reload.
 */
class SitePermissionController(private val gecko: GeckoPermissionWriter, private val media: SiteMediaStore) {
    /** Prior entries, as they were, to restore with [undo]. */
    data class Undo(val before: List<SitePermissions.Entry>)

    fun apply(entries: List<SitePermissions.Entry>, state: SitePermissions.State): Undo {
        entries.forEach { write(it, state) }
        return Undo(entries)
    }

    fun apply(entry: SitePermissions.Entry, state: SitePermissions.State) = apply(listOf(entry), state)

    fun undo(u: Undo) = u.before.forEach { write(it, it.state) }

    private fun write(e: SitePermissions.Entry, s: SitePermissions.State) {
        if (e.token == null && (e.kind == SitePermissions.Kind.CAMERA || e.kind == SitePermissions.Kind.MIC)) media.set(e.origin, e.kind, s)
        else gecko.write(e, s)
    }
}
