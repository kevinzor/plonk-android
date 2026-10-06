package land.plonk.app

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Moves a page that opened from the APK's offline snapshot back onto the live game once the
 * phone is online again.
 *
 * The snapshot can be months older than the server. Left alone, the game would reconnect its
 * socket when signal returns, and old client code would talk to a newer server (changed
 * messages, new items, new checks). So once the default network is validated, [onOnline] runs:
 * MainActivity tells the page (`{ t: 'online' }` event) and reloads it unless the page takes
 * care of it. The reload gets a fresh plan, normally `live`.
 *
 * Watches only while the activity is started; returning to the app with a network fires at once.
 */
class OfflineCopyWatch(
    context: Context,
    private val onOnline: () -> Unit,
) : DefaultLifecycleObserver {
    private val network = NetworkWatcher(context, onChange = {}, onBack = ::check)
    private var armed = false
    private var started = false

    /** The page now showing came from the offline snapshot. */
    fun arm() {
        armed = true
        if (started) watch()
    }

    /** The page now showing is live (or gone): nothing to watch. */
    fun disarm() {
        armed = false
        network.stop()
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        if (armed) watch()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        network.stop()
    }

    private fun watch() {
        network.start()
        check()
    }

    private fun check() {
        if (!armed || !started || !network.isValidated) return
        disarm()
        onOnline()
    }
}
