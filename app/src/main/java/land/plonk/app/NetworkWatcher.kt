package land.plonk.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Whether the phone has a usable network, and a nudge when it gets one back.
 *
 * Only registered while the error screen is up (see [LoadController]): the game itself copes
 * with its own socket dropping, so there is no reason to listen while it is running.
 *
 * "Back" means the default network gained internet, or became validated (a captive portal was
 * signed in to). The nudge waits a moment so DNS and routes are ready before the page reloads,
 * and is cancelled if the network drops again in that time (lifts, tunnels, flaky Wi-Fi).
 */
class NetworkWatcher(
    context: Context,
    private val onChange: () -> Unit,
    private val onBack: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val nudge = Runnable { onBack() }
    private var registered = false
    private var online = false
    private var validated = false

    /** True if the default network says it can reach the internet. Live even when not started. */
    val isOnline: Boolean get() = if (registered) online else currentlyOnline()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                val nowValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val cameBack = (hasInternet && !online) || (nowValidated && !validated)
                val changed = hasInternet != online
                online = hasInternet
                validated = nowValidated
                if (changed) onChange()
                if (cameBack) {
                    main.removeCallbacks(nudge)
                    main.postDelayed(nudge, SETTLE_MS)
                }
            }

            override fun onLost(network: Network) {
                main.removeCallbacks(nudge)
                val wasOnline = online
                online = currentlyOnline()
                validated = false
                if (wasOnline && !online) onChange()
            }
        }

    fun start() {
        if (registered || connectivity == null) return
        online = currentlyOnline()
        validated = currentlyValidated()
        try {
            connectivity.registerDefaultNetworkCallback(callback, main)
            registered = true
        } catch (e: RuntimeException) {
            // Android caps callbacks per app; without one the error screen still has Retry.
            Log.w(TAG, "Can't watch the network", e)
        }
    }

    fun stop() {
        main.removeCallbacks(nudge)
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun capabilities(): NetworkCapabilities? = connectivity?.run { getNetworkCapabilities(activeNetwork) }

    private fun currentlyOnline() = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun currentlyValidated() = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private companion object {
        const val TAG = "Plonk"
        const val SETTLE_MS = 700L
    }
}
