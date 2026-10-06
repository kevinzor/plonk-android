package land.plonk.app.bridge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * What bridge handlers may use from the activity: start other apps, ask for runtime permissions,
 * and get Activity Result launchers.
 *
 * Android only lets an activity register result launchers before it is STARTED. The host and every
 * handler are therefore built in MainActivity.onCreate, and a handler that needs its own launcher
 * calls [registerForResult] from its constructor, never from [BridgeHandler.handle].
 *
 * Permission prompts share one launcher. Requests that overlap (the page asks twice, or two
 * features ask at once) wait in a queue instead of cancelling each other.
 *
 * The activity never recreates on config changes (see the manifest), so pending callbacks survive
 * rotation. If Android kills the process while a prompt is open, the page reloads and asks again.
 */
class BridgeHost(
    val activity: ComponentActivity,
) {
    private class PermissionRequest(
        val permissions: List<String>,
        val onResult: (Map<String, Boolean>) -> Unit,
    )

    private val permissionQueue = ArrayDeque<PermissionRequest>()
    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val done = permissionQueue.removeFirstOrNull() ?: return@registerForActivityResult
            done.onResult(done.permissions.associateWith { granted[it] ?: hasPermission(it) })
            launchNextPermissionRequest()
        }

    /** Register an Activity Result launcher. Only valid while the activity is being created. */
    fun <I, O> registerForResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> = activity.registerForActivityResult(contract, callback)

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Ask for [permissions] and report each one as granted or not. Already-granted permissions
     * answer at once without showing the system dialog.
     */
    fun requestPermissions(
        permissions: Collection<String>,
        onResult: (Map<String, Boolean>) -> Unit,
    ) {
        val wanted = permissions.distinct()
        if (wanted.all(::hasPermission)) {
            onResult(wanted.associateWith { true })
            return
        }
        permissionQueue.addLast(PermissionRequest(wanted, onResult))
        if (permissionQueue.size == 1) launchNextPermissionRequest()
    }

    /** Start another app. Returns false (instead of throwing) when nothing can handle [intent]. */
    fun startActivity(intent: Intent): Boolean =
        try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "No app for ${intent.action} ${intent.data?.scheme.orEmpty()}")
            false
        }

    private fun launchNextPermissionRequest() {
        val next = permissionQueue.firstOrNull() ?: return
        try {
            permissionLauncher.launch(next.permissions.toTypedArray())
        } catch (e: Exception) {
            Log.w(TAG, "Permission request failed", e)
            permissionQueue.removeFirst()
            next.onResult(next.permissions.associateWith(::hasPermission))
            launchNextPermissionRequest()
        }
    }

    private companion object {
        const val TAG = "Plonk"
    }
}
