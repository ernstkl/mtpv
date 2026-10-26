package io.github.ernstkl.mtpv.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.ernstkl.mtpv.core.DirectCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** The direct network on this device, as the UI shows it. */
sealed interface DirectState {
    data object Off : DirectState

    /** Creating the group (controller) or looking for it (secondary). */
    data object Starting : DirectState

    /** In the group: own [address] (null until it has one) and, on the controller, the number of [clients]. */
    data class Up(val address: String?, val clients: Int = 0) : DirectState

    /** Not working, [reason] in words; tried again by itself while wanted. */
    data class Failed(val reason: String) : DirectState
}

/**
 * Thin base around WifiP2pManager for the controller's group and a secondary's joining it: channel,
 * broadcasts, permissions and the group config from a [DirectCode]. Main thread only.
 */
@SuppressLint("MissingPermission") // Every call to the manager goes through p2p(), which checks.
abstract class DirectWifi(context: Context) {
    protected val app: Context = context.applicationContext
    private val manager: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    protected val handler = Handler(Looper.getMainLooper())

    protected val _state = MutableStateFlow<DirectState>(DirectState.Off)
    val state: StateFlow<DirectState> = _state

    /** The code in use, null while off; and the network name it defines. */
    protected var code: String? = null
        private set
    protected var networkName: String? = null
        private set

    /** Wi-Fi Direct is enabled (Wi-Fi is on). Known once the sticky broadcast has arrived. */
    protected var enabled = false
        private set
    private var registered = false

    /** Called when Wi-Fi Direct is enabled again, e.g. after Wi-Fi was switched off and on. */
    protected abstract fun onEnabled()

    /** Called with the group this device is in now, or null; also from [checkGroup]. */
    protected abstract fun onGroup(group: WifiP2pGroup?)

    /**
     * Takes [code] into use. Returns true if Wi-Fi Direct was not in use yet: then [onEnabled]
     * follows as soon as it is known to be enabled.
     */
    protected fun use(code: String): Boolean {
        if (code != this.code) {
            networkName = DirectCode.networkName(code)
            this.code = code
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            return true
        }
        return false
    }

    /** Stops trying, leaves or removes the group, and lets go of Wi-Fi Direct. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (code != null) p2p()?.let { (m, ch) ->
            m.cancelConnect(ch, null)
            m.removeGroup(ch, null)
        }
        code = null
        networkName = null
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        enabled = false
        channel?.close()
        channel = null
        _state.value = DirectState.Off
    }

    protected val config: WifiP2pConfig?
        get() {
            val name = networkName ?: return null
            return WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(DirectCode.passphrase(code ?: return null)).build()
        }

    /** The group belongs to our code. */
    protected fun isOurs(g: WifiP2pGroup?) = g != null && g.networkName == networkName

    /** Manager and channel, or null after reporting why they cannot be used. */
    protected fun p2p(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = manager
        if (m == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            _state.value = DirectState.Failed("This device has no Wi-Fi Direct.")
            return null
        }
        if (!hasPermission(app)) {
            val what = if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"
            _state.value = DirectState.Failed("MTPV may not use Wi-Fi Direct: allow \"$what\" for MTPV in Android's app settings.")
            return null
        }
        val ch = channel ?: m.initialize(app, Looper.getMainLooper()) { channel = null } ?: return null
        channel = ch
        return m to ch
    }

    /** Asks for the group this device is in and passes it to [onGroup]. */
    protected fun checkGroup() {
        val (m, ch) = p2p() ?: return
        m.requestGroupInfo(ch) { if (code != null) onGroup(it) }
    }

    protected fun listener(onFailed: (String) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) = onFailed(reasonText(reason))
    }

    private fun reasonText(r: Int): String = when {
        r == WifiP2pManager.P2P_UNSUPPORTED -> "This device has no Wi-Fi Direct."
        !enabled -> "Wi-Fi is off. Switch it on (no Wi-Fi network needed)."
        locationNeeded(app) -> "If it keeps failing, switch on location: some devices with Android 12 and older need it."
        r == WifiP2pManager.BUSY -> "Wi-Fi Direct is busy."
        else -> "Wi-Fi Direct failed (error $r)."
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (code == null) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, 0) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    val was = enabled
                    enabled = on
                    if (!on) _state.value = DirectState.Failed(reasonText(WifiP2pManager.ERROR))
                    else if (!was) onEnabled()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    onGroup(IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java))
            }
        }
    }

    companion object {
        /**
         * To ask for: Android 13+ nearby devices, older versions precise location (Android 12 grants
         * it only together with coarse). The first one is the one needed.
         */
        val permissions: Array<String> =
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        fun hasPermission(context: Context) =
            context.checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED

        /**
         * Below Android 13, location off may be why Wi-Fi Direct fails. Not always: an SM-T510
         * (Android 11) created a group with location off, so this is only named after a failure.
         */
        fun locationNeeded(context: Context) =
            Build.VERSION.SDK_INT < 33 && context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true

        /** IPv4 address of interface [name] (a group's p2p interface), if it has one yet. */
        fun ipv4Of(name: String?): String? = runCatching {
            NetworkInterface.getByName(name ?: return null)?.inetAddresses?.toList()
                ?.firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }
}
