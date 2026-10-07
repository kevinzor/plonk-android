package land.plonk.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri

/**
 * Opens a web page in the system browser, never in this app.
 *
 * play.plonk.land is a verified App Link for every path (and plonk.land for its root), so a plain
 * ACTION_VIEW for one of our own pages, such as the Privacy Policy, resolves straight back to
 * MainActivity. That would load the page in the game WebView and drop the player's session. When
 * the plain intent could land on this app, [intentFor] pins it to the default browser instead (or
 * the first browser if no default is set, or whatever app is the APP_BROWSER as a last resort).
 * Links to other sites are left alone, so their own apps can still open them.
 */
internal object ExternalBrowser {
    /** Host-less https: every browser takes it and no App Link can claim it. */
    private val PROBE: Uri = "https://".toUri()

    fun intentFor(
        context: Context,
        url: Uri,
    ): Intent {
        val view = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        val own = context.packageName
        if (!couldOpenIn(pm, view, own)) return view
        val browser = browserPackage(pm, own)
        if (browser != null) {
            view.setPackage(browser)
        } else {
            view.selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        }
        return view
    }

    /** True if [view] could resolve to this app. When the lookup fails, assume it could. */
    @Suppress("DEPRECATION") // the Int-flags overload is the one that also runs on minSdk 28
    private fun couldOpenIn(
        pm: PackageManager,
        view: Intent,
        own: String,
    ): Boolean =
        runCatching { pm.queryIntentActivities(view, 0).any { it.activityInfo?.packageName == own } }
            .getOrDefault(true)

    /** The default browser, else the first installed one; never this app or the system chooser. */
    @Suppress("DEPRECATION")
    private fun browserPackage(
        pm: PackageManager,
        own: String,
    ): String? =
        runCatching {
            val probe = Intent(Intent.ACTION_VIEW, PROBE).addCategory(Intent.CATEGORY_BROWSABLE)
            val browsers =
                pm
                    .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapNotNull { it.activityInfo?.packageName }
                    .filter { it != own }
                    .distinct()
            val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            default?.takeIf { it in browsers } ?: browsers.firstOrNull()
        }.getOrNull()
}
