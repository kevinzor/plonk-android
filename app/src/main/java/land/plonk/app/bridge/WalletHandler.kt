package land.plonk.app.bridge

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wallet apps on this phone, so the game can help a player who has none.
 *
 * Inside the app, "Connect wallet" fires a `solana-wallet:` link (Mobile Wallet Adapter). With no
 * wallet installed, Android has nowhere to send it and the tap does nothing, which looks like a
 * broken button. The page can ask first and show "No wallet app found, get one" instead.
 *
 * - `{ t: 'wallets', icons? }` replies `{ t: 'wallets', apps: [{ label, pkg, id?, icon? }], store }`.
 *   `apps` are the installed apps that answer MWA links (Seed Vault Wallet on a Seeker, Phantom,
 *   Solflare...), sorted by label. `id` is set for the wallets `getWallet` knows. With
 *   `icons: true` each app also gets a 96 px PNG data URL. `store` says where `getWallet` would
 *   send the player: `dappstore`, `play`, or null when neither store is installed.
 * - `{ t: 'getWallet', which: 'phantom' | 'solflare' }` opens that wallet's store listing and
 *   replies `{ t: 'getWallet', store }`. The Solana dApp Store comes first (it is what Seeker
 *   players use), then Google Play, then the Play page in the browser. Replies
 *   `{ error: 'unknown_wallet' }` or `{ error: 'unavailable' }` when it can't.
 *
 * Package lookups go through the manifest's `<queries>`, so Android 11+ package visibility only
 * reveals wallet apps and stores, never the rest of the player's apps.
 */
class WalletHandler(
    private val host: BridgeHost,
) : BridgeHandler {
    override val types = setOf(WALLETS, GET_WALLET)

    /** Labels and icons come from other apps' resources: load them off the UI thread. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) {
        when (reply.type) {
            WALLETS -> {
                val withIcons = msg.optBoolean("icons", false)
                worker.execute { if (reply.isOpen) reply.post(installedWallets(withIcons)) }
            }

            GET_WALLET -> openStoreListing(msg.optString("which"), reply)
        }
    }

    override fun dispose() {
        worker.shutdownNow()
    }

    private fun installedWallets(withIcons: Boolean): JSONObject {
        val pm = host.activity.packageManager
        val ownPackage = host.activity.packageName
        val apps =
            resolveAll(pm, Intent(Intent.ACTION_VIEW, MWA_PROBE))
                .filter { it.packageName != ownPackage }
                .distinctBy { it.packageName }
                .map { walletJson(pm, it, withIcons) }
                .sortedBy { it.optString("label").lowercase() }
        return JSONObject()
            .put("apps", JSONArray(apps))
            .put("store", availableStore(pm) ?: JSONObject.NULL)
    }

    private fun walletJson(
        pm: PackageManager,
        activity: ActivityInfo,
        withIcon: Boolean,
    ): JSONObject {
        // The app's name, not the activity's: a wallet's MWA activity is often called "Connect".
        val app = activity.applicationInfo
        val json =
            JSONObject()
                .put("label", app.loadLabel(pm).toString())
                .put("pkg", activity.packageName)
        STORE_IDS.entries.firstOrNull { it.value == activity.packageName }?.let { json.put("id", it.key) }
        if (withIcon) runCatching { json.put("icon", pngDataUrl(app.loadIcon(pm).toBitmap(ICON_PX, ICON_PX))) }
        return json
    }

    private fun availableStore(pm: PackageManager): String? =
        when {
            resolveAll(pm, storeIntent(DAPP_STORE_URI, "")).isNotEmpty() -> "dappstore"
            resolveAll(pm, storeIntent(PLAY_URI, "")).isNotEmpty() -> "play"
            else -> null
        }

    private fun openStoreListing(
        which: String,
        reply: Reply,
    ) {
        val pkg =
            STORE_IDS[which]
                ?: return reply.error("unknown_wallet", "which: ${STORE_IDS.keys.joinToString(" | ")}")
        val store =
            when {
                host.startActivity(storeIntent(DAPP_STORE_URI, pkg)) -> "dappstore"
                host.startActivity(storeIntent(PLAY_URI, pkg)) -> "play"
                host.startActivity(storeIntent(PLAY_WEB_URI, pkg)) -> "web"
                else -> return reply.error("unavailable")
            }
        reply.post(JSONObject().put("store", store))
    }

    private fun storeIntent(
        base: String,
        pkg: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW, (base + Uri.encode(pkg)).toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)

    private fun resolveAll(
        pm: PackageManager,
        intent: Intent,
    ): List<ActivityInfo> {
        val found =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        return found.mapNotNull { it.activityInfo }
    }

    private fun pngDataUrl(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val WALLETS = "wallets"
        const val GET_WALLET = "getWallet"
        const val ICON_PX = 96

        /** The association link the MWA JS client opens; wallets register for this path. */
        val MWA_PROBE: Uri = "solana-wallet:/v1/associate/local".toUri()

        /** Deep link to a dApp Store listing (docs.solanamobile.com, "Link to your dApp listing page"). */
        const val DAPP_STORE_URI = "solanadappstore://details?id="
        const val PLAY_URI = "market://details?id="
        const val PLAY_WEB_URI = "https://play.google.com/store/apps/details?id="

        /** Wallets the page may ask getWallet for, by the same package on both stores. */
        val STORE_IDS =
            mapOf(
                "phantom" to "app.phantom",
                "solflare" to "com.solflare.mobile",
            )
    }
}
