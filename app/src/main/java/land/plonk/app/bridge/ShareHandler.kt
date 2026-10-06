package land.plonk.app.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.chooser.ChooserResult
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject

/**
 * `{ t: 'share', text, url?, title? }`: the Android share sheet, for referral invites and brag
 * text ("I just caught a Golden Koi in Plonk").
 *
 * A web page can only use navigator.share, which a WebView doesn't implement, so without this the
 * game could only copy a link to the clipboard. The sheet shows the player's direct-share targets
 * (recent chats) and, with `title`, a preview headline.
 *
 * Replies once per request:
 * - `{ t: 'share', ok: true, app? }` when the player picks a target. `app` is the chosen app's
 *   package (e.g. org.telegram.messenger). On Android 15+ the sheet's own actions report
 *   `{ ok: true, via: 'copy' | 'edit' }` instead.
 * - `{ t: 'share', error: 'cancelled' }` when the sheet is closed without picking, or a newer
 *   share replaces this one.
 * - `error: 'bad_request'` (no text and no url), `'bad_url'` (url isn't http/https),
 *   `'unavailable'` (no share sheet on this device).
 *
 * How the outcome is known: the chooser fires our PendingIntent with the chosen target. If the
 * activity comes back to the front without that having happened, the player dismissed the sheet.
 */
class ShareHandler(
    private val host: BridgeHost,
) : BridgeHandler {
    override val types = setOf("share")

    private val context = host.activity.applicationContext
    private val chosenAction = "${context.packageName}.SHARE_TARGET_CHOSEN"
    private val main = Handler(Looper.getMainLooper())

    private var pending: Reply? = null
    private var pendingId = 0L
    private var leftForSheet = false

    private val chosenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) = onTargetChosen(intent)
        }

    private val lifecycleObserver =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (pending != null) leftForSheet = true
                Lifecycle.Event.ON_RESUME -> if (leftForSheet) onBackFromSheet()
                else -> Unit
            }
        }

    init {
        // Not exported: only our own PendingIntent (sent with this app's identity) can reach it.
        ContextCompat.registerReceiver(
            context,
            chosenReceiver,
            IntentFilter(chosenAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        host.activity.lifecycle.addObserver(lifecycleObserver)
    }

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) {
        val text = msg.text("text").take(MAX_TEXT)
        val url = msg.text("url").take(MAX_URL)
        val title = msg.text("title").take(MAX_TITLE)
        if (url.isNotEmpty() && !isWebUrl(url)) return reply.error("bad_url")
        if (text.isEmpty() && url.isEmpty()) return reply.error("bad_request", "text or url is required")

        pending?.error("cancelled")
        val id = ++pendingId
        pending = reply
        leftForSheet = false

        if (!host.startActivity(chooser(shareBody(text, url), title, id))) {
            pending = null
            reply.error("unavailable")
        }
    }

    override fun dispose() {
        main.removeCallbacksAndMessages(null)
        host.activity.lifecycle.removeObserver(lifecycleObserver)
        runCatching { context.unregisterReceiver(chosenReceiver) }
        pending = null
    }

    /** The text goes in one field; most apps ignore EXTRA_SUBJECT, so the url rides in the text. */
    private fun shareBody(
        text: String,
        url: String,
    ): String =
        when {
            url.isEmpty() || url in text -> text
            text.isEmpty() -> url
            else -> "$text\n$url"
        }

    private fun chooser(
        body: String,
        title: String,
        id: Long,
    ): Intent {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                if (title.isNotEmpty()) {
                    putExtra(Intent.EXTRA_TITLE, title) // the sheet's preview headline
                    putExtra(Intent.EXTRA_SUBJECT, title) // email subject
                }
            }
        // Mutable so the sheet can add the chosen target; explicit (our package) as Android 14 requires.
        val onChosen =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(chosenAction).setPackage(context.packageName).putExtra(EXTRA_SHARE_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        return Intent.createChooser(send, title.ifEmpty { null }, onChosen.intentSender)
    }

    private fun onTargetChosen(intent: Intent) {
        val reply = pending ?: return
        if (intent.getLongExtra(EXTRA_SHARE_ID, -1) != pendingId) return
        pending = null
        val body = JSONObject().put("ok", true)
        chosenPackage(intent)?.let { body.put("app", it) }
        sheetAction(intent)?.let { body.put("via", it) }
        reply.post(body)
    }

    /**
     * Back in front with no target chosen means the sheet was dismissed. The chooser fires its
     * callback just before it closes, so allow it a moment to land before calling it cancelled.
     */
    private fun onBackFromSheet() {
        leftForSheet = false
        val id = pendingId
        main.postDelayed({
            val reply = pending
            if (reply != null && pendingId == id) {
                pending = null
                reply.error("cancelled")
            }
        }, DISMISS_GRACE_MS)
    }

    private fun chosenPackage(intent: Intent): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            chooserResult(intent)?.selectedComponent?.let { return it.packageName }
        }
        return IntentCompat
            .getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            ?.packageName
    }

    /** Android 15+ also reports the sheet's built-in Copy and Edit actions. */
    private fun sheetAction(intent: Intent): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return when (chooserResult(intent)?.type) {
            ChooserResult.CHOOSER_RESULT_COPY -> "copy"
            ChooserResult.CHOOSER_RESULT_EDIT -> "edit"
            else -> null
        }
    }

    private fun chooserResult(intent: Intent): ChooserResult? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            intent.getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT, ChooserResult::class.java)
        } else {
            null
        }

    private companion object {
        const val EXTRA_SHARE_ID = "land.plonk.app.extra.SHARE_ID"
        const val DISMISS_GRACE_MS = 600L
        const val MAX_TEXT = 4000
        const val MAX_URL = 2000
        const val MAX_TITLE = 200

        fun isWebUrl(url: String): Boolean {
            val scheme = url.substringBefore(':', "").lowercase()
            return (scheme == "https" || scheme == "http") && url.startsWith("$scheme://", ignoreCase = true)
        }

        /** A trimmed string field; missing, null or non-string values read as empty. */
        fun JSONObject.text(key: String): String = (opt(key) as? String)?.trim().orEmpty()
    }
}
