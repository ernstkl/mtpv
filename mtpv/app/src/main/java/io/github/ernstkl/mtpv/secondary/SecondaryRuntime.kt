package io.github.ernstkl.mtpv.secondary

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.ernstkl.mtpv.DisplayState
import io.github.ernstkl.mtpv.FrontNotification
import io.github.ernstkl.mtpv.MtpvApp
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.core.NoteTool
import io.github.ernstkl.mtpv.core.NotesJson
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.Stroke
import io.github.ernstkl.mtpv.core.ToolState
import io.github.ernstkl.mtpv.core.ToolSync
import io.github.ernstkl.mtpv.core.VersionGate
import io.github.ernstkl.mtpv.net.Protocol
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address

/**
 * Notes of the page a secondary shows. [rev] counts edits on this device and never resets;
 * [dirty] means there are edits the controller has not fetched yet. [baseRev] is the controller's
 * revision of the notes it sent with the page.
 */
data class ShownNotes(
    val fileId: String?,
    val page: Int,
    val baseRev: Long,
    val rev: Long,
    val dirty: Boolean,
    val strokes: List<Stroke>,
)

/** Secondary role: serves the HTTP API, announces it via mDNS and holds what the screen shows. */
class SecondaryRuntime(private val app: MtpvApp) {
    val cache = app.cache
    private val prefs = app.prefs
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    val pairing = Pairing(prefs)

    private val _display = MutableStateFlow(prefs.loadDisplay())
    val display: StateFlow<DisplayState> = _display

    private val notesFile = File(app.filesDir, "shown-notes.json")
    private val _notes = MutableStateFlow(loadNotes())
    val notes: StateFlow<ShownNotes> = _notes

    /**
     * Note tool, finger switch and dark pages, kept across restarts so pages stay dark until the
     * controller is back; the controller resends its own after a restart of this app.
     */
    private val _tools = MutableStateFlow(prefs.tools)
    val tools: StateFlow<ToolState> = _tools

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
        .let { ToolSync.toJson(_tools.value, it) }

    /**
     * Shows [page] with its [notes]. Refused with notes_unsaved while this device has edits that the
     * controller has not saved yet ([savedRev] older than the current edit count), so no note is
     * lost when the page moves on: the controller fetches them first and then retries.
     */
    @Synchronized
    fun applyDisplay(
        controller: String,
        version: Long,
        fileId: String?,
        page: Int,
        notes: List<Stroke>,
        notesRev: Long,
        savedRev: Long,
    ): NanoHTTPD.Response {
        val cur = _display.value
        if (!VersionGate.accepts(cur.controller, cur.version, controller, version)) {
            return SecondaryServer.error(
                NanoHTTPD.Response.Status.CONFLICT, Protocol.ERR_STALE, null, stateJson(cur)
            )
        }
        if (fileId != null && !cache.has(fileId)) {
            return SecondaryServer.error(NanoHTTPD.Response.Status.NOT_FOUND, Protocol.ERR_UNKNOWN_FILE, fileId)
        }
        val shown = _notes.value
        if (isWriting() || (shown.dirty && savedRev < shown.rev)) {
            return SecondaryServer.error(NanoHTTPD.Response.Status.CONFLICT, Protocol.ERR_NOTES_UNSAVED, null)
        }
        val next = DisplayState(fileId, page, version, controller)
        _display.value = next
        prefs.saveDisplay(next)
        setShownNotes(ShownNotes(fileId, page, notesRev, shown.rev, dirty = false, strokes = notes))
        fileId?.let { cache.touch(it) }
        return SecondaryServer.ok(stateJson(next))
    }

    // --- notes on the shown page ---

    /** Replaces the strokes of the shown page after drawing or erasing on this device. */
    @Synchronized
    fun editNotes(key: PageKey, strokes: List<Stroke>) {
        val cur = _notes.value
        // Page changes wait while the pen is down (see isWriting), so this only misses after a timeout.
        if (cur.fileId != key.fileId || cur.page != key.page) {
            Log.w(TAG, "dropped notes for page ${key.page}, now showing ${cur.page}")
            return
        }
        setShownNotes(cur.copy(rev = cur.rev + 1, dirty = true, strokes = strokes))
    }

    @Volatile private var penDownAt = 0L

    /** While the stylus is down, page changes are refused so the stroke stays on its page. */
    fun setPenDown(down: Boolean) {
        penDownAt = if (down) System.currentTimeMillis() else 0L
    }

    private fun isWriting(): Boolean {
        val at = penDownAt
        return at != 0L && System.currentTimeMillis() - at < MAX_STROKE_MS
    }

    @Synchronized
    fun notesJson(): JSONObject = _notes.value.let { n ->
        JSONObject()
            .put("fileId", n.fileId ?: "")
            .put("page", n.page)
            .put("baseRev", n.baseRev)
            .put("rev", n.rev)
            .put("dirty", n.dirty)
            .put("strokes", NotesJson.toJson(n.strokes))
    }

    /** Persisted immediately, so unsaved notes survive a crash or a restart of this device. */
    private fun setShownNotes(n: ShownNotes) {
        _notes.value = n
        val tmp = File(notesFile.parentFile, notesFile.name + ".tmp")
        tmp.writeText(notesJson().toString())
        tmp.renameTo(notesFile)
    }

    private fun loadNotes(): ShownNotes = runCatching {
        val o = JSONObject(notesFile.readText())
        ShownNotes(
            fileId = o.optString("fileId").ifEmpty { null },
            page = o.optInt("page"),
            baseRev = o.optLong("baseRev"),
            rev = o.optLong("rev"),
            dirty = o.optBoolean("dirty"),
            strokes = NotesJson.fromJson(o.optJSONArray("strokes")),
        )
    }.getOrDefault(ShownNotes(null, 0, 0, 0, false, emptyList()))

    // --- note tool and finger switch, the same on all screens ---

    /** Picked on this screen: passed on to the controller, which picks it on the other screens. */
    fun pickTool(t: NoteTool) {
        setTools(_tools.value.copy(tool = t))
        sendInput(ToolSync.event(t))
    }

    fun setFingers(on: Boolean) {
        setTools(_tools.value.copy(fingers = on))
        sendInput(ToolSync.event(on))
    }

    fun setDark(on: Boolean) {
        setTools(_tools.value.copy(dark = on))
        sendInput(ToolSync.darkEvent(on))
    }

    fun toolsFromController(t: ToolState) = setTools(t)

    private fun setTools(t: ToolState) {
        _tools.value = t
        prefs.tools = t
    }

    // --- page turns and tool picks entered on this device, fetched by the controller via long polling ---

    private val inputLock = Object()
    private var inputSeq = 0L
    private val recentInput = ArrayDeque<Pair<Long, String>>()

    /** Queues [action] ("next", "prev", "front" or a tool event) for the controller. */
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

    /** MTPV came (back) to the front here: the controller brings it to the front on the other screens. */
    fun cameToFront() = sendInput(ControllerRuntime.FRONT_EVENT)

    /** The controller asks to bring MTPV to the front; returns whether it is on screen already. */
    fun frontRequested(): Boolean {
        val shown = app.inFront
        if (!shown) FrontNotification.show(app)
        return shown
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
        private const val MAX_STROKE_MS = 15_000L
    }
}
