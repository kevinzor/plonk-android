package land.plonk.app.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject

/**
 * The way back to the page that sent a message.
 *
 * Handlers often answer later (after a permission prompt, a wallet round trip, a network call),
 * by which time the WebView may have been rebuilt or the activity destroyed. A Reply is safe to
 * keep and call at any time, from any thread:
 * - it hops to the UI thread, which JavaScriptReplyProxy requires;
 * - it is dropped once the WebView it came from is gone (renderer rebuild, activity destroyed);
 * - if the page navigated in between, WebView drops it too, because each proxy is bound to the
 *   document that sent the message. A reply can never reach a different page.
 *
 * Every reply carries `t` (the request's type unless the body sets one) and echoes the request's
 * `id` when it had one, so the page can match answers to requests.
 */
class Reply internal constructor(
    /** The request's `t`. */
    val type: String,
    private val id: Any?,
    private val proxy: JavaScriptReplyProxy,
    private val isLive: () -> Boolean,
) {
    /** False once the WebView this request came from is gone; long tasks can stop early. */
    val isOpen: Boolean get() = isLive()

    /** Send [body] to the page. Adds `t` and `id` to it if missing. */
    fun post(body: JSONObject = JSONObject()) {
        if (!body.has("t")) body.put("t", type)
        if (id != null && !body.has("id")) body.put("id", id)
        val text = body.toString()
        if (Looper.myLooper() == Looper.getMainLooper()) send(text) else main.post { send(text) }
    }

    /** Send `{ t, error: code }`, plus a human-readable [detail] if given. */
    fun error(
        code: String,
        detail: String? = null,
    ) {
        val body = JSONObject().put("error", code)
        if (detail != null) body.put("detail", detail)
        post(body)
    }

    private fun send(text: String) {
        if (!isLive()) return
        runCatching { proxy.postMessage(text) }
            .onFailure { Log.w(TAG, "Bridge reply for '$type' dropped", it) }
    }

    private companion object {
        const val TAG = "Plonk"
        val main = Handler(Looper.getMainLooper())
    }
}
