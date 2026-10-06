package land.plonk.app.bridge

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject

/**
 * `{ t: 'haptic', k }` with k = tick | tap | hit | heavy | success | error (default tap).
 *
 * Uses the phone's predefined effects where it has them, so a tap feels like the rest of the
 * system rather than a raw buzz. No reply.
 */
class HapticsHandler(
    context: Context,
) : BridgeHandler {
    override val types = setOf("haptic")

    private val haptics = Haptics(context)

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) = haptics.play(msg.optString("k", "tap"))
}

private class Haptics(
    context: Context,
) {
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
