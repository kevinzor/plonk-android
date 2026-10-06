package land.plonk.app

import android.webkit.WebView
import org.json.JSONObject

/**
 * App-to-page news the page did not ask for: a cancelable `plonknative` CustomEvent on `window`.
 *
 * The bridge can only answer messages the page sent, and this news (a link was tapped, the
 * network is back) can arrive before the page has said anything. The event also answers at once:
 * a listener that deals with it calls `preventDefault()`, and otherwise (an older page build, a
 * page still loading) the caller falls back to doing the job natively, so nothing is lost.
 *
 * ```js
 * window.addEventListener('plonknative', (e) => {
 *   const m = e.detail; // { t: 'open', ... } or { t: 'online', ... }
 *   if (handle(m)) e.preventDefault();
 * });
 * ```
 */
internal object PageEvents {
    /** Fire [detail] at the page; [onResult] gets true only if a listener called preventDefault(). */
    fun dispatch(
        webView: WebView,
        detail: JSONObject,
        onResult: (handled: Boolean) -> Unit,
    ) {
        webView.evaluateJavascript(script(detail)) { result -> onResult(result == "\"handled\"") }
    }

    private fun script(detail: JSONObject) =
        """(function(d){try{
  var e = new CustomEvent('plonknative', { detail: d, cancelable: true });
  return window.dispatchEvent(e) ? 'unhandled' : 'handled';
}catch(x){ return 'error'; }})($detail)"""
}
