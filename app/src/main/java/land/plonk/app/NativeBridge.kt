package land.plonk.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * `window.PlonkNative` — a small message bridge the game page can use when it runs inside the app.
 *
 * Only the main frame of [allowedOrigin] can reach it (WebViewCompat.addWebMessageListener with an
 * origin allow-list), so ads, iframes or other sites can never vibrate the phone or close the app.
 *
 * Page -> app:  PlonkNative.postMessage(JSON.stringify({ t: 'haptic', k: 'hit' }))
 *   t: 'haptic'  k: tick | tap | hit | heavy | success | error
 *   t: 'exit'    close the app (used by the back button at the world root)
 *   t: 'awake'   on: true/false — keep the screen on (default on)
 *   t: 'info'    replies { t:'info', version, code, sdk, model, seeker }
 */
class NativeBridge(
    context: Context,
    private val allowedOrigin: String,
    private val onExit: () -> Unit,
    private val onKeepAwake: (Boolean) -> Unit,
) {
    private val haptics = Haptics(context)

    fun attach(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, "PlonkNative", setOf(allowedOrigin)) {
            _: WebView,
            message: WebMessageCompat,
            _: android.net.Uri,
            isMainFrame: Boolean,
            reply: JavaScriptReplyProxy,
            ->
            if (isMainFrame) handle(message.data, reply)
        }
    }

    private fun handle(
        raw: String?,
        reply: JavaScriptReplyProxy,
    ) {
        val msg = runCatching { JSONObject(raw ?: return) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "haptic" -> haptics.play(msg.optString("k", "tap"))
            "exit" -> onExit()
            "awake" -> onKeepAwake(msg.optBoolean("on", true))
            "info" ->
                reply.postMessage(
                    JSONObject()
                        .put("t", "info")
                        .put("version", BuildConfig.VERSION_NAME)
                        .put("code", BuildConfig.VERSION_CODE)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("model", Build.MODEL)
                        .put("seeker", isSeekerDevice())
                        .toString(),
                )
        }
    }

    /** Device hint only (cosmetic UI). Perks are granted by the server's on-chain Genesis Token check. */
    private fun isSeekerDevice(): Boolean {
        val s = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} ${Build.DEVICE}".lowercase()
        return "seeker" in s || "solana" in s || "solanamobile" in s
    }
}

private class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    private var lastAt = 0L

    fun play(kind: String) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // Combat can fire many hits a second: never buzz more than ~16 times a second.
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 60) return
        lastAt = now
        val effect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (kind) {
                    "tick" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    "heavy" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    "success" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    "error" -> VibrationEffect.createWaveform(longArrayOf(0, 35, 70, 35), -1)
                    "hit" -> VibrationEffect.createOneShot(18, 160)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
            } else {
                val ms =
                    when (kind) {
                        "tick" -> 10L
                        "heavy", "error" -> 45L
                        "success" -> 30L
                        else -> 20L
                    }
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        runCatching { v.vibrate(effect) }
    }
}
