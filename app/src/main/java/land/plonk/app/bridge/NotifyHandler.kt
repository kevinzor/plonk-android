package land.plonk.app.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import land.plonk.app.R
import org.json.JSONObject

/**
 * Game alerts (boss spawns, payouts, party and trade invites, whispers) as Android notifications.
 *
 * The page raises these, so they only cover the short time after the player switches away
 * while the page still runs: a quick trip to the wallet, a reply in a chat app. While the game is
 * on screen it shows events itself; once it is not, it hands them to this handler and they land
 * in the notification shade instead of being missed. The app decides what "on screen" means (the
 * activity is at least STARTED), so a notification can never pop over the game the player is
 * looking at.
 *
 * What this can't do: alerts long after the player left. Nothing keeps the process alive in the
 * background, so on Android 14+ (every Seeker) the cached-app freezer stops the app and its
 * WebView renderer within seconds of leaving, and Chromium throttles a hidden page before that.
 * The socket stalls and no more `notify` messages arrive. Real "while away" alerts need the server
 * to keep them (e.g. a `/app/alerts?since=` feed polled by a WorkManager job, or push); see
 * docs/APP_BRIDGE.md.
 *
 * Messages:
 * - `{ t: 'notify', title, body, tag?, kind?, ttl? }` replies `{ shown }`, plus `reason`
 *   (foreground, permission, disabled or muted) when it was not shown. kind = boss | payout |
 *   invite (or party, trade) | whisper; anything else is "other". Alerts with the same tag replace
 *   each other. ttl (seconds) overrides how long the alert stays; boss spawns and invites expire
 *   by themselves.
 * - `{ t: 'notifyPermission' }` asks for Android 13's notification permission (only ever when the
 *   page asks, e.g. from an "Alert me" toggle) and replies `{ granted, enabled, permission }`.
 * - `{ t: 'notifyState' }` replies `{ enabled, permission, kinds: { boss: true, ... } }` without
 *   prompting. permission is granted, default (can still ask) or denied (Android will not show
 *   the prompt again; send the player to settings).
 * - `{ t: 'notifySettings', kind? }` opens the system settings for these alerts (or for one kind)
 *   and replies `{ opened }`.
 *
 * Coming back to the game clears the shade: whatever was in it, the game is now showing.
 */
class NotifyHandler(
    private val host: BridgeHost,
) : BridgeHandler {
    override val types = setOf(NOTIFY, PERMISSION, STATE, SETTINGS)

    private val activity = host.activity
    private val alerts = GameAlerts(activity.applicationContext, activity.javaClass).apply { createChannels() }
    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val clearOnReturn =
        LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) alerts.clearAll() }

    init {
        activity.lifecycle.addObserver(clearOnReturn)
    }

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) {
        when (reply.type) {
            NOTIFY -> notify(msg, reply)
            PERMISSION -> requestPermission(reply)
            STATE -> reply.post(state())
            SETTINGS -> reply.post(JSONObject().put("opened", openSettings(msg.text("kind").ifEmpty { null })))
        }
    }

    override fun dispose() {
        activity.lifecycle.removeObserver(clearOnReturn)
    }

    private fun notify(
        msg: JSONObject,
        reply: Reply,
    ) {
        val alert = parse(msg) ?: return reply.error("bad_request", "notify needs a title or a body")
        val skipped =
            when {
                isForeground() -> "foreground"
                !alerts.permissionGranted() -> "permission"
                !alerts.enabled() -> "disabled"
                alerts.isMuted(alert.kind) -> "muted"
                else -> null
            }
        if (skipped == null) alerts.post(alert)
        val body = JSONObject().put("shown", skipped == null)
        if (skipped != null) body.put("reason", skipped)
        reply.post(body)
    }

    private fun parse(msg: JSONObject): Alert? {
        val title = msg.text("title").take(MAX_TITLE)
        val body = msg.text("body").take(MAX_BODY)
        if (title.isEmpty() && body.isEmpty()) return null
        val kind = AlertKind.of(msg.text("kind").lowercase())
        val ttlMs =
            if (msg.has("ttl")) {
                (msg.optDouble("ttl", 0.0) * 1000).toLong().coerceIn(0, MAX_TTL_MS)
            } else {
                kind.ttlMs
            }
        return Alert(
            kind = kind,
            title = title.ifEmpty { activity.getString(R.string.app_name) },
            body = body,
            tag = msg.text("tag").take(MAX_TAG).ifEmpty { null },
            ttlMs = ttlMs,
        )
    }

    private fun requestPermission(reply: Reply) {
        if (alerts.permissionGranted()) {
            reply.post(permissionReply())
            return
        }
        prefs.edit { putBoolean(ASKED, true) }
        host.requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) { reply.post(permissionReply()) }
    }

    private fun permissionReply(): JSONObject =
        JSONObject()
            .put("granted", alerts.permissionGranted())
            .put("enabled", alerts.enabled())
            .put("permission", permissionState())

    private fun state(): JSONObject {
        val kinds = JSONObject()
        AlertKind.entries.forEach { kinds.put(it.key, !alerts.isMuted(it)) }
        return JSONObject()
            .put("enabled", alerts.enabled())
            .put("permission", permissionState())
            .put("kinds", kinds)
    }

    /**
     * granted, default or denied, named like the web's Notification.permission so the page can
     * share code with the browser build. Android has no direct "denied for good" check: after the
     * prompt has been answered, no rationale means the system will not show it again.
     */
    private fun permissionState(): String =
        when {
            alerts.permissionGranted() -> "granted"
            !prefs.getBoolean(ASKED, false) -> "default"
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> "default"
            else -> "denied"
        }

    private fun openSettings(kindKey: String?): Boolean {
        val intent =
            if (kindKey == null) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AlertKind.of(kindKey).channelId)
            }
        return host.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }

    /** A string field, trimmed; "" when missing or null (optString would turn null into "null"). */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name).trim()

    private fun isForeground(): Boolean = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private companion object {
        const val NOTIFY = "notify"
        const val PERMISSION = "notifyPermission"
        const val STATE = "notifyState"
        const val SETTINGS = "notifySettings"

        const val PREFS = "plonk_alerts"
        const val ASKED = "permission_asked"

        const val MAX_TITLE = 80
        const val MAX_BODY = 600
        const val MAX_TAG = 64
        const val MAX_TTL_MS = 24 * 60 * 60_000L
    }
}
