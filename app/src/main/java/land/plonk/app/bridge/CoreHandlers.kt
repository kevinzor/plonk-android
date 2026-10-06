package land.plonk.app.bridge

import android.os.Build
import android.view.WindowManager
import land.plonk.app.BuildConfig
import org.json.JSONObject

/** `{ t: 'exit' }`: close the app. The page sends it from the back button at the world root. */
class ExitHandler(
    private val host: BridgeHost,
) : BridgeHandler {
    override val types = setOf("exit")

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) = host.activity.finish()
}

/**
 * `{ t: 'awake', on }`: keep the screen on (on: true, the default) or let it sleep. The app starts
 * with the screen kept on.
 */
class KeepAwakeHandler(
    private val host: BridgeHost,
) : BridgeHandler {
    override val types = setOf("awake")

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) {
        val window = host.activity.window
        if (msg.optBoolean("on", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** `{ t: 'info' }` replies `{ t: 'info', version, code, sdk, model, seeker }`. */
class AppInfoHandler : BridgeHandler {
    override val types = setOf("info")

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) = reply.post(
        JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL)
            .put("seeker", isSeekerDevice()),
    )

    /** Device hint only (cosmetic UI). Perks are granted by the server's on-chain Genesis Token check. */
    private fun isSeekerDevice(): Boolean {
        val s = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} ${Build.DEVICE}".lowercase()
        return "seeker" in s || "solana" in s || "solanamobile" in s
    }
}
