package land.plonk.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri

/**
 * Navigation policy for the game WebView. Forked from Solana Mobile's webshell template
 * (Apache-2.0), plus renderer-crash recovery and main-frame callbacks for MainActivity.
 *
 * - play.plonk.land stays inside the app; every other http(s) page opens in the system browser.
 * - solana-wallet: links launch the wallet app (Mobile Wallet Adapter), then a synthetic blur
 *   tells the MWA JS client that the wallet opened (a WebView never fires blur on its own).
 * - intent: links are sanitized to implicit, browsable targets only.
 */
open class PlonkWebViewClient(
    private val context: Context,
    private val scopeHost: String,
    private val onMainFrameFinished: (WebView, String?) -> Unit,
    private val onMainFrameError: () -> Unit,
    private val onRendererGone: (WebView) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase() ?: return false

        // Never intercept subframe (iframe) navigation.
        if (!request.isForMainFrame) return false

        return when (scheme) {
            "solana-wallet" -> {
                if (launchExternal(Intent(Intent.ACTION_VIEW, url))) {
                    view.evaluateJavascript("window.dispatchEvent(new Event('blur'))", null)
                }
                true
            }

            "intent" -> {
                handleIntentScheme(url.toString())
                true
            }

            "blob", "javascript", "about", "data" -> false

            "http", "https" -> {
                if (url.host.equals(scopeHost, ignoreCase = true)) {
                    false
                } else {
                    launchExternal(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                    true
                }
            }

            else -> {
                launchExternal(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                true
            }
        }
    }

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        onMainFrameFinished(view, url)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) onMainFrameError()
    }

    /**
     * Android may kill the WebView's renderer (e.g. low memory while a wallet app is in front).
     * Returning true keeps the app alive; MainActivity swaps in a fresh WebView and reloads.
     */
    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        Log.w(TAG, "Renderer gone (crashed=${detail.didCrash()}); rebuilding WebView")
        onRendererGone(view)
        return true
    }

    private fun handleIntentScheme(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.removeFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            if (!launchExternal(intent)) {
                val fallback = intent.getStringExtra("browser_fallback_url")?.toUri()
                val fallbackScheme = fallback?.scheme?.lowercase()
                if (fallback != null && (fallbackScheme == "http" || fallbackScheme == "https")) {
                    launchExternal(Intent(Intent.ACTION_VIEW, fallback).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
        } catch (_: Exception) {
            // Malformed intent URL: ignore.
        }
    }

    private fun launchExternal(intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "No app for scheme: ${intent.data?.scheme}")
            false
        }
    }

    private companion object {
        const val TAG = "Plonk"
    }
}
