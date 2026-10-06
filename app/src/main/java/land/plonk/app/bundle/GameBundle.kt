package land.plonk.app.bundle

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import land.plonk.app.BuildConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The game's code and art ship inside the APK, and the app serves them from there while they
 * still match what the live server is serving.
 *
 * The WebView keeps the real origin (https://play.plonk.land): cookies, storage, the wallet
 * adapter and the PlonkNative origin lock all behave exactly as on the website. Only the bytes of
 * unchanged static files come from the APK instead of the network, so a cold start skips
 * megabytes of JavaScript, models and icons, and works on weak signal.
 *
 * How each page load is planned (decided when the game shell `/` is requested, so the shell and
 * every file it loads come from the same plan):
 * - **live**: the server's manifest (`GET /app/manifest`) answered. A bundled file is used only
 *   if its sha256 equals the server's; changed, new or unknown files come from the network. A
 *   deploy is therefore live in the app on the next page load, with no app update.
 * - **offline**: the phone has no network. The whole bundle is served as one consistent
 *   snapshot, so the game opens, shows its own reconnecting screen and reloads when the
 *   network is back.
 * - **network**: online but no manifest (the endpoint is missing or slow). Everything goes to
 *   the network, exactly like the website. Old bundled code is never mixed with a newer page.
 *
 * Requests that are not GETs for a known static path (`/status`, `/auth`, `/api`, POSTs,
 * websockets) are never touched. The request path does only map lookups; hashes are compared
 * once per manifest, and nothing is hashed on the phone at all.
 *
 * [intercept] runs on WebView's network threads, never on the UI thread.
 */
class GameBundle(
    context: Context,
    origin: String,
) {
    enum class Mode(
        val wire: String,
    ) {
        NETWORK("network"),
        LIVE("live"),
        OFFLINE("offline"),
    }

    /** One page load's rules: which bundled keys may be served. */
    private class Plan(
        val mode: Mode,
        val build: String?,
        private val keys: Set<String>,
    ) {
        fun allows(key: String) = mode == Mode.OFFLINE || key in keys
    }

    private val app = context.applicationContext
    private val host = origin.toUri().host.orEmpty()
    private val manifestSource =
        LiveManifest(
            url = "$origin/app/manifest",
            cacheDir = File(app.cacheDir, "bundle"),
            userAgent = "PlonkApp/${BuildConfig.VERSION_NAME}",
        )
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "plonk-bundle").apply { isDaemon = true } }

    private val indexLazy = lazy { BundleIndex.load(app.assets) }
    private val index by indexLazy

    private val lock = Any()
    private var inFlight: Future<LiveManifest.Result>? = null // guarded by lock
    private var livePlan: Plan? = null // guarded by lock
    private var livePlanAt = 0L // guarded by lock
    private var lastResult: LiveManifest.Result? = null // guarded by lock

    @Volatile private var plan = Plan(Mode.NETWORK, null, emptySet())

    @Volatile private var closed = false

    private val served = AtomicInteger()
    private val servedBytes = AtomicLong()
    private val missed = AtomicInteger()

    /** Read the bundle and ask the server for its manifest, in the background. Call at launch. */
    fun prefetch() {
        worker.execute { if (!index.isEmpty) startManifestFetch() }
    }

    /**
     * Also answer requests made by the game's service worker. Its navigation fetches (the game
     * shell) bypass the WebViewClient, so without this the shell would never come from the APK.
     */
    fun interceptServiceWorkers() {
        ServiceWorkerController.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = intercept(request)
            },
        )
    }

    /** The response for [request] from the APK, or null to let it go to the network unchanged. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (closed || !request.method.equals("GET", ignoreCase = true)) return null
        val url = request.url
        if (url.scheme != "https" || !url.host.equals(host, ignoreCase = true)) return null
        if (url.port != -1 && url.port != 443) return null
        val key = BundlePaths.keyFor(url.path ?: return null) ?: return null
        val idx = index
        if (idx.isEmpty) return null
        if (key == BundlePaths.SHELL) plan = planPageLoad(idx)

        val current = plan
        val response = if (current.allows(key)) BundleResponse.build(app.assets, idx, key, rangeOf(request)) else null
        if (response == null) {
            missed.incrementAndGet()
            if (BuildConfig.DEBUG && current.mode != Mode.NETWORK) Log.v(TAG, "Bundle miss (network): $key")
        } else {
            served.incrementAndGet()
            servedBytes.addAndGet(idx[key]?.size ?: 0)
        }
        return response
    }

    /** State for the page and for debugging (`{ t: 'bundle' }` on the bridge). */
    fun status(): Status {
        val idx = if (indexLazy.isInitialized()) index else null
        val p = plan
        return Status(
            mode = p.mode.wire,
            bundleBuild = idx?.build,
            liveBuild = p.build,
            bundled = idx?.size ?: 0,
            served = served.get(),
            servedBytes = servedBytes.get(),
            network = missed.get(),
        )
    }

    class Status(
        val mode: String,
        val bundleBuild: String?,
        val liveBuild: String?,
        val bundled: Int,
        val served: Int,
        val servedBytes: Long,
        val network: Int,
    )

    /** Debug builds: one log line per page load with how much came from the APK. */
    fun logPageSummary() {
        if (!BuildConfig.DEBUG) return
        val s = status()
        if (s.bundled == 0) {
            Log.d(TAG, "Bundle: empty in this build, every file streamed")
            return
        }
        Log.d(
            TAG,
            "Bundle [${s.mode}${s.liveBuild?.let { " $it" }.orEmpty()}]: ${s.served} hits " +
                "(${s.servedBytes / 1024} KB from the APK), ${s.network} misses to the network",
        )
    }

    fun close() {
        closed = true
        worker.shutdownNow()
    }

    private fun planPageLoad(idx: BundleIndex): Plan {
        served.set(0)
        servedBytes.set(0)
        missed.set(0)
        val next =
            when {
                !isOnline() -> Plan(Mode.OFFLINE, idx.build, emptySet())
                else -> awaitLivePlan(idx) ?: Plan(Mode.NETWORK, null, emptySet())
            }
        if (BuildConfig.DEBUG) Log.d(TAG, "Bundle plan: ${describe(next, idx)}")
        return next
    }

    /**
     * A plan from a manifest at most [LIVE_MAX_AGE_MS] old, fetching one if needed. The shell
     * request waits for it, but never longer than [SHELL_WAIT_MS]; a slower answer still lands
     * and is used from the next page load.
     */
    private fun awaitLivePlan(idx: BundleIndex): Plan? {
        val pending =
            synchronized(lock) {
                val cached = livePlan
                if (cached != null && SystemClock.elapsedRealtime() - livePlanAt < LIVE_MAX_AGE_MS) return cached
                if (lastResult is LiveManifest.Result.Missing && inFlight == null &&
                    SystemClock.elapsedRealtime() - livePlanAt < MISSING_RETRY_MS
                ) {
                    return null
                }
                startManifestFetch()
            } ?: return null
        val result =
            try {
                pending.get(SHELL_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                return null // timed out, or the app is closing
            }
        return (result as? LiveManifest.Result.Fresh)?.let { planFor(idx, it) }
    }

    /** Start a manifest fetch unless one is running. Returns the running fetch. */
    private fun startManifestFetch(): Future<LiveManifest.Result>? =
        synchronized(lock) {
            inFlight?.let { return it }
            if (closed) return null
            worker.submit(Callable { onManifest(manifestSource.fetch()) }).also { inFlight = it }
        }

    private fun onManifest(result: LiveManifest.Result): LiveManifest.Result {
        val newPlan = (result as? LiveManifest.Result.Fresh)?.let { planFor(index, it) }
        synchronized(lock) {
            inFlight = null
            lastResult = result
            livePlanAt = SystemClock.elapsedRealtime()
            livePlan = newPlan
        }
        if (BuildConfig.DEBUG && result !is LiveManifest.Result.Fresh) {
            val why = if (result is LiveManifest.Result.Unreachable) result.reason else "no /app/manifest on the server"
            Log.d(TAG, "Bundle: live manifest unavailable ($why)")
        }
        return result
    }

    /** The one place hashes are compared: once per manifest, never per request. */
    private fun planFor(
        idx: BundleIndex,
        live: LiveManifest.Result.Fresh,
    ): Plan {
        val current = HashSet<String>(idx.size * 2)
        for (key in idx.keys) {
            if (live.hashes[key] == idx[key]?.sha256) current += key
        }
        return Plan(Mode.LIVE, live.build, current)
    }

    private fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun describe(
        p: Plan,
        idx: BundleIndex,
    ): String =
        when (p.mode) {
            Mode.LIVE -> "live (server ${p.build ?: "?"}): ${idx.keys.count(p::allows)} of ${idx.size} bundled files current"
            Mode.OFFLINE -> "offline: serving the bundle snapshot (${idx.build ?: "?"}, ${idx.size} files)"
            Mode.NETWORK -> "network: no live manifest, streaming everything"
        }

    private fun rangeOf(request: WebResourceRequest): String? =
        request.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value

    private companion object {
        const val TAG = "Plonk"

        /** How long the game shell waits for the manifest before loading from the network. */
        const val SHELL_WAIT_MS = 2500L

        /** A manifest this fresh is reused without asking again (launch prefetch, quick reloads). */
        const val LIVE_MAX_AGE_MS = 15_000L

        /** After a 404 (server without the endpoint), don't ask again for this long. */
        const val MISSING_RETRY_MS = 5 * 60_000L
    }
}
