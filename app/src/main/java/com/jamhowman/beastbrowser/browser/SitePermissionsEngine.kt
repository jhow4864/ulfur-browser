package com.jamhowman.beastbrowser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jamhowman.beastbrowser.data.Realm
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.StorageController

/** 2.12: Android side of [SitePermissions]: Gecko's StorageController plus the camera / mic store. */
object SitePermissionsEngine {
    private const val TAG = "UlfurSitePerms"
    private const val FILE = "site_media_permissions"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var storeRef: SiteMediaStore? = null

    fun store(ctx: Context): SiteMediaStore = storeRef ?: synchronized(this) {
        storeRef ?: run {
            val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            SiteMediaStore(object : KeyValue {
                override fun get(): String? = sp.getString("v", null)
                override fun put(value: String?) { sp.edit().apply { if (value == null) remove("v") else putString("v", value) }.apply() }
            }).also { storeRef = it }
        }
    }

    /** Writes Gecko's stored permission: the one every open tab of that origin consults. */
    val geckoWriter = object : GeckoPermissionWriter {
        override fun write(entry: SitePermissions.Entry, state: SitePermissions.State) {
            val p = entry.token as? ContentPermission ?: return
            val sc = Engine.runtime?.storageController ?: return
            runCatching { sc.setPermission(p, state.gecko) }.onFailure { Log.w(TAG, "setPermission failed", it) }
        }
    }

    fun controller(ctx: Context) = SitePermissionController(geckoWriter, store(ctx))

    /** Every listable permission (normal browsing only), grouped and MRU-sorted. [done] on the main thread. */
    fun load(ctx: Context, done: (List<SitePermissions.Site>) -> Unit) {
        val store = store(ctx)
        val sc = Engine.runtime?.storageController
        val finish = { gecko: List<ContentPermission> ->
            val entries = toEntries(gecko, store) + store.mediaEntries()
            main.post { done(SitePermissions.group(entries)) }
        }
        if (sc == null) { finish(emptyList()); return }
        sc.allPermissions.accept({ finish(it.orEmpty()) }, { e -> Log.w(TAG, "getAllPermissions", e); finish(emptyList()) })
    }

    fun toEntries(list: List<ContentPermission>, store: SiteMediaStore): List<SitePermissions.Entry> = list
        .filter { SitePermissions.shownInDashboard(it.permission) }
        .filter { SitePermissions.listable(it.privateMode, it.contextId, Realm.GHOST.contextId) }
        .mapNotNull { p ->
            val o = SitePermissions.origin(p.uri) ?: return@mapNotNull null
            val k = SitePermissions.Kind.fromGecko(p.permission)
            SitePermissions.Entry(o, k, SitePermissions.State.fromGecko(p.value), store.lastUsed(o, k), p)
        }

    fun clearSiteData(origin: String) {
        val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: return
        Engine.runtime?.storageController?.clearDataFromHost(host, StorageController.ClearFlags.ALL)
    }
}
