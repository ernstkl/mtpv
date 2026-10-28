package io.github.ernstkl.multitabletpdfviewer.secondary

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ernstkl.multitabletpdfviewer.BuildConfig
import io.github.ernstkl.multitabletpdfviewer.DisplayState
import io.github.ernstkl.multitabletpdfviewer.MtpvApp
import io.github.ernstkl.multitabletpdfviewer.core.DirectAuto
import io.github.ernstkl.multitabletpdfviewer.core.NoteTool
import io.github.ernstkl.multitabletpdfviewer.core.NotesJson
import io.github.ernstkl.multitabletpdfviewer.core.PageKey
import io.github.ernstkl.multitabletpdfviewer.core.PagePosition
import io.github.ernstkl.multitabletpdfviewer.core.Presence
import io.github.ernstkl.multitabletpdfviewer.core.Stroke
import io.github.ernstkl.multitabletpdfviewer.core.ToolState
import io.github.ernstkl.multitabletpdfviewer.core.ToolSync
import io.github.ernstkl.multitabletpdfviewer.core.VersionGate
import io.github.ernstkl.multitabletpdfviewer.net.DirectState
import io.github.ernstkl.multitabletpdfviewer.net.DirectWifi
import io.github.ernstkl.multitabletpdfviewer.net.Protocol
import io.github.ernstkl.multitabletpdfviewer.net.Tls
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Notes of the page a secondary shows; [notesKey] names the song they belong to on the controller.
 * [rev] counts edits on this device and never resets; [dirty] means there are edits the controller
 * has not fetched yet. [baseRev] is the controller's revision of the notes it sent with the page.
 */
data class ShownNotes(
    val fileId: String?,
    val notesKey: String?,
    val page: Int,
    val baseRev: Long,
    val rev: Long,
    val dirty: Boolean,
    val strokes: List<Stroke>,
)

/**
 * Secondary role: holds what the screen shows and, while MTPV is on screen ([resume] to [pause]),
 * serves the HTTP API and announces it via mDNS.
 */
class SecondaryRuntime(private val app: MtpvApp) {
    val cache = app.cache
    private val prefs = app.prefs
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    val pairing = Pairing(prefs) { main.post(::updatePresence) }

    /** The last display command, kept across restarts: its version, and the page its notes belong to. */
    private val _display = MutableStateFlow(prefs.loadDisplay())

    /**
     * What the screen shows: blank from the start and from each return to the screen until the
     * controller gives a page again, since meanwhile it may have moved on. A link that drops while
     * MTPV stays on screen keeps the page.
     */
    private val _shown = MutableStateFlow(blank(_display.value))
    val display: StateFlow<DisplayState> = _shown

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

    /**
     * The controller's direct network. Its code ([joinCode]) comes from the controller or is typed
     * here, and is remembered until Leave; the network is joined when no controller reaches this
     * device otherwise ([DirectAuto]). Left in the background, joined again at once when back.
     */
    val direct = DirectJoin(app)
    private val _joinCode = MutableStateFlow(prefs.directJoin)
    val joinCode: StateFlow<String?> = _joinCode
    /** The code was typed here: look for the network until joined, even while a controller reaches this device. */
    private var manualJoin = false
    /** In the direct network (or looking for it) when MTPV left the screen. */
    private var directWanted = false
    private var resumedAt = 0L
    /** Since when this device has been looking for the direct network; null while joined or off. */
    private var lookingSince: Long? = null
    private val main = Handler(Looper.getMainLooper())
    private val checkDirect = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val joined = direct.state.value is DirectState.Up
            val looking = !joined && direct.state.value != DirectState.Off
            if (joined) manualJoin = false
            lookingSince = if (looking) lookingSince ?: now else null
            val since = now - maxOf(_lastContact.value, resumedAt)
            val contactWhileLooking = lookingSince?.let { _lastContact.value > it } == true
            val code = _joinCode.value
            val noWifi = !DirectWifi.inWifiNetwork(app)
            val contacted = _lastContact.value > resumedAt
            val active = direct.state.value != DirectState.Off
            when (DirectAuto.secondary(code, active, joined, manualJoin, since, contactWhileLooking, noWifi, contacted)) {
                DirectAuto.Join.START -> code?.let(direct::start)
                DirectAuto.Join.STOP -> direct.stop()
                DirectAuto.Join.NONE -> Unit
            }
            main.postDelayed(this, DIRECT_CHECK_MS)
        }
    }

    private var server: SecondaryServer? = null
    var port: Int = 0
        private set

    /** The current mDNS registration (a listener serves one registration only), and whether it says open. */
    private var registration: NsdManager.RegistrationListener? = null
    private var announcedOpen = false
    private var active = false

    /** MTPV came to the screen (main thread): listen, announce, and rejoin the direct network it was in. */
    fun resume() {
        active = true
        resumedAt = System.currentTimeMillis()
        synchronized(this) { _shown.value = blank(_display.value) }
        updatePresence()
        val code = _joinCode.value
        if (directWanted && code != null) direct.start(code)
        main.post(checkDirect)
    }

    /** MTPV left the screen (main thread): stop everything, so nothing runs in the background. */
    fun pause() {
        active = false
        main.removeCallbacks(checkDirect)
        directWanted = direct.state.value != DirectState.Off
        direct.stop()
        unannounce()
        server?.stop()
        server = null
    }

    /**
     * Server and mDNS while on screen (main thread): on resume, and when a controller is paired or
     * removed, which changes the announced [Presence.open].
     */
    private fun updatePresence() {
        if (!active) return
        val paired = pairing.paired.value.map { it.uuid }
        if (server == null) {
            // The server needs this device's key; making it the first time takes a moment.
            val identity = Tls.loaded() ?: return loadIdentity()
            server = startServer(identity)
            port = server?.listeningPort ?: 0
        }
        val open = Presence.open(paired)
        if (port > 0 && (registration == null || announcedOpen != open)) {
            unannounce()
            announce(open)
        }
    }

    private fun announce(open: Boolean) {
        val info = NsdServiceInfo().apply {
            serviceName = "MTPV ${prefs.deviceName} ${prefs.deviceUuid.take(4)}"
            serviceType = Protocol.SERVICE_TYPE
            port = this@SecondaryRuntime.port
            setAttribute("uuid", prefs.deviceUuid)
            setAttribute("name", prefs.deviceName)
            setAttribute("api", Protocol.API_VERSION.toString())
            setAttribute("open", if (open) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "mDNS registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
        announcedOpen = open
    }

    private fun unannounce() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private var loadingIdentity = false

    /** Loads or makes the key off the main thread, then carries on with [updatePresence]. */
    private fun loadIdentity() {
        if (loadingIdentity) return
        loadingIdentity = true
        thread(name = "mtpv-identity") {
            val ok = runCatching { Tls.identity() }.onFailure { Log.e(TAG, "no device key", it) }.isSuccess
            main.post {
                loadingIdentity = false
                if (ok) updatePresence()
            }
        }
    }

    /** The fixed port makes manual entry predictable; fall back to any free port. */
    private fun startServer(identity: Tls.Identity): SecondaryServer? {
        for (p in listOf(Protocol.DEFAULT_PORT, 0)) {
            try {
                return SecondaryServer(p, this, identity).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            } catch (e: IOException) {
                Log.w(TAG, "port $p unavailable", e)
            }
        }
        return null
    }

    fun stop() = pause()

    /** Typed here: join now. */
    fun joinDirect(code: String) {
        prefs.directJoin = code
        _joinCode.value = code
        manualJoin = true
        direct.start(code)
    }

    /** Leaves and forgets the code; a paired controller hands it over again at its next contact. */
    fun leaveDirect() {
        prefs.directJoin = null
        _joinCode.value = null
        manualJoin = false
        direct.stop()
    }

    /** From a paired controller (server thread): its code, null if it has no direct network. */
    fun directFromController(code: String?) = main.post {
        if (code == _joinCode.value) return@post
        prefs.directJoin = code
        _joinCode.value = code
        manualJoin = false
        // Looking for the old network, or in it: go on with the new one.
        if (code == null) direct.stop() else if (direct.state.value != DirectState.Off) direct.start(code)
    }

    fun onContact() {
        _lastContact.value = System.currentTimeMillis()
    }

    fun info(): JSONObject = JSONObject()
        .put("uuid", prefs.deviceUuid)
        .put("name", prefs.deviceName)
        .put("apiVersion", Protocol.API_VERSION)
        .put("open", Presence.open(pairing.paired.value.map { it.uuid }))

    fun stateJson(d: DisplayState = _shown.value): JSONObject = JSONObject()
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
        notesKey: String?,
        page: Int,
        position: PagePosition?,
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
        val next = DisplayState(fileId, page, version, controller, position)
        _display.value = next
        _shown.value = next
        prefs.saveDisplay(next)
        setShownNotes(ShownNotes(fileId, notesKey, page, notesRev, shown.rev, dirty = false, strokes = notes))
        fileId?.let { cache.touch(it) }
        return SecondaryServer.ok(stateJson(next))
    }

    /**
     * Removes the paired controller [uuid]. If it gave the page on screen, the screen clears, notes
     * not fetched yet included: they are its notes, and it can no longer fetch them. The version
     * and the edit count stay, so neither goes backwards.
     */
    @Synchronized
    fun removeController(uuid: String) {
        pairing.remove(uuid)
        val cur = _display.value
        if (cur.controller != uuid) return
        val next = blank(cur)
        _display.value = next
        _shown.value = next
        prefs.saveDisplay(next)
        setShownNotes(ShownNotes(null, null, 0, 0, _notes.value.rev, dirty = false, strokes = emptyList()))
    }

    // --- notes on the shown page ---

    /** Replaces the strokes of the shown page after drawing or erasing on this device. */
    @Synchronized
    fun editNotes(key: PageKey, strokes: List<Stroke>) {
        val cur = _notes.value
        // Page changes wait while the pen is down (see isWriting), so this only misses after a timeout.
        if (cur.notesKey != key.notesKey || cur.page != key.page) {
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
            .put("notesKey", n.notesKey ?: "")
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
            notesKey = o.optString("notesKey").ifEmpty { null },
            page = o.optInt("page"),
            baseRev = o.optLong("baseRev"),
            rev = o.optLong("rev"),
            dirty = o.optBoolean("dirty"),
            strokes = NotesJson.fromJson(o.optJSONArray("strokes")),
        )
    }.getOrDefault(ShownNotes(null, null, 0, 0, 0, false, emptyList()))

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

    /** Queues [action] ("next", "prev" or a tool event) for the controller. */
    fun sendInput(action: String) = synchronized(inputLock) {
        if (BuildConfig.DEBUG) Log.d(TAG, "input $action")
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

    fun render(fileId: String?, page: Int, w: Int, h: Int, dark: Boolean) = app.renderers.render(fileId, page, w, h, dark)

    /**
     * IPv4 addresses of this device, shown so the controller can be pointed here by hand: in the
     * Wi-Fi, and in a direct network (not a network that ConnectivityManager lists).
     */
    fun addresses(): List<String> {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses.orEmpty().map { it.address }
        val direct = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.name.startsWith("p2p") && it.isUp }
                .flatMap { it.inetAddresses.toList() }
        }.getOrDefault(emptyList())
        return (wifi + direct).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
    }

    companion object {
        private const val TAG = "MtpvSecondary"
        private const val MAX_STROKE_MS = 15_000L
        private const val DIRECT_CHECK_MS = 1_000L

        /** [d] with nothing on screen; version and controller stay. */
        private fun blank(d: DisplayState) = d.copy(fileId = null, page = 0, position = null)
    }
}
