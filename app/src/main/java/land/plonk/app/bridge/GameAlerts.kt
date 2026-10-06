package land.plonk.app.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import land.plonk.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * The kinds of game alert. Each one is its own Android notification channel, so a player can mute
 * whispers but keep boss spawns from the system settings, without the game needing its own
 * per-kind switches.
 *
 * [ttlMs] is how long an alert stays in the shade by default: a boss spawn or an invite is
 * useless 20 minutes later, a payout or a whisper is not (0 = until tapped or the game opens).
 * [privateText] replaces the real text on a locked screen when the player hides sensitive content
 * there; wallet amounts and private messages should not show on a phone lying on a desk.
 */
internal enum class AlertKind(
    val keys: Set<String>,
    val channelId: String,
    @param:StringRes val channelName: Int,
    @param:StringRes val channelDescription: Int,
    val importance: Int,
    val category: String,
    val ttlMs: Long,
    @param:StringRes val privateText: Int? = null,
) {
    BOSS(
        setOf("boss"),
        "alerts.boss",
        R.string.alerts_boss,
        R.string.alerts_boss_desc,
        NotificationManagerCompat.IMPORTANCE_HIGH,
        NotificationCompat.CATEGORY_EVENT,
        ttlMs = 20 * MINUTE,
    ),
    PAYOUT(
        setOf("payout"),
        "alerts.payout",
        R.string.alerts_payout,
        R.string.alerts_payout_desc,
        NotificationManagerCompat.IMPORTANCE_DEFAULT,
        NotificationCompat.CATEGORY_STATUS,
        ttlMs = 0,
        privateText = R.string.alerts_private_payout,
    ),
    INVITE(
        setOf("invite", "party", "trade"),
        "alerts.invite",
        R.string.alerts_invite,
        R.string.alerts_invite_desc,
        NotificationManagerCompat.IMPORTANCE_HIGH,
        NotificationCompat.CATEGORY_SOCIAL,
        ttlMs = 5 * MINUTE,
    ),
    WHISPER(
        setOf("whisper"),
        "alerts.whisper",
        R.string.alerts_whisper,
        R.string.alerts_whisper_desc,
        NotificationManagerCompat.IMPORTANCE_HIGH,
        NotificationCompat.CATEGORY_MESSAGE,
        ttlMs = 0,
        privateText = R.string.alerts_private_whisper,
    ),
    OTHER(
        setOf("other"),
        "alerts.other",
        R.string.alerts_other,
        R.string.alerts_other_desc,
        NotificationManagerCompat.IMPORTANCE_DEFAULT,
        NotificationCompat.CATEGORY_EVENT,
        ttlMs = 0,
    ),
    ;

    /** The name the page uses for this kind (the first of [keys]). */
    val key: String get() = keys.first()

    companion object {
        /** The kind for the page's `kind` string. Unknown or missing kinds become [OTHER]. */
        fun of(key: String?): AlertKind = entries.firstOrNull { key in it.keys } ?: OTHER
    }
}

/** One alert, already cleaned up by the bridge handler. */
internal class Alert(
    val kind: AlertKind,
    val title: String,
    val body: String,
    val tag: String?,
    val ttlMs: Long,
)

/**
 * Posts game alerts to the notification shade, and owns the "Game alerts" channel set.
 *
 * Alerts with the same tag replace each other (two "Boss spawned" for the same boss are one
 * notification). Untagged alerts each get their own. Tapping one brings the existing game back
 * exactly as the launcher icon would: the activity is singleTask, so this never starts a second
 * copy of the game or reloads it.
 */
internal class GameAlerts(
    private val context: Context,
    private val gameActivity: Class<out Activity>,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val nextId = AtomicInteger(FIRST_UNTAGGED_ID)
    private val gold = ContextCompat.getColor(context, R.color.plonk_alert_gold)

    /** Create (or rename, after an update) the channel group and its channels. Cheap to repeat. */
    fun createChannels() {
        manager.createNotificationChannelGroup(
            NotificationChannelGroupCompat
                .Builder(GROUP_ID)
                .setName(context.getString(R.string.alerts_group))
                .setDescription(context.getString(R.string.alerts_group_desc))
                .build(),
        )
        manager.createNotificationChannelsCompat(
            AlertKind.entries.map { kind ->
                NotificationChannelCompat
                    .Builder(kind.channelId, kind.importance)
                    .setName(context.getString(kind.channelName))
                    .setDescription(context.getString(kind.channelDescription))
                    .setGroup(GROUP_ID)
                    .setLightColor(gold)
                    .setLightsEnabled(true)
                    .build()
            },
        )
    }

    /** Android 13+ asks the player at runtime; older versions grant it at install. */
    fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the app may post at all: permission granted and the app's master switch on. */
    fun enabled(): Boolean = permissionGranted() && manager.areNotificationsEnabled()

    /** True when the player turned this kind (or the whole "Game alerts" group) off in settings. */
    fun isMuted(kind: AlertKind): Boolean {
        val channel = manager.getNotificationChannelCompat(kind.channelId)
        if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) return true
        return manager.getNotificationChannelGroupCompat(GROUP_ID)?.isBlocked == true
    }

    /** Show [alert]. Callers check [enabled] first; this quietly does nothing without permission. */
    @SuppressLint("MissingPermission") // checked on the line below
    fun post(alert: Alert) {
        if (!permissionGranted()) return
        val notification = build(alert)
        if (alert.tag != null) {
            manager.notify(TAG_PREFIX + alert.tag, TAGGED_ID, notification)
        } else {
            manager.notify(nextId.getAndIncrement(), notification)
        }
    }

    /** Remove every alert this app has posted. */
    fun clearAll() = manager.cancelAll()

    private fun build(alert: Alert): android.app.Notification {
        val kind = alert.kind
        val builder =
            NotificationCompat
                .Builder(context, kind.channelId)
                .setSmallIcon(R.drawable.ic_stat_plonk)
                .setColor(gold)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setCategory(kind.category)
                .setContentIntent(openGame())
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
        // Long whispers and payout breakdowns expand instead of being cut at one line.
        if (alert.body.length > ONE_LINE) builder.setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
        if (alert.ttlMs > 0) builder.setTimeoutAfter(alert.ttlMs)
        val privateText = kind.privateText
        if (privateText == null) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat
                        .Builder(context, kind.channelId)
                        .setSmallIcon(R.drawable.ic_stat_plonk)
                        .setColor(gold)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(privateText))
                        .build(),
                )
        }
        return builder.build()
    }

    /** Same intent as the launcher icon, so a tap resumes the running game instead of reloading it. */
    private fun openGame(): PendingIntent {
        val intent =
            Intent(context, gameActivity)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val GROUP_ID = "alerts"
        private const val TAG_PREFIX = "alert:"
        private const val TAGGED_ID = 1
        private const val FIRST_UNTAGGED_ID = 1000
        private const val ONE_LINE = 40
    }
}

private const val MINUTE = 60_000L
