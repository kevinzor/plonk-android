package land.plonk.app.bridge

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import land.plonk.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * `window.PlonkNative`: the message bridge the game page uses when it runs inside the app.
 *
 * Only the main frame of [allowedOrigin] can reach it (WebViewCompat.addWebMessageListener with an
 * origin allow-list), so ads, iframes or other sites can never vibrate the phone or close the app.
 *
 * The bridge only routes. Each message type belongs to one [BridgeHandler]; MainActivity lists
 * them. Envelope:
 *
 *   page -> app   PlonkNative.postMessage(JSON.stringify({ t: 'haptic', k: 'hit', id?: 7 }))
 *   app -> page   PlonkNative.onmessage = e => JSON.parse(e.data)   // { t, id?, ... }
 *
 * `{ t: 'caps' }` is built in and replies `{ t: 'caps', v, types: [...] }`, so the page can turn
 * features on only when this build of the app supports them. Unknown types are ignored, as before.
 */
class NativeBridge(
    private val allowedOrigin: String,
    handlers: List<BridgeHandler>,
) {
    private val capsHandler =
        object : BridgeHandler {
            override val types = setOf(CAPS)

            override fun handle(
                msg: JSONObject,
                reply: Reply,
            ) = reply.post(
                JSONObject()
                    .put("v", PROTOCOL_VERSION)
                    .put("types", JSONArray(routes.keys.sorted())),
            )
        }

    private val handlers = handlers + capsHandler
    private val routes: Map<String, BridgeHandler> = buildRoutes(this.handlers)
    private val main = Handler(Looper.getMainLooper())

    /** Bumped whenever the WebView is replaced or torn down; replies from older ones are dropped. */
    private var session = 0
    private var disposed = false

    /** Every type the page can send, `caps` included. */
    val supportedTypes: Set<String> get() = routes.keys

    /** Expose `window.PlonkNative` in [webView]. Call once per WebView, before its first load. */
    fun attach(webView: WebView) {
        session++
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val mySession = session
        WebViewCompat.addWebMessageListener(webView, JS_NAME, setOf(allowedOrigin)) {
            _: WebView,
            message: WebMessageCompat,
            _: Uri,
            isMainFrame: Boolean,
            proxy: JavaScriptReplyProxy,
            ->
            if (isMainFrame) onUiThread { dispatch(message.data, proxy, mySession) }
        }
    }

    /** Tear down: pending replies are dropped and every handler is disposed. */
    fun dispose() {
        if (disposed) return
        disposed = true
        session++
        handlers.forEach { runCatching { it.dispose() } }
    }

    private fun dispatch(
        raw: String?,
        proxy: JavaScriptReplyProxy,
        fromSession: Int,
    ) {
        if (disposed || fromSession != session) return
        val msg = runCatching { JSONObject(raw ?: return) }.getOrNull() ?: return
        val type = msg.optString("t")
        val handler = routes[type]
        if (handler == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "PlonkNative: no handler for '$type'")
            return
        }
        val reply = Reply(type, msg.opt("id"), proxy) { !disposed && session == fromSession }
        try {
            handler.handle(msg, reply)
        } catch (e: Exception) {
            Log.w(TAG, "PlonkNative '$type' failed", e)
            reply.error("failed")
        }
    }

    private fun onUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "Plonk"
        const val JS_NAME = "PlonkNative"
        const val CAPS = "caps"

        /** Bump when the envelope itself changes, not when a type is added (caps covers that). */
        const val PROTOCOL_VERSION = 1

        fun buildRoutes(handlers: List<BridgeHandler>): Map<String, BridgeHandler> {
            val routes = LinkedHashMap<String, BridgeHandler>()
            for (handler in handlers) {
                for (type in handler.types) {
                    val taken = routes.put(type, handler)
                    check(taken == null) {
                        "PlonkNative type '$type' is claimed by both ${taken!!.javaClass.simpleName} " +
                            "and ${handler.javaClass.simpleName}"
                    }
                }
            }
            return routes
        }
    }
}
