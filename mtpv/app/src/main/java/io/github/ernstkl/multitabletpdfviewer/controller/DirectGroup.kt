package io.github.ernstkl.multitabletpdfviewer.controller

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.github.ernstkl.multitabletpdfviewer.core.DirectCode
import io.github.ernstkl.multitabletpdfviewer.net.DirectState
import io.github.ernstkl.multitabletpdfviewer.net.DirectWifi

/**
 * The controller's direct network: a Wi-Fi Direct group with the name and password of its code,
 * which the secondaries join by the same code. Kept up while wanted: created again when Wi-Fi
 * comes back (the system does not), and retried every [RETRY_MS] while it is not up.
 */
@SuppressLint("MissingPermission") // p2p() checks it.
class DirectGroup(context: Context) : DirectWifi(context) {
    private val retry = Runnable { create() }

    fun start(code: String) {
        if (code != this.code && this.code != null) stop()
        _state.value = DirectState.Starting
        use(code)
        create()
    }

    override fun onEnabled() = create()

    /** Adopts our group if it is there already, removes another one first (it would block ours). */
    private fun create() {
        handler.removeCallbacks(retry)
        if (code == null) return
        if (_state.value !is DirectState.Up) handler.postDelayed(retry, RETRY_MS)
        val (m, ch) = p2p() ?: return
        m.requestGroupInfo(ch) { g ->
            val config = config ?: return@requestGroupInfo
            when {
                g == null -> m.createGroup(ch, config, listener(::failed))
                isOurs(g) && g.isGroupOwner -> onGroup(g)
                else -> m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = createSoon()
                    override fun onFailure(reason: Int) = createSoon()
                    fun createSoon() {
                        handler.postDelayed({ if (code != null) m.createGroup(ch, config, listener(::failed)) }, REMOVE_WAIT_MS)
                    }
                })
            }
        }
    }

    private fun failed(reason: String) {
        if (code != null && _state.value !is DirectState.Up) _state.value = DirectState.Failed("$reason Trying again.")
    }

    override fun onGroup(group: WifiP2pGroup?) {
        if (group != null && isOurs(group) && group.isGroupOwner) {
            handler.removeCallbacks(retry)
            _state.value = DirectState.Up(ipv4Of(group.`interface`) ?: OWNER_ADDRESS, group.clientList.size)
        } else if (_state.value is DirectState.Up) {
            // Lost, e.g. Wi-Fi switched off and on: a group from a config does not come back by itself.
            _state.value = DirectState.Starting
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, LOST_WAIT_MS)
        }
    }

    companion object {
        private const val RETRY_MS = 15_000L
        private const val LOST_WAIT_MS = 2_000L
        private const val REMOVE_WAIT_MS = 1_000L
        private const val OWNER_ADDRESS = DirectCode.GROUP_PREFIX + "1"
    }
}
