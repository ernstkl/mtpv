package io.github.ernstkl.mtpv.secondary

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.ernstkl.mtpv.DisplayState
import io.github.ernstkl.mtpv.MtpvApp
import io.github.ernstkl.mtpv.core.VersionGate
import io.github.ernstkl.mtpv.net.Protocol
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address

/** Secondary role: serves the HTTP API, announces it via mDNS and holds what the screen shows. */
class SecondaryRuntime(private val app: MtpvApp) {
    val cache = app.cache
    private val prefs = app.prefs
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _display = MutableStateFlow(prefs.loadDisplay())
    val display: StateFlow<DisplayState> = _display

    /** Time of the last request from a controller, for the "connected" indicator. */
    private val _lastContact = MutableStateFlow(0L)
    val lastContact: StateFlow<Long> = _lastContact

    private var server: SecondaryServer? = null
    var port: Int = 0
        private set

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "registered ${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.w(TAG, "mDNS registration failed: $code")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }
    private var registered = false

    fun start() {
        server = startServer()
        port = server?.listeningPort ?: 0
        if (port > 0) {
            val info = NsdServiceInfo().apply {
                serviceName = "MTPV ${prefs.deviceName} ${prefs.deviceUuid.take(4)}"
                serviceType = Protocol.SERVICE_TYPE
                port = this@SecondaryRuntime.port
                setAttribute("uuid", prefs.deviceUuid)
                setAttribute("name", prefs.deviceName)
                setAttribute("api", Protocol.API_VERSION.toString())
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            registered = true
        }
    }

    /** The fixed port makes manual entry predictable; fall back to any free port. */
    private fun startServer(): SecondaryServer? {
        for (p in listOf(Protocol.DEFAULT_PORT, 0)) {
            try {
                return SecondaryServer(p, this).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            } catch (e: IOException) {
                Log.w(TAG, "port $p unavailable", e)
            }
        }
        return null
    }

    fun stop() {
        if (registered) runCatching { nsd.unregisterService(registration) }
        registered = false
        server?.stop()
        server = null
    }

    fun onContact() {
        _lastContact.value = System.currentTimeMillis()
    }

    fun info(): JSONObject = JSONObject()
        .put("uuid", prefs.deviceUuid)
        .put("name", prefs.deviceName)
        .put("apiVersion", Protocol.API_VERSION)

    fun stateJson(d: DisplayState = _display.value): JSONObject = JSONObject()
        .put("fileId", d.fileId ?: "")
        .put("page", d.page)
        .put("version", d.version)
        .put("controller", d.controller ?: "")

    @Synchronized
    fun applyDisplay(controller: String, version: Long, fileId: String?, page: Int): NanoHTTPD.Response {
        val cur = _display.value
        if (!VersionGate.accepts(cur.controller, cur.version, controller, version)) {
            return SecondaryServer.error(
                NanoHTTPD.Response.Status.CONFLICT, Protocol.ERR_STALE, null, stateJson(cur)
            )
        }
        if (fileId != null && !cache.has(fileId)) {
            return SecondaryServer.error(NanoHTTPD.Response.Status.NOT_FOUND, Protocol.ERR_UNKNOWN_FILE, fileId)
        }
        val next = DisplayState(fileId, page, version, controller)
        _display.value = next
        prefs.saveDisplay(next)
        fileId?.let { cache.touch(it) }
        return SecondaryServer.ok(stateJson(next))
    }

    // --- page turns entered on this device, fetched by the controller via long polling ---

    private val inputLock = Object()
    private var inputSeq = 0L
    private val recentInput = ArrayDeque<Pair<Long, String>>()

    /** Queues [action] ("next" or "prev") for the controller. */
    fun sendInput(action: String) = synchronized(inputLock) {
        inputSeq++
        recentInput.addLast(inputSeq to action)
        while (recentInput.size > 20) recentInput.removeFirst()
        inputLock.notifyAll()
    }

    /**
     * Returns the actions after sequence number [after], waiting up to [timeoutMs] for one.
     * A negative [after] (controller just connected) or one from before a restart of this app
     * returns the current sequence number at once, without old actions.
     */
    fun awaitInput(after: Long, timeoutMs: Long): JSONObject = synchronized(inputLock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (after == inputSeq) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            inputLock.wait(left)
        }
        val events = if (after < 0 || after > inputSeq) emptyList() else recentInput.filter { it.first > after }
        JSONObject().put("seq", inputSeq).put("events", JSONArray(events.map { it.second }))
    }

    fun afterStore(id: String) {
        cache.evict(setOfNotNull(id, _display.value.fileId))
    }

    fun render(fileId: String?, page: Int, w: Int, h: Int) = app.renderers.render(fileId, page, w, h)

    /** IPv4 addresses of this device, shown so the controller can be pointed here by hand. */
    fun addresses(): List<String> {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }

    companion object {
        private const val TAG = "MtpvSecondary"
    }
}
