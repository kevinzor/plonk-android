package land.plonk.app

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import land.plonk.app.bridge.AppInfoHandler
import land.plonk.app.bridge.BridgeHandler
import land.plonk.app.bridge.BridgeHost
import land.plonk.app.bridge.BundleHandler
import land.plonk.app.bridge.ExitHandler
import land.plonk.app.bridge.HapticsHandler
import land.plonk.app.bridge.KeepAwakeHandler
import land.plonk.app.bridge.NativeBridge
import land.plonk.app.bridge.NotifyHandler
import land.plonk.app.bridge.ShareHandler
import land.plonk.app.bridge.WalletHandler
import land.plonk.app.bundle.GameBundle
import land.plonk.app.ui.StatusScreen
import org.json.JSONObject

/**
 * Plonk for Android: a full-screen game WebView on https://play.plonk.land.
 *
 * Game-safety rules this activity enforces (the stock webshell template broke each one):
 * - no pull-to-refresh (a downward drag mid-fight must never reload the game);
 * - rotation, folds, keyboards and theme changes never recreate the activity (no reload);
 * - Android back closes the top game window first, and only a second press leaves (to the
 *   background, so the game stays warm);
 * - page zoom is off (the game has its own pinch zoom);
 * - if Android kills the WebView renderer (e.g. while a wallet app is in front) the app rebuilds
 *   the WebView and reloads instead of crashing;
 * - the player never sees a browser error page: loading and failures get the branded
 *   [StatusScreen], which retries by itself (see [LoadController]).
 *
 * Unchanged game files are served from the APK ([GameBundle]), so a cold start doesn't wait on
 * megabytes of code and art.
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var status: StatusScreen
    private lateinit var loads: LoadController
    private var webView: WebView? = null
    private lateinit var bridge: NativeBridge
    private lateinit var bundle: GameBundle
    private lateinit var startUrl: String
    private lateinit var scopeHost: String
    private lateinit var links: GameLinks
    private lateinit var offlineCopy: OfflineCopyWatch
    private lateinit var keepAwake: KeepAwakeHandler

    private var firstPaintDone = false
    private val createdAt = SystemClock.uptimeMillis()
    private lateinit var backCallback: OnBackPressedCallback
    private val armBack = Runnable { backCallback.isEnabled = true }

    /** When the renderer died recently (elapsedRealtime), oldest first. */
    private val rendererDeaths = ArrayDeque<Long>()

    /** The renderer died while the app was in the background: build the game again on return. */
    private var rebuildOnStart = false

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
        links = GameLinks(startUrl)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        WindowCompat.setDecorFitsSystemWindows(window, false)
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

        // Before the first load: the manifest check runs while the WebView spins up.
        bundle = GameBundle(this, origin = "https://$scopeHost")
        bundle.prefetch()
        bundle.interceptServiceWorkers()
        offlineCopy = OfflineCopyWatch(this, ::leaveOfflineCopy)
        lifecycle.addObserver(offlineCopy)

        bridge = NativeBridge(allowedOrigin = "https://$scopeHost", handlers = bridgeHandlers(BridgeHost(this)))
        // The screen stays on for the game, never for the error screen.
        loads.onShowingProblem = { keepAwake.paused = it }

        // Cold start from a link (App Link, plonk://, shortcut): the link is simply the first page.
        // A recreated activity (process restored) must not replay the link that first opened it.
        val link = if (savedInstanceState == null) links.fromIntent(intent) else null
        loads.loading()
        newWebView().loadUrl(link?.url?.toString() ?: startUrl)
        backCallback = onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    /** A link while the game is running (the activity is singleTask). See [GameLinks.deliver]. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = links.fromIntent(intent) ?: return
        val wv = webView
        if (wv == null || loads.isShowingProblem) {
            // Nothing in play to keep: show the loader and load the link as a fresh page.
            loads.loading()
            (wv ?: newWebView()).loadUrl(link.url.toString())
        } else {
            links.deliver(wv, link)
        }
    }

    override fun onStart() {
        super.onStart()
        if (rebuildOnStart && webView == null && !loads.isShowingProblem) {
            loads.loading()
            newWebView().loadUrl(startUrl)
        }
        rebuildOnStart = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        webView?.let(::destroyWebView)
        webView = null
        bridge.dispose()
        bundle.close()
        super.onDestroy()
    }

    /**
     * Every window.PlonkNative feature, one line each. Built in onCreate because handlers may
     * register Activity Result launchers. Order doesn't matter; two handlers can't share a type.
     */
    private fun bridgeHandlers(host: BridgeHost): List<BridgeHandler> =
        listOf(
            HapticsHandler(this),
            KeepAwakeHandler(host).also { keepAwake = it },
            ExitHandler(host),
            AppInfoHandler(),
            NotifyHandler(host),
            ShareHandler(host),
            WalletHandler(host),
            BundleHandler(bundle),
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
                    // WebView's own popup blocker stays on: only a tap opens a window.
                    javaScriptCanOpenWindowsAutomatically = false
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
                        onMainFrameFinished = { _, _ ->
                            loads.finished()
                            bundle.logPageSummary()
                            if (bundle.isOfflineCopy) offlineCopy.arm() else offlineCopy.disarm()
                        },
                        onMainFrameError = { httpStatus -> loads.failed(httpStatus) },
                        onRendererGone = { dead -> rebuildAfterRendererLoss(dead) },
                        interceptRequest = bundle::intercept,
                        onMainFrameStarted = bundle::onDocumentStarted,
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
        rebuildOnStart = false
        return wv
    }

    private fun destroyWebView(wv: WebView) {
        (wv.webChromeClient as? PlonkChromeClient)?.closePopups()
        root.removeView(wv)
        wv.destroy()
    }

    /**
     * The renderer died (see PlonkWebViewClient.onRenderProcessGone). Swap in a new WebView and
     * load the game, but:
     * - in the background, only drop the dead one, and load again when the player returns, so a
     *   low-memory kill behind a wallet app doesn't reload the whole game (and get killed again);
     * - after [CRASH_LIMIT] deaths within [CRASH_WINDOW_MS] (a bad deploy, a GPU driver crash),
     *   say so and retry on the loader's backoff instead of looping with an endless load bar.
     */
    private fun rebuildAfterRendererLoss(dead: WebView) {
        if (dead !== webView) return
        destroyWebView(dead)
        webView = null
        val now = SystemClock.elapsedRealtime()
        rendererDeaths.addLast(now)
        while (now - rendererDeaths.first() > CRASH_WINDOW_MS) rendererDeaths.removeFirst()
        when {
            rendererDeaths.size >= CRASH_LIMIT -> loads.crashed(rendererDeaths.size - CRASH_LIMIT + 1)
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> rebuildOnStart = true
            else -> {
                loads.loading()
                newWebView().loadUrl(startUrl)
            }
        }
    }

    /**
     * The network is back while the page runs from the offline snapshot. Tell the page; if it
     * doesn't take care of it (preventDefault), reload it so the live game takes over.
     */
    private fun leaveOfflineCopy() {
        val wv = webView ?: return
        PageEvents.dispatch(wv, JSONObject().put("t", "online").put("mode", "offline")) { handled ->
            if (handled || wv !== webView) return@dispatch
            loads.loading()
            wv.reload()
        }
    }

    /**
     * Retry after a failed load: reload the page that failed, or start the game again if nothing
     * loaded or the failed page isn't a game page (retrying it could never succeed).
     */
    private fun reloadGame() {
        val wv = webView ?: newWebView()
        val failed = wv.url?.toUri()
        if (failed == null || !PlonkWebViewClient.isInScope(failed, scopeHost)) wv.loadUrl(startUrl) else wv.reload()
    }

    /**
     * Back: let the game close its top window (window.plonkBack, returns true if it closed one).
     * Older game builds without plonkBack get a synthetic Escape, and count as "closed" if it
     * changed anything on screen.
     *
     * At the world root the first press only warns. For the next [EXIT_WINDOW_MS] this callback
     * steps aside, so the second press is the system's own back: Android plays the predictive
     * back-to-home animation and, on 12+, keeps the game warm in the background instead of
     * finishing it, so coming back resumes the session rather than cold-loading it.
     */
    private fun handleBack() {
        val wv = webView
        if (wv == null || loads.isShowingProblem) {
            moveTaskToBack(true)
            return
        }
        wv.evaluateJavascript(BACK_JS) { result ->
            if (result?.contains("closed") == true) return@evaluateJavascript
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            backCallback.isEnabled = false
            root.removeCallbacks(armBack)
            root.postDelayed(armBack, EXIT_WINDOW_MS)
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

        const val CRASH_LIMIT = 2
        const val CRASH_WINDOW_MS = 120_000L

        const val EXIT_WINDOW_MS = 2000L

        /**
         * 'closed' if the game closed something, else 'root'. The Escape fallback watches the DOM
         * while the game handles the key: a real attribute or child change means a window (or a
         * target) was closed. Writes that leave a value as it was don't count.
         */
        const val BACK_JS = """(function(){
  try { if (typeof window.plonkBack === 'function') return window.plonkBack() ? 'closed' : 'root'; } catch (e) {}
  var changed = false;
  try {
    var mo = new MutationObserver(function(){});
    mo.observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeOldValue: true });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true }));
    changed = mo.takeRecords().some(function (r) {
      return r.type !== 'attributes' || r.target.getAttribute(r.attributeName) !== r.oldValue;
    });
    mo.disconnect();
  } catch (e) {}
  return changed ? 'closed' : 'root';
})()"""
    }
}
