package land.plonk.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Page-chrome hooks: load progress, debug console, popups (window.open / target=_blank go to the
 * system browser, e.g. jup.ag) and <input type=file> (the skin painter's PNG upload).
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

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        // A throwaway WebView only captures the popup's URL, which is handed to the system browser.
        val popup = WebView(view.context)
        popup.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        try {
                            view.context.startActivity(
                                Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE),
                            )
                        } catch (_: ActivityNotFoundException) {
                            if (isDebug) Log.w(TAG, "No app for popup URL: ${request.url}")
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Can't open popup URL", e)
                        }
                    }
                    view.post { view.destroy() }
                    return true
                }
            }
        popup.webChromeClient =
            object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    window.destroy()
                }
            }
        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private companion object {
        const val TAG = "Plonk"
    }
}
