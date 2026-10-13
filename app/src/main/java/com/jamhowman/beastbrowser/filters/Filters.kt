package com.jamhowman.beastbrowser.filters

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jamhowman.beastbrowser.browser.Engine
import com.jamhowman.beastbrowser.data.Prefs
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** App-wide owner of [FilterStore]: background updates, and pushes the compiled block to uBO through the bridge. */
object Filters {
    private const val TAG = "UlfurFilters"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "filters").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var store: FilterStore? = null
    @Volatile private var pushed: String? = null

    @Synchronized fun store(ctx: Context): FilterStore =
        store ?: FilterStore(ctx.applicationContext.filesDir).also { store = it }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { runCatching { it() } } }

    fun update(ctx: Context, ids: Collection<String>, done: () -> Unit = {}) {
        val s = store(ctx)
        io.execute {
            ids.forEach { id -> s.update(id) { url -> FilterFetcher.fetch(url) } }
            changed(); sync(ctx); main.post(done)
        }
    }

    /** 2.11 auto-update: same store and executor as manual updates (never two updates at once); blocks the caller. */
    fun updateBlocking(ctx: Context, fetch: (String) -> String): FilterAutoUpdate.Outcome {
        val s = store(ctx)
        val o = io.submit<FilterAutoUpdate.Outcome> { FilterAutoUpdate.runOnce(s, fetch) }.get()
        changed(); sync(ctx)
        return o
    }

    /** Pushes the compiled block to uBO if it changed since the last confirmed push (also on every bridge hello). */
    fun sync(ctx: Context, force: Boolean = false) {
        val s = store(ctx)
        io.execute {
            val text = runCatching { s.compile() }.getOrElse { "" }
            if (!force && text == pushed) return@execute
            if (!Prefs.ublockActive || !Engine.uboBridge.isConnected) return@execute
            Engine.uboBridge.request(JSONObject().put("type", "setManaged").put("text", text), 120_000) { r ->
                if (r?.optBoolean("ok") == true) pushed = text
                else Log.w(TAG, "filters not applied: ${r?.optString("error")?.take(80)}")
            }
        }
    }

    fun changedLocally(ctx: Context) { changed(); sync(ctx) }
}
