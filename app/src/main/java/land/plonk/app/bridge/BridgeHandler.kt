package land.plonk.app.bridge

import org.json.JSONObject

/**
 * One `window.PlonkNative` feature: the message types it answers and what it does with them.
 *
 * The bridge itself knows nothing about haptics, wallets or notifications. Each feature is a
 * handler registered in MainActivity, so features can be added or removed without touching the
 * dispatcher or each other. Two handlers claiming the same type is a programming error and fails
 * at startup, not silently at runtime.
 *
 * [handle] is always called on the UI thread, only for main-frame messages from the allowed
 * origin. Long work should move off the UI thread; [Reply] can be used from any thread.
 */
interface BridgeHandler {
    /** Values of `t` this handler answers, e.g. `setOf("haptic")`. */
    val types: Set<String>

    /** Handle one message. [msg] is the parsed JSON (its `t` is one of [types]). */
    fun handle(
        msg: JSONObject,
        reply: Reply,
    )

    /** Release anything the handler holds. Called once, when the activity is destroyed. */
    fun dispose() {}
}
