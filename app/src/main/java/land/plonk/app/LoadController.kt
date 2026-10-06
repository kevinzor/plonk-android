package land.plonk.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import land.plonk.app.ui.StatusScreen
import land.plonk.app.ui.StatusScreen.Problem

/**
 * Decides what [StatusScreen] shows while the game page loads, and gets the player back into the
 * game on its own when the page can't load.
 *
 * - Loads the app starts (launch, Retry, renderer rebuild) show the branded loader with progress.
 *   Navigations the page makes itself don't, the game has its own transitions for those.
 * - A main-frame failure shows the error screen. Which words it uses depends on why: no network
 *   at all, a network but no answer, or the server saying it is restarting (HTTP 502-504).
 * - Offline, it waits for [NetworkWatcher] to see a network come back, then reloads.
 * - Online but unanswered, it retries on a backoff (4 s, 8 s, 15 s, then every 30 s) and shows a
 *   countdown, so a server restart or a deploy heals by itself.
 * - Nothing is retried while the app is in the background. Coming back to the app retries at
 *   once, which covers "went to Settings and turned Wi-Fi on".
 * - A renderer that keeps crashing gets the same countdown ([crashed]), never a tight loop.
 *
 * [serverUpdating] is the hook for the game page to say it is in maintenance; a future
 * `{ t: 'status', state: 'updating' }` bridge message only has to call it.
 */
class LoadController(
    private val screen: StatusScreen,
    context: Context,
    private val reload: () -> Unit,
) : DefaultLifecycleObserver {
    private enum class State { LOADING, READY, FAILED }

    private var state = State.LOADING
    private var problem: Problem? = null

    /** The current load already failed, so its onPageFinished must not hide the error. */
    private var failedThisLoad = false
    private var attempt = 0
    private var retryAt = 0L
    private var started = false

    private val main = Handler(Looper.getMainLooper())
    private val network = NetworkWatcher(context, onChange = ::onNetworkChanged, onBack = ::retryNow)
    private val tick = Runnable { onTick() }

    /** Called once, the first time the game has loaded (or failed to): the splash can go. */
    var onSettled: () -> Unit = {}
    private var settled = false

    init {
        screen.onRetry = ::retryNow
    }

    /** The app is about to load the game itself. Shows the loader. */
    fun loading() {
        state = State.LOADING
        failedThisLoad = false
        problem = null
        stopRetrying()
        screen.showLoading()
    }

    fun progress(percent: Int) {
        if (state != State.LOADING) return
        screen.setProgress(percent)
        if (percent >= 100 && !failedThisLoad) ready()
    }

    fun finished() {
        if (state == State.LOADING && !failedThisLoad) ready()
    }

    /** The main frame failed. [httpStatus] is set when the server answered with an error. */
    fun failed(httpStatus: Int?) {
        if (httpStatus != null && httpStatus !in SERVER_RESTARTING) return
        // WebView can report one failed load more than once; don't restart the countdown.
        if (failedThisLoad && state == State.FAILED) return
        failedThisLoad = true
        fail(
            when {
                httpStatus != null -> Problem.UPDATING
                !network.isOnline -> Problem.OFFLINE
                else -> Problem.UNREACHABLE
            },
        )
    }

    /** The game says it is being updated: cover it and keep checking until it is back. */
    fun serverUpdating() {
        failedThisLoad = true
        fail(Problem.UPDATING)
    }

    /**
     * The game's renderer keeps dying (see MainActivity). Show it and back off: [repeats] counts
     * the recent deaths beyond the first, and stretches the wait even though a load succeeded in
     * between, because a page that loads and then crashes is not fixed.
     */
    fun crashed(repeats: Int) {
        failedThisLoad = true
        attempt = maxOf(attempt, repeats - 1)
        fail(Problem.CRASHED)
    }

    /** True while the error screen is up (Android back should leave rather than reach the page). */
    val isShowingProblem: Boolean get() = state == State.FAILED

    override fun onStart(owner: LifecycleOwner) {
        started = true
        if (state != State.FAILED) return
        network.start()
        // Back from the background (maybe from turning Wi-Fi on): try straight away.
        if (problem == Problem.OFFLINE && !network.isOnline) show(Problem.OFFLINE) else retryNow()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        stopRetrying()
    }

    override fun onDestroy(owner: LifecycleOwner) = stopRetrying()

    private fun ready() {
        state = State.READY
        attempt = 0
        stopRetrying()
        screen.dismiss()
        settle()
    }

    private fun fail(why: Problem) {
        state = State.FAILED
        settle()
        if (!started) {
            problem = why
            screen.showProblem(why, null)
            return
        }
        network.start()
        show(if (why == Problem.UNREACHABLE && !network.isOnline) Problem.OFFLINE else why)
    }

    private fun show(why: Problem) {
        problem = why
        main.removeCallbacks(tick)
        if (why == Problem.OFFLINE) {
            // Nothing to gain by retrying with no network: wait for NetworkWatcher.
            screen.showProblem(why, screen.context.getString(R.string.status_waiting_for_network))
            return
        }
        val delay = BACKOFF_S[minOf(attempt, BACKOFF_S.lastIndex)]
        attempt++
        retryAt = SystemClock.uptimeMillis() + delay * 1000L
        screen.showProblem(why, countdown())
        main.postDelayed(tick, 1000)
    }

    private fun onTick() {
        if (state != State.FAILED) return
        if (SystemClock.uptimeMillis() >= retryAt) {
            retryNow()
        } else {
            screen.setHint(countdown())
            main.postDelayed(tick, 1000)
        }
    }

    private fun onNetworkChanged() {
        if (state != State.FAILED) return
        // Lost the network while waiting on the server: stop the countdown, say we're offline.
        if (!network.isOnline) show(Problem.OFFLINE)
    }

    private fun retryNow() {
        if (state != State.FAILED) return
        loading()
        reload()
    }

    private fun countdown(): String {
        val left = ((retryAt - SystemClock.uptimeMillis() + 999) / 1000).coerceAtLeast(1)
        return screen.context.getString(R.string.status_retry_in, left)
    }

    private fun stopRetrying() {
        main.removeCallbacks(tick)
        network.stop()
    }

    private fun settle() {
        if (settled) return
        settled = true
        onSettled()
    }

    private companion object {
        val BACKOFF_S = intArrayOf(4, 8, 15, 30)

        /** Gateway errors: nginx answering while the game server restarts or deploys. */
        val SERVER_RESTARTING = 502..504
    }
}
