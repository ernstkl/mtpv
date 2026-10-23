package io.github.ernstkl.mtpv.controller

import android.net.Uri
import android.provider.OpenableColumns
import io.github.ernstkl.mtpv.ControllerSession
import io.github.ernstkl.mtpv.FrontNotification
import io.github.ernstkl.mtpv.KnownSecondary
import io.github.ernstkl.mtpv.MtpvApp
import io.github.ernstkl.mtpv.controllerSupported
import io.github.ernstkl.mtpv.core.FolderFile
import io.github.ernstkl.mtpv.core.NoteTool
import io.github.ernstkl.mtpv.core.ToolState
import io.github.ernstkl.mtpv.core.NotesStore
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.PageRenderer
import io.github.ernstkl.mtpv.core.Paging
import io.github.ernstkl.mtpv.core.SetOrder
import io.github.ernstkl.mtpv.core.SetRecord
import io.github.ernstkl.mtpv.core.Song
import io.github.ernstkl.mtpv.core.SongSet
import io.github.ernstkl.mtpv.core.StoredFile
import io.github.ernstkl.mtpv.core.Stroke
import io.github.ernstkl.mtpv.core.ToolSync
import io.github.ernstkl.mtpv.core.moved
import io.github.ernstkl.mtpv.net.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A pairing in progress: [code] is shown here and on the secondary, where someone taps Allow. */
data class PairingUi(val uuid: String, val name: String, val code: String?)

data class ControllerState(
    val session: ControllerSession,
    val links: Map<String, LinkStatus> = emptyMap(),
    /** Page shown on each secondary, whose notes may have unsaved edits there. */
    val held: Map<String, PageKey> = emptyMap(),
    /** Note tool and finger switch on every screen: the last ones picked on any of them. */
    val tools: ToolState = ToolState(),
    /** Secondaries found on the network that are not paired with this controller. */
    val nearby: Map<String, KnownSecondary> = emptyMap(),
    val pairing: PairingUi? = null,
    val busy: String? = null,
    val message: String? = null,
) {
    val slots get() = session.arrangement.size
    val totalPages get() = session.set?.totalPages ?: 0
    val ownTarget get() = targetForSlot(session.arrangement.indexOf(Paging.SELF))

    /** Page of the set's stream shown in [slot], or 0 if that screen is blank. */
    fun pageForSlot(slot: Int) = Paging.pageFor(session.start, slot, totalPages)

    /**
     * File and page within it for [slot]. A blank screen past the end gets the last song's file
     * with page 0, so it shows the empty-set sign. Null while no set is open.
     */
    fun targetForSlot(slot: Int): Target? {
        val set = session.set ?: return null
        return set.locate(pageForSlot(slot))?.let { Target(it.fileId, it.page) } ?: Target(set.songs.last().fileId, 0)
    }

    /** Secondary that still holds unsaved notes of [key] and can be reached, if any. */
    fun holderOf(key: PageKey, except: String? = null): String? =
        held.entries.firstOrNull { (uuid, k) -> k == key && uuid != except && links[uuid]?.online == true }?.key
}

/**
 * Controller role: owns the session (PDF, first page, screen arrangement), finds secondaries and
 * keeps one [SecondaryLink] per secondary in the arrangement.
 */
class ControllerRuntime(private val app: MtpvApp) : NotesHub {
    private val prefs = app.prefs
    private val cache = app.cache
    private val notes = NotesStore(app)
    private val folders = Folders(app)
    val notesChanges: StateFlow<Long> = notes.changes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val versions = VersionClock({ prefs.lastVersion }, { prefs.lastVersion = it })
    private val links = mutableMapOf<String, Pair<SecondaryLink, Job>>()
    private var discovery: Discovery? = null

    private val _state = MutableStateFlow(ControllerState(restoreSession(), tools = prefs.tools))
    val state: StateFlow<ControllerState> = _state

    private fun restoreSession(): ControllerSession {
        val s = prefs.loadSession()
        val withSelf = if (Paging.SELF in s.arrangement) s.arrangement else listOf(Paging.SELF) + s.arrangement
        val setOk = s.set != null && s.set.songs.isNotEmpty() && s.set.songs.all { cache.has(it.fileId) }
        return s.copy(set = s.set.takeIf { setOk }, arrangement = withSelf)
    }

    fun start() {
        // MtpvApp never creates a controller below Android 14; the check is for lint.
        if (controllerSupported) discovery = Discovery(app, ::onFound).also { it.start() }
        syncLinks()
    }

    fun stop() {
        if (controllerSupported) discovery?.stop()
        discovery = null
        scope.cancel()
        links.clear()
    }

    // --- page turning ---

    fun next() = turn(+1)

    fun prev() = turn(-1)

    private fun turn(delta: Int) = updateSession {
        it.copy(start = it.start + delta)
    }

    // --- file ---

    /** A single PDF becomes a set of one song. */
    fun openPdf(uri: Uri) {
        scope.launch {
            _state.update { it.copy(busy = "Loading PDF…") }
            try {
                val id = app.contentResolver.openInputStream(uri)?.use { cache.store(it) }
                    ?: error("cannot read file")
                val name = displayName(uri)
                val count = app.renderers.get(id)?.pageCount ?: error("not a readable PDF")
                val set = SongSet.of(name, listOf(Song(name, id, count)))
                if (set.songs.isEmpty()) error("the PDF has no pages")
                withContext(Dispatchers.Main) {
                    updateSession { it.copy(set = set, folder = null, start = 1) }
                }
                cache.evict(setOf(id))
            } catch (e: Exception) {
                showMessage("Could not open PDF: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * The PDFs in folder [tree] become a set, in the order stored for that folder: files still
     * there keep their places, new ones go to the end. Files that are no readable PDF or have no
     * pages are left out quietly.
     */
    fun openFolder(tree: Uri) {
        scope.launch {
            _state.update { it.copy(busy = "Reading folder…") }
            try {
                folders.keepAccess(tree)
                val name = folders.name(tree)
                val present = folders.pdfs(tree).associateBy { it.name }
                val old = folders.load(tree)
                val order = SetOrder.merge(old?.order.orEmpty(), present.keys)
                val stored = order.mapIndexedNotNull { i, n ->
                    _state.update { it.copy(busy = "Loading ${i + 1} of ${order.size}…") }
                    val f = present.getValue(n)
                    old?.unchanged(f)?.takeIf { it.pageCount == 0 || cache.has(it.fileId) } ?: copy(tree, f)
                }
                folders.save(tree, SetRecord(tree.toString(), name, order, stored))
                val set = SongSet.of(name, stored.map { Song(it.file.name, it.fileId, it.pageCount) })
                if (set.songs.isEmpty()) error("no PDFs in $name")
                withContext(Dispatchers.Main) {
                    updateSession { s ->
                        // Reading the same folder again keeps the page on the first screen, if it is still there.
                        val same = s.folder == tree.toString()
                        val first = if (same) s.set?.locate(s.start)?.let(set::pageOf) ?: 0 else 0
                        s.copy(set = set, folder = tree.toString(), start = first.coerceAtLeast(1))
                    }
                }
                cache.evict(set.songs.map { it.fileId }.toSet())
            } catch (e: Exception) {
                showMessage("Could not open the folder: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** Copies [f] into the cache; null if it cannot be read now, page count 0 if it is no PDF. */
    private fun copy(tree: Uri, f: FolderFile): StoredFile? {
        val id = runCatching { folders.open(tree, f).use { cache.store(it) } }.getOrNull() ?: return null
        val count = runCatching { PageRenderer(cache.file(id)).use { it.pageCount } }.getOrDefault(0)
        return StoredFile(f, id, count)
    }

    private fun displayName(uri: Uri): String =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "PDF"

    // --- screens ---

    /**
     * Called by discovery (main thread). Known secondaries get their new address; others are only
     * listed as nearby until they are paired, so strangers on a crowded network never join.
     */
    private fun onFound(uuid: String, name: String, ep: Endpoint) {
        val known = _state.value.session.known[uuid]
        if (known != null) {
            updateSession { s -> s.copy(known = s.known + (uuid to known.copy(name = name, host = ep.host, port = ep.port))) }
        } else {
            _state.update { it.copy(nearby = it.nearby + (uuid to KnownSecondary(uuid, name, ep.host, ep.port))) }
        }
    }

    fun addByAddress(host: String, port: Int) {
        scope.launch {
            _state.update { it.copy(busy = "Contacting $host…") }
            try {
                val ep = Endpoint(host, port)
                val info = SecondaryClient(ep).info()
                withContext(Dispatchers.Main) {
                    onFound(info.uuid, info.name, ep)
                    if (_state.value.session.known[info.uuid]?.token != null) include(info.uuid) else pair(info.uuid)
                }
            } catch (e: Exception) {
                showMessage("No MTPV secondary at $host:$port (${e.message})")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun move(slot: Int, delta: Int) = updateSession { s -> s.copy(arrangement = s.arrangement.moved(slot, delta)) }

    fun include(uuid: String) = updateSession { s ->
        if (uuid in s.arrangement) s else s.copy(arrangement = s.arrangement + uuid)
    }

    fun exclude(uuid: String) = updateSession { s ->
        if (uuid == Paging.SELF) s else s.copy(arrangement = s.arrangement - uuid)
    }

    fun forget(uuid: String) = updateSession { s ->
        s.copy(arrangement = s.arrangement - uuid, known = s.known - uuid)
    }

    // --- pairing ---

    private var pairJob: Job? = null

    /** Asks secondary [uuid] to accept this controller; someone has to tap Allow on it within a minute. */
    fun pair(uuid: String) {
        val st = _state.value
        val k = st.session.known[uuid] ?: st.nearby[uuid] ?: return
        val host = k.host ?: return
        pairJob?.cancel()
        pairJob = scope.launch {
            _state.update { it.copy(pairing = PairingUi(uuid, k.name, null)) }
            try {
                val client = SecondaryClient(Endpoint(host, k.port))
                val (requestId, code) = client.pairRequest(prefs.deviceUuid, prefs.deviceName)
                _state.update { it.copy(pairing = PairingUi(uuid, k.name, code)) }
                val deadline = System.currentTimeMillis() + PAIR_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val r = client.pairStatus(requestId)
                    when (r.status) {
                        "allowed" -> {
                            withContext(Dispatchers.Main) { paired(k.copy(token = r.token)) }
                            return@launch
                        }
                        "denied" -> {
                            showMessage("${k.name} declined the pairing.")
                            return@launch
                        }
                        "unknown" -> break
                    }
                    delay(1_000)
                }
                showMessage("No answer from ${k.name}. Try again, and tap Allow on it.")
            } catch (e: IOException) {
                showMessage("Could not reach ${k.name}: ${e.message}")
            } finally {
                _state.update { if (it.pairing?.uuid == uuid) it.copy(pairing = null) else it }
            }
        }
    }

    fun cancelPairing() {
        pairJob?.cancel()
        _state.update { it.copy(pairing = null) }
    }

    /** A newly paired secondary becomes the rightmost screen. */
    private fun paired(k: KnownSecondary) {
        _state.update { it.copy(nearby = it.nearby - k.uuid) }
        updateSession { s ->
            s.copy(
                known = s.known + (k.uuid to k),
                arrangement = if (k.uuid in s.arrangement) s.arrangement else s.arrangement + k.uuid,
            )
        }
    }

    fun render(fileId: String?, page: Int, w: Int, h: Int) = app.renderers.render(fileId, page, w, h)

    // --- notes ---

    /** Notes drawn on the controller's own screen. */
    fun editNotes(key: PageKey, strokes: List<Stroke>) {
        notes.save(key, strokes)
    }

    override fun load(key: PageKey): NotesStore.Page = notes.load(key)

    override fun saveFromSecondary(uuid: String, notes: SecondaryClient.Notes) {
        val key = PageKey(notes.fileId ?: return, notes.page)
        if (this.notes.saveFromSecondary(key, notes.strokes, notes.baseRev)) {
            val name = _state.value.session.known[uuid]?.name ?: uuid.take(4)
            showMessage("Page ${key.page} was written on here and on $name. Both sets of notes are kept.")
        }
    }

    override suspend fun awaitFree(key: PageKey, uuid: String) {
        // Normally a few milliseconds: the neighbour saves the page before it moves on.
        withTimeoutOrNull(3_000) { _state.first { it.holderOf(key, except = uuid) == null } }
    }

    override fun hold(uuid: String, key: PageKey?) = _state.update {
        it.copy(held = if (key == null) it.held - uuid else it.held + (uuid to key))
    }

    fun setTool(t: NoteTool) = setTools(_state.value.tools.copy(tool = t))

    fun setFingers(on: Boolean) = setTools(_state.value.tools.copy(fingers = on))

    /** Picked here or on a secondary (main thread); every screen follows. */
    private fun setTools(t: ToolState) {
        if (t == _state.value.tools) return
        _state.update { it.copy(tools = t) }
        prefs.tools = t
        links.values.forEach { it.first.setTools(t) }
    }

    // --- bring MTPV to the front on every screen ---

    /** MTPV came (back) to the front here or on secondary [from]: the other screens follow (main thread). */
    fun cameToFront(from: String? = null) {
        if (from != null && !app.inFront) FrontNotification.show(app)
        links.forEach { (uuid, l) -> if (uuid != from) l.first.bringToFront() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun showMessage(m: String) = _state.update { it.copy(message = m) }

    // --- plumbing ---

    /** All session changes go through here, on the main thread: persist, then push targets to the links. */
    private fun updateSession(f: (ControllerSession) -> ControllerSession) {
        val before = _state.value.session
        var after = f(before)
        after = after.copy(start = Paging.clampStart(after.start, after.set?.totalPages ?: 0))
        if (after == before) return
        _state.update { it.copy(session = after) }
        prefs.saveSession(after)
        syncLinks()
    }

    private fun syncLinks() {
        val st = _state.value
        val s = st.session
        val wanted = s.arrangement.filter { it != Paging.SELF }.toSet()
        (links.keys - wanted).forEach { uuid ->
            links.remove(uuid)?.second?.cancel()
            _state.update { it.copy(links = it.links - uuid, held = it.held - uuid) }
        }
        s.arrangement.forEachIndexed { slot, uuid ->
            if (uuid == Paging.SELF) return@forEachIndexed
            val link = links.getOrPut(uuid) {
                val l = SecondaryLink(
                    uuid, prefs.deviceUuid, cache, versions, this,
                    report = { status -> _state.update { it.copy(links = it.links + (uuid to status)) } },
                    onInput = { event -> scope.launch(Dispatchers.Main) { onInput(uuid, event) } },
                )
                l to l.launchIn(scope)
            }.first
            s.known[uuid]?.let { k ->
                k.host?.let { link.setEndpoint(Endpoint(it, k.port)) }
                link.setToken(k.token)
            }
            link.setTarget(st.targetForSlot(slot))
            link.setTools(st.tools)
        }
    }

    private fun onInput(uuid: String, event: String) {
        when (event) {
            "prev" -> prev()
            "next" -> next()
            FRONT_EVENT -> cameToFront(from = uuid)
            else -> ToolSync.apply(_state.value.tools, event)?.let(::setTools)
        }
    }

    companion object {
        private const val PAIR_TIMEOUT_MS = 60_000L
        const val FRONT_EVENT = "front"
    }
}
