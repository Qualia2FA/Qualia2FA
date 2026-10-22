package dev.qualia.qualia2fa

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The multicast hold of Android: a `WifiManager.MulticastLock`.
 *
 * The lock is what stops the chipset from filtering multicast datagrams out of the traffic it
 * hands to the system. It is **not** reference counted: the caller decides when the hold is
 * taken and when it is given back, and a second release — which a `DisposableEffect` can
 * produce when a screen is left in the middle of a flow — must not throw.
 *
 * Asking for the lock when the platform has no WiFi service (a device with no WiFi at all)
 * leaves the hold empty rather than failing: a channel that hears nothing is a fact the screen
 * reports through its own list of devices.
 */
@Composable
actual fun rememberMulticastHold(): MulticastHold {
    val context = LocalContext.current
    return remember(context) { AndroidMulticastHold(context.applicationContext) }
}

private class AndroidMulticastHold(private val context: Context) : MulticastHold {
    private var lock: WifiManager.MulticastLock? = null

    override fun acquire() {
        if (lock != null) return
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lock =
            wifi.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    override fun release() {
        val held = lock ?: return
        lock = null
        runCatching { held.release() }
    }
}

/** The name the platform shows for this hold in its own battery and network diagnostics. */
private const val LOCK_TAG = "qualia2fa-lan"

/**
 * The permissions this version of Android gates the local network behind.
 *
 * Two of them exist only on the versions that introduced them, and asking for a permission the
 * platform does not know is answered with a refusal rather than being ignored, so which ones
 * are asked for is decided by the API level:
 *
 * - `NEARBY_WIFI_DEVICES` from Android 13 (API 33), which is also where the WiFi APIs started
 *   to require it. It is declared `neverForLocation`: an announcement is not used to work out
 *   where the device is.
 * - `ACCESS_LOCAL_NETWORK` from Android 17 (API 37), the permission that gates every socket
 *   to the local network.
 *
 * An already granted permission is not asked for again: on the versions below the two, this
 * list is empty and there is nothing to ask for at all.
 */
@Composable
actual fun rememberLanPermission(): LanPermission {
    val context = LocalContext.current
    // Nothing is cached here: a permission granted between two opens — in the system settings,
    // say — has to be seen as granted, and one revoked has to be asked for again.
    return remember(context) {
        object : LanPermission {
            override fun request(onAnswer: (Boolean) -> Unit) {
                val missing = missingLanPermissions(context)
                if (missing.isEmpty()) {
                    onAnswer(true)
                    return
                }
                val activity = context.findActivity()
                if (activity == null) {
                    onAnswer(false)
                    return
                }
                LanPermissions.request(activity, missing, onAnswer)
            }
        }
    }
}

/**
 * The one permission request in flight, answered by [MainActivity] when the system dialog is
 * dealt with.
 *
 * # Why the Compose launcher is not used
 *
 * `rememberLauncherForActivityResult` with a permission contract cannot be used by an activity
 * that extends `FragmentActivity`: `ActivityResultRegistry` hands out request codes from
 * `0x10000` upwards, and `FragmentActivity` — which the biometric prompt requires — throws
 * `IllegalArgumentException: Can only use lower 16 bits for requestCode` for any code that does
 * not fit in the lower sixteen bits. The request is therefore issued with a code of this
 * application's own, below that boundary, and the answer is routed back by the activity's
 * `onRequestPermissionsResult`.
 *
 * One request at a time is all this needs: the system dialog is modal, and every screen asks
 * for the same set. A request that supersedes one still in flight answers the earlier one with
 * `false`, so no caller is left waiting.
 */
internal object LanPermissions {
    /** Any value below `0x10000`; the number itself carries no meaning. */
    private const val REQUEST_CODE = 0x514C

    private var pending: ((Boolean) -> Unit)? = null

    /** Asks for [permissions], reporting the answer through [onAnswer] on the main thread. */
    fun request(activity: Activity, permissions: List<String>, onAnswer: (Boolean) -> Unit) {
        pending?.invoke(false)
        pending = onAnswer
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), REQUEST_CODE)
    }

    /** Answers the request of [REQUEST_CODE]. Returns whether the code was this one. */
    fun deliver(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val answer = pending ?: return true
        pending = null
        answer(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        return true
    }
}

/** The activity [this] context belongs to, or `null` for an application context. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** The permissions of [LAN_PERMISSIONS] that this device has not granted yet. */
private fun missingLanPermissions(context: Context): List<String> =
    LAN_PERMISSIONS
        .filter { Build.VERSION.SDK_INT >= it.api }
        .filter { ContextCompat.checkSelfPermission(context, it.name) != PackageManager.PERMISSION_GRANTED }
        .map { it.name }

/** One permission the local network needs, and the API level that introduced it. */
private class LanPermissionOf(val api: Int, val name: String)

/**
 * The name `ACCESS_LOCAL_NETWORK` is declared under.
 *
 * Written out rather than read from `Manifest.permission`, which only carries the constants of
 * the SDK this build compiles against and does not have this one yet.
 */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** The API level that introduced `ACCESS_LOCAL_NETWORK`. */
private const val API_LOCAL_NETWORK = 37

/** The API level that introduced `NEARBY_WIFI_DEVICES`. */
private const val API_NEARBY_WIFI_DEVICES = 33

/** Every permission the local network is behind, oldest first. */
private val LAN_PERMISSIONS =
    listOf(
        LanPermissionOf(API_NEARBY_WIFI_DEVICES, Manifest.permission.NEARBY_WIFI_DEVICES),
        LanPermissionOf(API_LOCAL_NETWORK, ACCESS_LOCAL_NETWORK),
    )
