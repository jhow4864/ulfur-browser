package com.jamhowman.beastbrowser.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

/**
 * Request/response channel to a built-in extension over a GeckoView native-messaging port.
 * The extension calls `browser.runtime.connectNative(nativeApp)`; we answer with JSON messages tagged by `id`.
 * Messages without an id are events (e.g. "siteChanged"). All callbacks run on the main thread.
 */
class NativeBridge(private val nativeApp: String) : WebExtension.MessageDelegate, WebExtension.PortDelegate {
    private val main = Handler(Looper.getMainLooper())
    private var port: WebExtension.Port? = null
    private var nextId = 1
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()
    private val queued = ArrayList<JSONObject>()
    val events = mutableListOf<(JSONObject) -> Unit>()

    val isConnected: Boolean get() = port != null

    fun attach(ext: WebExtension) = ext.setMessageDelegate(this, nativeApp)

    override fun onConnect(port: WebExtension.Port) {
        Log.i(TAG, "$nativeApp connected")
        this.port = port
        port.setDelegate(this)
        queued.toList().also { queued.clear() }.forEach { runCatching { port.postMessage(it) } }
    }

    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? = null

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val o = message as? JSONObject ?: return
        val id = o.optInt("id", 0)
        if (id != 0) pending.remove(id)?.invoke(o) else events.toList().forEach { it(o) }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        if (this.port === port) this.port = null
        Log.w(TAG, "$nativeApp disconnected")
    }

    /** Sends [msg]; [cb] gets the reply, or null on timeout / when the extension isn't connected in time. */
    fun request(msg: JSONObject, timeoutMs: Long = 4000, cb: (JSONObject?) -> Unit) {
        main.post {
            val id = nextId++
            msg.put("id", id)
            pending[id] = cb
            val p = port
            if (p != null) runCatching { p.postMessage(msg) }.onFailure { pending.remove(id)?.invoke(null); return@post }
            else queued += msg
            main.postDelayed({
                queued.remove(msg)
                pending.remove(id)?.invoke(null)
            }, timeoutMs)
        }
    }

    private companion object { const val TAG = "BeastBridge" }
}
