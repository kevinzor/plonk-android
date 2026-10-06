package land.plonk.app.bridge

import land.plonk.app.bundle.GameBundle
import org.json.JSONObject

/**
 * `{ t: 'bundle' }` replies with how the current page was loaded:
 * `{ t: 'bundle', mode, bundleBuild, liveBuild, bundled, served, servedBytes, network }`.
 *
 * mode is `live` (unchanged files came from the APK), `offline` (the whole bundle, no network)
 * or `network` (no live manifest, everything streamed). The game can show "offline copy" in
 * offline mode, and testers can check the APK is doing its job from the devtools console.
 */
class BundleHandler(
    private val bundle: GameBundle,
) : BridgeHandler {
    override val types = setOf("bundle")

    override fun handle(
        msg: JSONObject,
        reply: Reply,
    ) {
        val s = bundle.status()
        reply.post(
            JSONObject()
                .put("mode", s.mode)
                .put("bundleBuild", s.bundleBuild ?: JSONObject.NULL)
                .put("liveBuild", s.liveBuild ?: JSONObject.NULL)
                .put("bundled", s.bundled)
                .put("served", s.served)
                .put("servedBytes", s.servedBytes)
                .put("network", s.network),
        )
    }
}
