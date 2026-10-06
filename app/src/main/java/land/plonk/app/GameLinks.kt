package land.plonk.app

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.core.net.toUri
import org.json.JSONObject

/**
 * A link that opens the game, already checked and rewritten onto the game origin.
 *
 * [url] is what the WebView loads at cold start (or as the fallback). [target], [ref] and [kol]
 * are what the running game needs to act on the link in place.
 */
class GameLink internal constructor(
    val url: Uri,
    /** Game window to open: bag, market or map. */
    val target: String?,
    /** Referral code (`?ref=`). */
    val ref: String?,
    /** Creator / campaign code (`?kol=`). */
    val kol: String?,
    /** True when the link adds nothing but open/ref/kol to its page (no other params, no #). */
    private val onlyLinkParams: Boolean,
) {
    /** True when there is something for a running game to act on. */
    val hasPayload: Boolean get() = target != null || ref != null || kol != null

    /**
     * True when [currentUrl] is already this link's page, so the running game can take the link
     * without a reload.
     */
    fun sameDocumentAs(currentUrl: String?): Boolean {
        if (!onlyLinkParams) return false
        val current = currentUrl?.toUri() ?: return false
        return current.scheme == "https" &&
            current.host.equals(url.host, ignoreCase = true) &&
            normalizedPath(current.path) == normalizedPath(url.path)
    }

    /** The `plonknative` event detail: `{ t: 'open', target?, ref?, kol?, url }`. */
    fun toEventDetail(): JSONObject =
        JSONObject().apply {
            put("t", "open")
            target?.let { put("target", it) }
            ref?.let { put("ref", it) }
            kol?.let { put("kol", it) }
            put("url", url.toString())
        }

    override fun toString() = url.toString()

    private fun normalizedPath(path: String?) = path?.ifEmpty { "/" } ?: "/"
}

/**
 * Turns VIEW intents into game URLs and hands them to the game.
 *
 * Accepted links (everything else is ignored, and the app just opens or stays where it is):
 * - `https://play.plonk.land/...`: App Links. Path and query are kept, except that `open`, `ref`
 *   and `kol` must be valid (see below) or are dropped.
 * - `https://plonk.land/?ref=...&kol=...`: referral links from the website (root path only).
 * - `plonk://play`, `plonk://bag`, `plonk://market`, `plonk://map`, with optional `?ref=&kol=`:
 *   the custom scheme, also used by the launcher shortcuts.
 *
 * Hosts are matched exactly, https only, with no user info and no port other than 443. `open`
 * must be bag, market or map; `ref` and `kol` must be short codes. The URL loaded is always
 * rebuilt from scratch on the game origin of [startUrl], with the start URL's query (`src=app`)
 * kept, so a malformed or hostile link can never steer the WebView off the game.
 *
 * Delivery to a running game. Reloading would cost the player their session (mid-fight, an
 * open trade), so when the link only adds open/ref/kol to the page already showing, it is
 * dispatched to the page as a cancelable DOM event instead:
 *
 * ```js
 * window.addEventListener('plonknative', (e) => {
 *   const m = e.detail; // { t: 'open', target?: 'bag' | 'market' | 'map', ref?, kol?, url }
 *   if (m.t === 'open' && handleLink(m)) e.preventDefault(); // "handled, don't reload"
 * });
 * ```
 *
 * An event rather than a PlonkNative message, because the bridge only answers messages the page
 * sent, and a link can arrive before the page has said anything. The event also answers
 * synchronously: if no listener calls preventDefault() (an older page build, or a page still
 * loading), the app loads [GameLink.url] instead, so a link is never lost.
 */
class GameLinks(
    startUrl: String,
) {
    private val start = startUrl.toUri()
    private val gameHost = start.host.orEmpty().lowercase()
    private val startPath = start.encodedPath?.ifEmpty { null } ?: "/"
    private val startParams = queryParams(start)

    /** The link in [intent], or null if it isn't a game link (e.g. a plain launcher start). */
    fun fromIntent(intent: Intent?): GameLink? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        // Reopening from Recents replays the original intent; don't reopen the bag every time.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return parse(intent.data ?: return null)
    }

    fun parse(uri: Uri): GameLink? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()
        return when {
            scheme == "https" && host == gameHost && isPlainAuthority(uri) -> fromGameUrl(uri)
            scheme == "https" && host == REFERRAL_HOST && isPlainAuthority(uri) && isRoot(uri) ->
                build(startPath, linkParams(uri), fragment = null)
            scheme == SCHEME && isRoot(uri) -> fromScheme(uri, host)
            else -> null
        }
    }

    /**
     * Hand [link] to the game in [webView]: in place if the game already shows that page and
     * takes the event, otherwise by loading the link.
     */
    fun deliver(
        webView: WebView,
        link: GameLink,
    ) {
        if (webView.progress < 100 || !link.sameDocumentAs(webView.url)) {
            webView.loadUrl(link.url.toString())
            return
        }
        if (!link.hasPayload) return // already on the game: bringing the app forward is enough
        webView.evaluateJavascript(dispatchScript(link.toEventDetail())) { result ->
            // Skip the fallback if this WebView was replaced (renderer loss) in the meantime.
            if (result != "\"handled\"" && webView.isAttachedToWindow) webView.loadUrl(link.url.toString())
        }
    }

    private fun fromGameUrl(uri: Uri): GameLink {
        // A path we can't vouch for falls back to the start page; the referral still counts.
        val path = uri.encodedPath?.takeIf { SAFE_PATH.matches(it) && ".." !in it } ?: startPath
        val params = LinkedHashMap<String, String>()
        for (name in queryNames(uri)) {
            if (name in LINK_PARAMS || !PARAM_NAME.matches(name)) continue
            val value = uri.safeQuery(name) ?: continue
            if (value.length <= MAX_PARAM_VALUE) params[name] = value
        }
        params.putAll(linkParams(uri))
        val fragment = uri.encodedFragment?.takeIf { SAFE_FRAGMENT.matches(it) }
        return build(path, params, fragment)
    }

    /** plonk://bag?ref=... : the host names the window to open, `play` opens just the game. */
    private fun fromScheme(
        uri: Uri,
        host: String?,
    ): GameLink? {
        val params = linkParams(uri)
        when (host) {
            "play" -> Unit
            in OPEN_TARGETS -> params["open"] = host!!
            else -> return null
        }
        return build(startPath, params, fragment = null)
    }

    private fun build(
        path: String,
        params: Map<String, String>,
        fragment: String?,
    ): GameLink {
        // The start URL's own params (src=app) always win: the game relies on them.
        val query = LinkedHashMap(startParams).apply { params.forEach { (k, v) -> putIfAbsent(k, v) } }
        val url =
            Uri
                .Builder()
                .scheme("https")
                .authority(gameHost)
                .encodedPath(path)
                .apply { query.forEach { (k, v) -> appendQueryParameter(k, v) } }
                .encodedFragment(fragment)
                .build()
        val extra = params.keys - LINK_PARAMS - startParams.keys
        return GameLink(
            url = url,
            target = params["open"],
            ref = params["ref"],
            kol = params["kol"],
            onlyLinkParams = extra.isEmpty() && fragment == null,
        )
    }

    /** The validated open/ref/kol of [uri]; invalid values are dropped, not passed on. */
    private fun linkParams(uri: Uri): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        uri.safeQuery("open")?.lowercase()?.takeIf { it in OPEN_TARGETS }?.let { out["open"] = it }
        uri.safeQuery("ref")?.takeIf { CODE.matches(it) }?.let { out["ref"] = it }
        uri.safeQuery("kol")?.takeIf { CODE.matches(it) }?.let { out["kol"] = it }
        return out
    }

    private fun isPlainAuthority(uri: Uri) = uri.userInfo == null && (uri.port == -1 || uri.port == 443)

    private fun isRoot(uri: Uri) = uri.path.isNullOrEmpty() || uri.path == "/"

    private fun queryParams(uri: Uri): Map<String, String> =
        queryNames(uri).associateWith { uri.getQueryParameter(it).orEmpty() }

    private fun queryNames(uri: Uri): Set<String> = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())

    private fun Uri.safeQuery(name: String): String? =
        runCatching { getQueryParameter(name) }.getOrNull()?.trim()

    private companion object {
        const val SCHEME = "plonk"
        const val REFERRAL_HOST = "plonk.land"
        const val MAX_PARAM_VALUE = 512

        val OPEN_TARGETS = setOf("bag", "market", "map")
        val LINK_PARAMS = setOf("open", "ref", "kol")

        /** Referral and creator codes: wallet addresses, handles, short slugs. */
        val CODE = Regex("^[A-Za-z0-9_.@-]{1,64}$")
        val PARAM_NAME = Regex("^[A-Za-z0-9_.-]{1,40}$")
        val SAFE_PATH = Regex("^/[A-Za-z0-9._~/-]{0,200}$")
        val SAFE_FRAGMENT = Regex("^[A-Za-z0-9._~/=&-]{1,200}$")

        /** Dispatches the event; answers "handled" only if a listener called preventDefault(). */
        fun dispatchScript(detail: JSONObject) =
            """(function(d){try{
  var e = new CustomEvent('plonknative', { detail: d, cancelable: true });
  return window.dispatchEvent(e) ? 'unhandled' : 'handled';
}catch(x){ return 'error'; }})($detail)"""
    }
}
