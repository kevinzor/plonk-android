package land.plonk.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import land.plonk.app.bridge.AppInfoHandler
import land.plonk.app.bridge.BridgeHandler
import land.plonk.app.bridge.BridgeHost
import land.plonk.app.bridge.ExitHandler
import land.plonk.app.bridge.HapticsHandler
import land.plonk.app.bridge.KeepAwakeHandler
import land.plonk.app.bridge.NativeBridge
import land.plonk.app.bridge.WalletHandler
import land.plonk.app.ui.StatusScreen

/**
 * Plonk for Android: a full-screen game WebView on https://play.plonk.land.
 *
 * Game-safety rules this activity enforces (the stock webshell template broke each one):
 * - no pull-to-refresh (a downward drag mid-fight must never reload the game);
 * - rotation, folds, keyboards and theme changes never recreate the activity (no reload);
 * - Android back closes the top game window first, and only a second press leaves;
 * - page zoom is off (the game has its own pinch zoom);
 * - if Android kills the WebView renderer (e.g. while a wallet app is in front) the app rebuilds
 *   the WebView and reloads instead of crashing;
 * - the player never sees a browser error page: loading and failures get the branded
 *   [StatusScreen], which retries by itself (see [LoadController]).
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var status: StatusScreen
    private lateinit var loads: LoadController
    private var webView: WebView? = null
    private lateinit var bridge: NativeBridge
    private lateinit var startUrl: String
    private lateinit var scopeHost: String

    private var firstPaintDone = false
    private val createdAt = SystemClock.uptimeMillis()
    private var lastBackAt = 0L

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            pendingFileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            pendingFileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash for a fast load, then hand over to the branded loader, which shows progress.
        splash.setKeepOnScreenCondition { !firstPaintDone && SystemClock.uptimeMillis() - createdAt < SPLASH_MAX_MS }
        splash.setOnExitAnimationListener { provider ->
            provider.view
                .animate()
                .alpha(0f)
                .setDuration(SPLASH_FADE_MS)
                .withEndAction { provider.remove() }
                .start()
        }

        startUrl = BuildConfig.SOLANA_MOBILE_URL
        scopeHost = startUrl.toUri().host.orEmpty()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes =
            window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        // The game draws edge to edge, but never under the camera cutout, and shrinks above the
        // keyboard so the chat box stays visible.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(cut.left, cut.top, cut.right, maxOf(cut.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        status = StatusScreen(this).also { root.addView(it) }
        loads = LoadController(status, this, ::reloadGame).apply { onSettled = { firstPaintDone = true } }
        lifecycle.addObserver(loads)
        hideSystemBars()

        bridge = NativeBridge(allowedOrigin = "https://$scopeHost", handlers = bridgeHandlers(BridgeHost(this)))

        loads.loading()
        newWebView().loadUrl(startUrl)
        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        bridge.dispose()
        super.onDestroy()
    }

    /**
     * Every window.PlonkNative feature, one line each. Built in onCreate because handlers may
     * register Activity Result launchers. Order doesn't matter; two handlers can't share a type.
     */
    private fun bridgeHandlers(host: BridgeHost): List<BridgeHandler> =
        listOf(
            HapticsHandler(this),
            KeepAwakeHandler(host),
            ExitHandler(host),
            AppInfoHandler(),
            WalletHandler(host),
        )

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val wv =
            WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(BG)
                overScrollMode = View.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadWithOverviewMode = false
                    useWideViewPort = false
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)
                    offscreenPreRaster = true
                    allowFileAccess = false
                    allowContentAccess = false
                    // "Solana Mobile Web Shell" lets wallet libraries treat this WebView as a
                    // supported MWA host; "PlonkApp/x" lets the game turn on app-only features.
                    userAgentString = "${userAgentString.trim()} Solana Mobile Web Shell PlonkApp/${BuildConfig.VERSION_NAME}"
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient =
                    PlonkChromeClient(
                        onProgress = { p -> loads.progress(p) },
                        onFileChooser = { cb, params -> openFileChooser(cb, params) },
                        isDebug = BuildConfig.DEBUG,
                    )
                webViewClient =
                    PlonkWebViewClient(
                        context = this@MainActivity,
                        scopeHost = scopeHost,
                        onMainFrameFinished = { _, _ -> loads.finished() },
                        onMainFrameError = { httpStatus -> loads.failed(httpStatus) },
                        onRendererGone = { dead -> rebuildAfterRendererLoss(dead) },
                    )
                setDownloadListener { url, _, _, _, _ ->
                    val scheme = url.toUri().scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        runCatching {
                            startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
                                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE),
                            )
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Downloads aren't supported in the app yet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        bridge.attach(wv)
        root.addView(wv, 0)
        webView = wv
        return wv
    }

    private fun rebuildAfterRendererLoss(dead: WebView) {
        if (dead !== webView) return
        root.removeView(dead)
        dead.destroy()
        webView = null
        loads.loading()
        newWebView().loadUrl(startUrl)
    }

    /** Retry after a failed load: reload the page that failed, or the game if nothing loaded. */
    private fun reloadGame() {
        val wv = webView ?: newWebView()
        if (wv.url.isNullOrEmpty()) wv.loadUrl(startUrl) else wv.reload()
    }

    /**
     * Back: let the game close its top window (window.plonkBack, returns true if it closed one).
     * Older game builds without plonkBack get a synthetic Escape, which does the same job.
     * At the world root, a second back within 2 s leaves the app.
     */
    private fun handleBack() {
        val wv = webView ?: return finish()
        if (loads.isShowingProblem) return finish()
        wv.evaluateJavascript(BACK_JS) { result ->
            if (result?.contains("closed") == true) return@evaluateJavascript
            val now = SystemClock.uptimeMillis()
            if (now - lastBackAt < 2000) {
                finish()
            } else {
                lastBackAt = now
                Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFileChooser(
        cb: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = cb
        val type = params.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "*/*"
        return try {
            pickFile.launch(type)
            true
        } catch (_: Exception) {
            pendingFileCallback = null
            false
        }
    }

    private companion object {
        const val BG = 0xFF06070C.toInt()
        const val SPLASH_MAX_MS = 1500L
        const val SPLASH_FADE_MS = 200L

        const val BACK_JS = """(function(){
  try { if (typeof window.plonkBack === 'function') return window.plonkBack() ? 'closed' : 'root'; } catch (e) {}
  try { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); } catch (e) {}
  return 'escape';
})()"""
    }
}
