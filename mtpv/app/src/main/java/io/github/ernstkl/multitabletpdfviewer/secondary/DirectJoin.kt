package io.github.ernstkl.multitabletpdfviewer.secondary

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.github.ernstkl.multitabletpdfviewer.net.DirectState
import io.github.ernstkl.multitabletpdfviewer.net.DirectWifi

/**
 * A secondary in the controller's direct network: joins the Wi-Fi Direct group its code defines.
 * Until it is in, it asks again every 10 s, less often while the controller stays away (up to
 * every 30 s); at once when Wi-Fi comes back or the group is lost. A join still running is never
 * cancelled: that cost every second try (BUSY right after the cancel) and left the system's own
 * search running, after which joins failed for minutes while the group was there.
 */
@SuppressLint("MissingPermission") // p2p() checks it.
class DirectJoin(context: Context) : DirectWifi(context) {
    private val retry = Runnable { attempt() }
    private var every = FIRST_RETRY_MS

    fun start(code: String) {
        if (code != this.code && this.code != null) stop()
        _state.value = DirectState.Starting
        every = FIRST_RETRY_MS
        use(code)
        attempt()
    }

    override fun onEnabled() {
        every = FIRST_RETRY_MS
        attempt()
    }

    /** Joins unless in our group already; leaves another group first (the device can be in one only). */
    private fun attempt() {
        handler.removeCallbacks(retry)
        if (code == null) return
        if (_state.value !is DirectState.Up) {
            handler.postDelayed(retry, every)
            every = minOf(every * 2, MAX_RETRY_MS)
        }
        val (m, ch) = p2p() ?: return
        requestGroup(m, ch) { g ->
            val config = config ?: return@requestGroup
            when {
                isOurs(g) && g?.isGroupOwner == false -> onGroup(g)
                g != null -> m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = connectSoon()
                    override fun onFailure(reason: Int) = connectSoon()
                    fun connectSoon() {
                        handler.postDelayed({ if (code != null) m.connect(ch, config, listener(::failed)) }, REMOVE_WAIT_MS)
                    }
                })
                // While the last join still runs, this one is answered BUSY and that one goes on.
                else -> m.connect(ch, config, listener(::failed))
            }
        }
    }

    private fun failed(reason: String) {
        if (code != null && _state.value !is DirectState.Up) _state.value = DirectState.Failed("$reason Trying again.")
    }

    override fun onGroup(group: WifiP2pGroup?) {
        if (group != null && isOurs(group) && !group.isGroupOwner) {
            handler.removeCallbacks(retry)
            val address = ipv4Of(group.`interface`)
            _state.value = DirectState.Up(address)
            // The address comes a moment after the group: ask again.
            if (address == null) handler.postDelayed(::checkGroup, ADDRESS_WAIT_MS)
        } else if (_state.value is DirectState.Up) {
            _state.value = DirectState.Starting
            every = FIRST_RETRY_MS
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, LOST_WAIT_MS)
        }
    }

    companion object {
        private const val FIRST_RETRY_MS = 10_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val LOST_WAIT_MS = 2_000L
        private const val REMOVE_WAIT_MS = 1_000L
        private const val ADDRESS_WAIT_MS = 2_000L
    }
}
