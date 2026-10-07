package land.plonk.app

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Page-chrome hooks: load progress, debug console, popups (window.open / target=_blank from a tap
 * go to the system browser, e.g. jup.ag) and <input type=file> (the skin painter's PNG upload).
 * Popup handling forked from Solana Mobile's webshell template (Apache-2.0).
 */
class PlonkChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onFileChooser: (ValueCallback<Array<Uri>>, FileChooserParams) -> Boolean,
    private val isDebug: Boolean,
) : WebChromeClient() {
    override fun onProgressChanged(
        view: WebView,
        newProgress: Int,
    ) {
        onProgress(newProgress)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        // Page console output can hold sensitive data: only log it in debug builds.
        if (!isDebug) return true
        val level =
            when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                else -> Log.INFO
            }
        Log.println(level, TAG, "${consoleMessage.message()} — ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = onFileChooser(filePathCallback, fileChooserParams)

    /** Popups still waiting for their URL. */
    private val popups = mutableSetOf<WebView>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * window.open / target=_blank. A throwaway WebView only captures the popup's URL, which is
     * handed to the system browser.
     *
     * Only a tap may take the player out of the game: without a user gesture (a timer, or a
     * script in an ad or widget iframe) the popup is refused. A popup that never navigates
     * (window.open('') whose follow-up failed) is destroyed after [POPUP_TIMEOUT_MS] rather than
     * kept alive with the activity, and a renderer crash in it never takes the app down.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        if (!isUserGesture) {
            if (isDebug) Log.w(TAG, "Popup without a tap refused")
            return false
        }
        val popup = WebView(view.context)
        popups += popup
        val close = Runnable { if (popups.remove(popup)) popup.destroy() }
        popup.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    openInBrowser(view, request.url)
                    main.post(close)
                    return true
                }

                // The popup shares the game's renderer. The default (false) would kill the app.
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    close.run()
                    return true
                }
            }
        popup.webChromeClient =
            object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = close.run()
            }
        main.postDelayed(close, POPUP_TIMEOUT_MS)
        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    /** Destroy popups that are still open. Call before the game WebView is destroyed. */
    fun closePopups() {
        main.removeCallbacksAndMessages(null)
        popups.toList().forEach { it.destroy() }
        popups.clear()
    }

    private fun openInBrowser(
        view: WebView,
        url: Uri,
    ) {
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        try {
            // Our own pages (the Privacy Policy and Terms links) would otherwise come straight back to
            // the app as an App Link and replace the game. See ExternalBrowser.
            view.context.startActivity(ExternalBrowser.intentFor(view.context, url))
        } catch (_: ActivityNotFoundException) {
            if (isDebug) Log.w(TAG, "No app for popup URL: $url")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't open popup URL", e)
        }
    }

    private companion object {
        const val TAG = "Plonk"

        /** How long a popup may sit without a URL before it is thrown away. */
        const val POPUP_TIMEOUT_MS = 10_000L
    }
}
