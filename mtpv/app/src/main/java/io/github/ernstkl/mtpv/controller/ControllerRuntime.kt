package io.github.ernstkl.mtpv.controller

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import io.github.ernstkl.mtpv.BuildConfig
import io.github.ernstkl.mtpv.ControllerSession
import io.github.ernstkl.mtpv.KnownSecondary
import io.github.ernstkl.mtpv.MtpvApp
import io.github.ernstkl.mtpv.core.Backup
import io.github.ernstkl.mtpv.core.DirectAuto
import io.github.ernstkl.mtpv.core.DirectCode
import io.github.ernstkl.mtpv.core.FolderFile
import io.github.ernstkl.mtpv.core.LibraryEntry
import io.github.ernstkl.mtpv.core.LibraryLevel
import io.github.ernstkl.mtpv.core.NoteTool
import io.github.ernstkl.mtpv.core.ToolState
import io.github.ernstkl.mtpv.core.NotesKey
import io.github.ernstkl.mtpv.core.NotesSource
import io.github.ernstkl.mtpv.core.NotesSources
import io.github.ernstkl.mtpv.core.NotesStore
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.PageRenderer
import io.github.ernstkl.mtpv.core.PairCode
import io.github.ernstkl.mtpv.core.Paging
import io.github.ernstkl.mtpv.core.SetAdoption
import io.github.ernstkl.mtpv.core.SetOrder
import io.github.ernstkl.mtpv.core.SetRecord
import io.github.ernstkl.mtpv.core.Song
import io.github.ernstkl.mtpv.core.SongReconcile
import io.github.ernstkl.mtpv.core.SongSet
import io.github.ernstkl.mtpv.core.StoredFile
import io.github.ernstkl.mtpv.core.Stroke
import io.github.ernstkl.mtpv.core.ToolSync
import io.github.ernstkl.mtpv.core.moved
import io.github.ernstkl.mtpv.net.DirectState
import io.github.ernstkl.mtpv.net.Endpoint
import io.github.ernstkl.mtpv.net.Protocol
import io.github.ernstkl.mtpv.net.Tls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.LocalDate

/** A pairing in progress: [code] is shown here and on the secondary, where someone taps Allow. */
data class PairingUi(val uuid: String, val name: String, val code: String?)

/**
 * The library as the Sets dialog shows it. [path] runs from the library folder down to the folder
 * shown; [level] is null while it is read or if it cannot be read ([error]). [openPath] holds the
 * document IDs of the folders above the open set's folder and, last, that folder itself, to mark
 * the way there.
 */
data class LibraryUi(
    val path: List<LibraryEntry>,
    val level: LibraryLevel? = null,
    val error: String? = null,
    val openPath: List<String> = emptyList(),
) {
    val name get() = path.firstOrNull()?.name

    /** The folder shown is the open set. */
    val showsOpenSet get() = openPath.isNotEmpty() && path.lastOrNull()?.documentId == openPath.last()
}

/** Notes the song [song] can copy: [sources] with the same PDF, newest first, each with its last change. */
data class CopyNotesUi(
    val song: String,
    val notesKey: String,
    val hasNotes: Boolean,
    val sources: List<Pair<NotesSource, Long>>,
)

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
    /** An address scan is running. */
    val scanning: Boolean = false,
    /** Null until a library folder is chosen. */
    val library: LibraryUi? = null,
    /** Copy notes from…, while it is open. */
    val copyNotes: CopyNotesUi? = null,
    val busy: String? = null,
    val message: String? = null,
) {
    val slots get() = session.arrangement.size
    val totalPages get() = session.set?.totalPages ?: 0
    val ownTarget get() = targetForSlot(session.arrangement.indexOf(Paging.SELF))

    /** Page of the set's stream shown in [slot], or 0 if that screen is blank. */
    fun pageForSlot(slot: Int) = Paging.pageFor(session.start, slot, totalPages)

    /**
     * Song page for [slot]. A blank screen past the end gets the last shown song's file with page 0,
     * so it shows the empty-set sign. Null while no set is open.
     */
    fun targetForSlot(slot: Int): Target? {
        val set = session.set ?: return null
        val last = set.songs.lastOrNull { !it.excluded } ?: set.songs.last()
        val page = pageForSlot(slot)
        return set.locate(page)?.let { Target(it.fileId, it.notesKey, it.page, set.positionAt(page)) } ?: Target(last.fileId, null, 0)
    }

    /** Secondary that still holds unsaved notes of [key] and can be reached, if any. */
    fun holderOf(key: PageKey, except: String? = null): String? =
        held.entries.firstOrNull { (uuid, k) -> k == key && uuid != except && links[uuid]?.online == true }?.key
}

/**
 * Controller role: owns the session (PDF, first page, screen arrangement) and, while MTPV is on
 * screen ([resume] to [pause]), finds secondaries and keeps one [SecondaryLink] per secondary in
 * the arrangement.
 */
class ControllerRuntime(private val app: MtpvApp) : NotesHub {
    private val prefs = app.prefs
    private val cache = app.cache
    private val notes = NotesStore(app)
    private val folders = Folders(app)
    val notesChanges: StateFlow<Long> = notes.changes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Writes of a folder's song order, one after the other, so the last one wins. */
    private val orderWrites = Dispatchers.IO.limitedParallelism(1)
    private val versions = VersionClock({ prefs.lastVersion }, { prefs.lastVersion = it })
    private val links = mutableMapOf<String, Pair<SecondaryLink, Job>>()
    private var discovery: Discovery? = null
    /** On screen: network in use. Links, discovery, scans and the direct network exist only meanwhile. */
    private var active = false
    private var activeJob: Job? = null
    private val scanner = AddressScanner(app)
    private var scanJob: Job? = null
    private var lastScan = 0L
    /** Pause between automatic scans; doubles after each. Lost contact resets it and allows a scan right away. */
    private var scanEvery = SCAN_EVERY_MS

    /**
     * The direct network, for places without a shared Wi-Fi. [directCode] goes to the paired
     * secondaries and is shown for others to type. With [directAuto] it starts by itself
     * ([DirectAuto]), unless stopped by hand in this session. It stops when MTPV leaves the
     * screen and starts again at once when it is back ([directWanted]).
     */
    val direct = DirectGroup(app)
    private val _directCode = MutableStateFlow(prefs.directCode ?: newCode())
    val directCode: StateFlow<String> = _directCode
    private val _directAuto = MutableStateFlow(prefs.directAuto)
    val directAuto: StateFlow<Boolean> = _directAuto
    private var directStoppedByHand = false
    /** The network was started by itself ([DirectAuto]), not by hand: it stops again when not needed. */
    private var directByItself = false
    private var directWanted = false

    private val _state = MutableStateFlow(ControllerState(
            restoreSession(), tools = prefs.tools, library = prefs.library?.let { LibraryUi(prefs.libraryPath) },
        ))
    val state: StateFlow<ControllerState> = _state

    /** A set with a file gone from the cache is dropped; [start] reads it again from its folder, if it has one. */
    private fun restoreSession(): ControllerSession {
        val s = prefs.loadSession()
        val withSelf = if (Paging.SELF in s.arrangement) s.arrangement else listOf(Paging.SELF) + s.arrangement
        val setOk = s.set != null && s.set.songs.isNotEmpty() && s.set.songs.all { cache.has(it.fileId) }
        return s.copy(set = s.set.takeIf { setOk }, arrangement = withSelf)
    }

    fun start() {
        scope.launch(Dispatchers.Main) { followDirect() }
        if (_state.value.session.set == null) reloadFolder() else relocateFolder()
    }

    /** MTPV came to the screen (main thread): find the secondaries and bring them in line. */
    fun resume() {
        active = true
        if (directWanted) direct.start(_directCode.value)
        discovery = Discovery(app, ::onFound, ::onLost).also { it.start() }
        scanEvery = SCAN_EVERY_MS
        lastScan = 0L
        syncLinks()
        activeJob = scope.launch(Dispatchers.Main) { watchScreens() }
        if (screensShown) screensJob = scope.launch(Dispatchers.Main) { scanWhileScreens() }
    }

    /**
     * MTPV left the screen (main thread): drop links, discovery, scans and the direct network, so
     * nothing runs in the background. Notes written on a secondary stay there until it is reached again.
     */
    fun pause() {
        active = false
        activeJob?.cancel()
        activeJob = null
        screensJob?.cancel()
        screensJob = null
        scanJob?.cancel()
        discovery?.stop()
        discovery = null
        directWanted = direct.state.value != DirectState.Off
        direct.stop()
        links.values.forEach { it.second.cancel() }
        links.clear()
        _state.update { it.copy(links = emptyMap(), held = emptyMap(), scanning = false) }
    }

    fun stop() {
        pause()
        scope.cancel()
    }

    // --- page turning ---

    fun next() = turn(+1)

    fun prev() = turn(-1)

    private fun turn(delta: Int) = updateSession {
        if (BuildConfig.DEBUG) Log.d(TAG, "turn $delta from page ${it.start} of ${it.set?.totalPages ?: 0}")
        it.copy(start = it.start + delta)
    }

    // --- file ---

    /** Reading a folder or PDF; a new one cancels it. */
    private var openJob: Job? = null

    /** A single PDF becomes a set of one song, with a record of its own keyed by the document. */
    fun openPdf(uri: Uri) {
        openJob?.cancel()
        openJob = scope.launch {
            _state.update { it.copy(busy = "Loading PDF…") }
            try {
                val id = app.contentResolver.openInputStream(uri)?.use { cache.store(it) }
                    ?: error("cannot read file")
                val name = displayName(uri)
                val count = app.renderers.get(id)?.pageCount ?: error("not a readable PDF")
                if (count == 0) error("the PDF has no pages")
                val stored = StoredFile(FolderFile(name, uri.toString(), -1, 0), id, count)
                val record = updateRecord(uri, name, folders.load(uri), listOf(stored), mapOf(name to id), path = null)
                val set = SongSet.of(name, listOf(Song(name, id, record.notesKey(name)!!, count)))
                withContext(Dispatchers.Main) {
                    updateSession { it.copy(set = set, folder = null, start = 1) }
                }
                cache.evict(setOf(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Could not open PDF: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** A folder picked with the system's folder picker: keeps access to it, then opens it. */
    fun openPickedFolder(tree: Uri) {
        try {
            folders.keepAccess(tree)
        } catch (e: SecurityException) {
            showMessage("Could not keep access to the folder: ${e.message}")
        }
        openFolder(Folders.folderOf(tree))
    }

    /**
     * The PDFs in [folder] (a document URI) become a set, in the order stored for that folder:
     * files still there keep their places, new ones go to the end. Files that are no readable PDF
     * or have no pages are left out quietly. [path] holds the folder names from the library folder
     * down, if the folder was picked there; a folder that cannot be read is then looked for at the
     * same place in the library folder, in case the library moved.
     */
    fun openFolder(folder: Uri, path: List<String>? = null) {
        openJob?.cancel()
        openJob = scope.launch {
            _state.update { it.copy(busy = "Reading folder…") }
            try {
                val files = try {
                    folders.pdfs(folder)
                } catch (e: Exception) {
                    val (moved, p) = movedFolder(folder, path) ?: throw e
                    readFolder(moved, folders.pdfs(moved), p)
                    return@launch
                }
                readFolder(folder, files, path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Could not open the folder: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * For a [folder] that cannot be read: the folder at its place in the library folder, and that
     * place ([path], or the one its record keeps). Null if there is none.
     */
    private fun movedFolder(folder: Uri, path: List<String>?): Pair<Uri, List<String>>? {
        val p = path ?: folders.load(folder)?.path ?: return null
        val tree = prefs.library?.let(Uri::parse) ?: return null
        val moved = runCatching { folders.find(tree, p) }.getOrNull()?.takeIf { it != folder } ?: return null
        return moved to p
    }

    /** At start: if the open set's folder cannot be read any more but the library has one at its place, open that. */
    private fun relocateFolder() {
        val folder = _state.value.session.folder?.let(Uri::parse) ?: return
        scope.launch {
            if (runCatching { folders.pdfs(folder) }.isSuccess) return@launch
            val (moved, path) = movedFolder(folder, null) ?: return@launch
            withContext(Dispatchers.Main) { openFolder(moved, path) }
        }
    }

    private suspend fun readFolder(folder: Uri, files: List<FolderFile>, path: List<String>?) {
        val name = folders.name(folder)
        val present = files.associateBy { it.name }
        // A folder without a record may be a set whose library moved.
        val old = folders.load(folder)
            ?: path?.let { p -> SetAdoption.pick(folders.all().filter { it.folder != folder.toString() }, p, present.keys) }
        val names = SetOrder.merge(old?.order.orEmpty(), present.keys)
        val stored = names.mapIndexedNotNull { i, n ->
            _state.update { it.copy(busy = "Loading ${i + 1} of ${names.size}…") }
            val f = present.getValue(n)
            old?.unchanged(f)?.takeIf { it.pageCount == 0 || cache.has(it.fileId) } ?: copy(folder, f)
        }
        val hashes = present.keys.associateWith { n -> stored.firstOrNull { it.file.name == n }?.fileId }
        val record = updateRecord(folder, name, old, stored, hashes, path)
        val byName = stored.associateBy { it.file.name }
        val set = SongSet.of(name, record.order.mapNotNull { n ->
            val f = byName[n] ?: return@mapNotNull null
            Song(n, f.fileId, record.notesKey(n) ?: return@mapNotNull null, f.pageCount, n in record.excluded)
        })
        if (set.songs.isEmpty()) error("no PDFs in $name")
        withContext(Dispatchers.Main) {
            updateSession { s ->
                val first = when {
                    // Restored without its set: keep the page number.
                    s.set == null -> if (s.folder == folder.toString()) s.start else 1
                    // The same set read again, also from where its library moved, keeps the page on
                    // the first screen if its song is still there; another set has none of its songs.
                    else -> s.set.locate(s.start)?.let { set.pageOf(it.key) } ?: 1
                }
                s.copy(set = set, folder = folder.toString(), start = first.coerceAtLeast(1))
            }
        }
        cache.evict(set.songs.map { it.fileId }.toSet())
        releaseUnusedFolders()
    }

    /**
     * Brings the record of [folder] (or of a single PDF) up to date with the files read now
     * ([hashes]: each listed file with its hash, null if it could not be read): songs follow
     * renames, those whose file is gone or changed lose their notes, and new songs start without
     * notes, even if their PDF has notes in another set (see [copyNotes]).
     */
    private fun updateRecord(
        folder: Uri,
        name: String,
        old: SetRecord?,
        stored: List<StoredFile>,
        hashes: Map<String, String?>,
        path: List<String>?,
    ): SetRecord {
        val changes = SongReconcile.reconcile(
            old?.songs.orEmpty(), old?.files.orEmpty().associate { it.file.name to it.file.documentId },
            hashes, stored.associate { it.file.name to it.file.documentId }, NotesKey::newId,
        )
        fun renamed(n: String) = changes.renamed[n] ?: n
        val record = SetRecord(
            id = old?.id ?: NotesKey.newId(),
            folder = folder.toString(),
            name = name,
            order = SetOrder.merge(old?.order.orEmpty().map(::renamed), hashes.keys),
            files = stored,
            excluded = old?.excluded.orEmpty().map(::renamed).toSet() intersect hashes.keys,
            songs = changes.songs,
            path = path ?: old?.path,
        )
        folders.save(folder, record)
        if (old != null && old.folder != record.folder) folders.remove(old)
        changes.removed.forEach { notes.delete(NotesKey.of(record.id, it)) }
        return record
    }

    fun reloadFolder() {
        _state.value.session.folder?.let { openFolder(Uri.parse(it)) }
    }

    // --- library ---

    private var browseJob: Job? = null

    /**
     * A library folder picked with the system's folder picker; the Sets dialog starts at its top.
     * The open set is closed: it belongs to the library before.
     */
    fun setLibrary(tree: Uri) {
        openJob?.cancel()
        updateSession { it.copy(set = null, folder = null, start = 1) }
        try {
            folders.keepAccess(tree)
        } catch (e: SecurityException) {
            showMessage("Could not keep access to the folder: ${e.message}")
        }
        prefs.library = tree.toString()
        browse(emptyList())
        releaseUnusedFolders()
    }

    /**
     * Gives up read access to folders no longer needed (Android keeps at most 512 grants per app):
     * all but the library folder and the folder the open set was read from.
     */
    private fun releaseUnusedFolders() {
        val keep = listOfNotNull(prefs.library, _state.value.session.folder).map(Uri::parse)
        runCatching { folders.releaseAllBut(keep) }
    }

    /**
     * Reads the library folder at [path] for the Sets dialog; null reads again where the dialog was
     * left, an empty path the library folder itself. A folder that is gone falls back to the top.
     */
    fun browse(path: List<LibraryEntry>? = null) {
        val tree = prefs.library?.let(Uri::parse) ?: return
        val want = path ?: _state.value.library?.path.orEmpty()
        browseJob?.cancel()
        browseJob = scope.launch {
            _state.update { it.copy(library = LibraryUi(want)) }
            val ui = try {
                readLibrary(tree, want)
            } catch (e: Exception) {
                try {
                    readLibrary(tree, emptyList())
                } catch (e: Exception) {
                    LibraryUi(want, error = "Cannot read the library folder (${e.message}).")
                }
            }
            if (ui.error == null) prefs.libraryPath = ui.path
            _state.update { it.copy(library = ui) }
        }
    }

    /** Opens [entry], one of the folders of the library (the one shown, or one in it), as a set. */
    fun openLibrarySet(entry: LibraryEntry) {
        val tree = prefs.library?.let(Uri::parse) ?: return
        val shown = _state.value.library?.path.orEmpty()
        val path = if (shown.lastOrNull()?.documentId == entry.documentId) shown else shown + entry
        // Names below the library folder: the library may move, its name may change.
        openFolder(DocumentsContract.buildDocumentUriUsingTree(tree, entry.documentId), path.drop(1).map { it.name })
    }

    private fun readLibrary(tree: Uri, path: List<LibraryEntry>): LibraryUi {
        val top = Folders.folderOf(tree)
        val topId = DocumentsContract.getDocumentId(top)
        val full = if (path.firstOrNull()?.documentId == topId) path else listOf(LibraryEntry(folders.name(top), topId))
        val level = folders.children(DocumentsContract.buildDocumentUriUsingTree(tree, full.last().documentId))
        return LibraryUi(full, level, openPath = openPath(tree))
    }

    /** The open set's folder and, if the provider can tell, the library folders above it. */
    private fun openPath(tree: Uri): List<String> {
        val open = _state.value.session.folder?.let(Uri::parse) ?: return emptyList()
        if (open.authority != tree.authority) return emptyList()
        val id = DocumentsContract.getDocumentId(open)
        return folders.pathTo(tree, id) ?: listOf(id)
    }

    // --- songs ---

    /** Shows song [index] from its first page on the leftmost screen. */
    fun jumpToSong(index: Int) = updateSession { s ->
        val set = s.set ?: return@updateSession s
        if (index !in set.songs.indices) s else s.copy(start = set.firstPageOf(index))
    }

    /** Moves a song up or down; the controller's own screen keeps its page. The order is stored for the folder. */
    fun moveSong(index: Int, delta: Int) = changeSongs { it.move(index, delta) }

    /**
     * Leaves song [index] out of the stream, or takes it back in. The controller's own screen keeps
     * its page unless that page belongs to the song left out.
     */
    fun excludeSong(index: Int, excluded: Boolean) = changeSongs { it.exclude(index, excluded) }

    private fun changeSongs(f: (SongSet) -> SongSet) {
        updateSession { s ->
            val set = s.set ?: return@updateSession s
            val changed = f(set)
            val self = s.arrangement.indexOf(Paging.SELF)
            val start = set.locate(s.start + self)?.let { changed.pageOf(it.key) }?.takeIf { it > 0 }?.let { it - self } ?: s.start
            s.copy(set = changed, start = start)
        }
        val s = _state.value.session
        val folder = s.folder?.let(Uri::parse) ?: return
        val songs = s.set?.songs ?: return
        val names = songs.map { it.name }
        val excluded = songs.filter { it.excluded }.map { it.name }.toSet()
        scope.launch(orderWrites) {
            folders.load(folder)?.let { r ->
                folders.save(folder, r.copy(order = SetOrder.reordered(r.order, names), excluded = excluded))
            }
        }
    }

    /** Copies [f] into the cache; null if it cannot be read now, page count 0 if it is no PDF. */
    private fun copy(folder: Uri, f: FolderFile): StoredFile? {
        val id = runCatching { folders.open(folder, f).use { cache.store(it) } }.getOrNull() ?: return null
        val count = runCatching { PageRenderer(cache.file(id)).use { it.pageCount } }.getOrDefault(0)
        return StoredFile(f, id, count)
    }

    private fun displayName(uri: Uri): String =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "PDF"

    // --- direct network ---

    fun startDirect() {
        directStoppedByHand = false
        directByItself = false
        direct.start(_directCode.value)
    }

    private fun startDirectByItself() {
        directByItself = true
        direct.start(_directCode.value)
    }

    /** Stopped by hand: it does not start by itself again until the next start of the app. */
    fun stopDirect() {
        directStoppedByHand = true
        direct.stop()
    }

    fun setDirectAuto(on: Boolean) {
        prefs.directAuto = on
        _directAuto.value = on
        pushDirectCode()
    }

    /**
     * A new code; a running direct network restarts with it. Secondaries reached only through it
     * drop out and need the new code typed; the others get it handed over.
     */
    fun newDirectCode() {
        _directCode.value = newCode()
        if (direct.state.value != DirectState.Off) direct.start(_directCode.value)
        pushDirectCode()
    }

    private var screensShown = false
    private var screensJob: Job? = null

    /**
     * Screens was opened: if nothing can be reached here, start the direct network, so the others
     * can join it. While it is open, look for secondaries all the time: mDNS before Android 14
     * misses some that come and go (an unpaired one is only there while MTPV is on its screen).
     */
    fun screensOpened() {
        val st = _state.value
        val start = !directStoppedByHand && DirectAuto.startOnScreens(
            _directAuto.value, direct.state.value != DirectState.Off, st.links.values.any { it.online }, st.nearby.size,
        )
        if (start) startDirectByItself()
        discovery?.refresh()
        screensShown = true
        screensJob?.cancel()
        if (active) screensJob = scope.launch(Dispatchers.Main) { scanWhileScreens() }
    }

    fun screensClosed() {
        screensShown = false
        screensJob?.cancel()
        screensJob = null
    }

    private suspend fun scanWhileScreens() {
        while (true) {
            scan()
            delay(SCREENS_SCAN_MS)
        }
    }

    /** What the secondaries get: the code while the network may run, "" otherwise. */
    private fun directCodeToSend(): String =
        if (_directAuto.value || direct.state.value != DirectState.Off) _directCode.value else ""

    private fun pushDirectCode() {
        val code = directCodeToSend()
        links.values.forEach { it.first.setDirectCode(code) }
    }

    /** Hands the code on as the network starts or stops; scans when tablets have joined, to find them at once. */
    private suspend fun followDirect() {
        var clients = 0
        direct.state.collect { s ->
            pushDirectCode()
            val now = (s as? DirectState.Up)?.clients ?: 0
            if (now > clients) scope.launch(Dispatchers.Main) {
                // The new tablet gets its address first; on a slow one, the second scan finds it.
                for (wait in DIRECT_JOIN_SCAN_MS) {
                    delay(wait)
                    scan()
                }
            }
            clients = now
        }
    }

    private fun newCode(): String = DirectCode.generate(SecureRandom()).also { prefs.directCode = it }

    /**
     * Called by discovery (main thread). Known secondaries get their new address, except that one
     * reached in the direct network stays there while it answers ([DirectCode.preferAddress]).
     * Others are listed as nearby only while [open] (paired with no controller), so strangers on a
     * crowded network never join, and a band's paired tablets do not crowd the list.
     */
    private fun onFound(uuid: String, name: String, ep: Endpoint, open: Boolean) {
        val known = _state.value.session.known[uuid]
        if (known != null) {
            val online = _state.value.links[uuid]?.online == true
            val keep = DirectCode.preferAddress(known.host, ep.host, DirectCode.GROUP_PREFIX, online) != ep.host
            val k = if (keep) known.copy(name = name) else known.copy(name = name, host = ep.host, port = ep.port)
            updateSession { s -> s.copy(known = s.known + (uuid to k)) }
        } else if (open) {
            _state.update { it.copy(nearby = it.nearby + (uuid to KnownSecondary(uuid, name, ep.host, ep.port))) }
        } else {
            onLost(uuid)
        }
    }

    /** mDNS lost a secondary: a nearby one is no longer listed, so Pair is not offered for an old address. */
    private fun onLost(uuid: String) {
        _state.update { it.copy(nearby = it.nearby - uuid) }
    }

    fun addByAddress(host: String, port: Int) {
        scope.launch {
            _state.update { it.copy(busy = "Contacting $host…") }
            try {
                val ep = Endpoint(host, port)
                val info = SecondaryClient(ep).info()
                withContext(Dispatchers.Main) {
                    onFound(info.uuid, info.name, ep, info.open)
                    when {
                        _state.value.session.known[info.uuid]?.fingerprint != null -> include(info.uuid)
                        info.open -> pair(info.uuid)
                        else -> showMessage(alreadyPaired(info.name))
                    }
                }
            } catch (e: Exception) {
                showMessage("No MTPV secondary at $host:$port (${e.message})")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * Probes every address of the local networks for secondaries, for networks where mDNS finds
     * nothing (some hotspots, Wi-Fi Direct). Answers count like discovery results.
     */
    private fun scan() {
        if (!active || scanJob?.isActive == true) return
        lastScan = System.currentTimeMillis()
        scanJob = scope.launch {
            _state.update { it.copy(scanning = true) }
            try {
                scanner.scan(Protocol.DEFAULT_PORT) { info, ep ->
                    withContext(Dispatchers.Main) { onFound(info.uuid, info.name, ep, info.open) }
                }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    /**
     * While on screen, checks every second whether the secondaries in the arrangement are reached.
     * Scans now and then while one is not and mDNS has not found it again; less and less often while
     * it stays away (it may just be switched off). Starts and stops the direct network by itself
     * ([DirectAuto]).
     */
    private suspend fun watchScreens() {
        val since = System.currentTimeMillis()
        /** Since when all screens have been reached; null while one is out of reach. */
        var reachableSince: Long? = null
        while (true) {
            val st = _state.value
            val missing = st.session.arrangement.any { it != Paging.SELF && st.links[it]?.online != true }
            val now = System.currentTimeMillis()
            // The first scan waits a moment: the links find most screens by themselves.
            if (missing && now - since >= SCAN_FIRST_MS && now - lastScan >= scanEvery) {
                scan()
                scanEvery = minOf(scanEvery * 2, SCAN_MAX_MS)
            }
            reachableSince = if (missing) null else reachableSince ?: now
            val d = direct.state.value
            if (DirectAuto.controllerStart(_directAuto.value, d != DirectState.Off, directStoppedByHand, missing)) {
                startDirectByItself()
            } else if (DirectAuto.controllerStop(directByItself, (d as? DirectState.Up)?.clients ?: 0, reachableSince?.let { now - it })) {
                directByItself = false
                direct.stop()
            }
            delay(SCAN_CHECK_MS)
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

    /**
     * Asks secondary [uuid] to accept this controller; someone has to tap Allow on it within a minute,
     * after checking that it shows the same code ([PairCode], see [Protocol]).
     */
    fun pair(uuid: String) {
        val st = _state.value
        val k = st.session.known[uuid] ?: st.nearby[uuid] ?: return
        val host = k.host ?: return
        pairJob?.cancel()
        pairJob = scope.launch {
            _state.update { it.copy(pairing = PairingUi(uuid, k.name, null)) }
            try {
                val tls = Tls.pairing()
                val client = SecondaryClient(Endpoint(host, k.port), tls)
                val nonce = PairCode.newNonce(SecureRandom())
                val (requestId, theirs) = client.pairRequest(prefs.deviceUuid, prefs.deviceName, PairCode.commit(nonce))
                val fingerprint = tls.peer ?: throw IOException("no certificate")
                var status = client.pairNonce(requestId, nonce)
                val code = PairCode.derive(fingerprint, Tls.identity().fingerprint, nonce, theirs)
                _state.update { it.copy(pairing = PairingUi(uuid, k.name, code)) }
                val deadline = System.currentTimeMillis() + PAIR_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (status == "pending") {
                        delay(1_000)
                        status = client.pairStatus(requestId)
                    }
                    when (status) {
                        "pending" -> Unit
                        "allowed" -> {
                            withContext(Dispatchers.Main) { paired(k.copy(fingerprint = fingerprint)) }
                            return@launch
                        }
                        "denied" -> {
                            showMessage("${k.name} declined the pairing.")
                            return@launch
                        }
                        else -> break
                    }
                }
                showMessage("No answer from ${k.name}. Try again, and tap Allow on it.")
            } catch (e: AlreadyPairedException) {
                _state.update { it.copy(nearby = it.nearby - uuid) }
                showMessage(alreadyPaired(k.name))
            } catch (e: IOException) {
                // Its address may be outdated (a nearby entry from another network): look for it again.
                val nearby = uuid !in _state.value.session.known
                if (nearby) _state.update { it.copy(nearby = it.nearby - uuid) }
                withContext(Dispatchers.Main) { scan() }
                showMessage(
                    "Could not reach ${k.name} at $host (${e.message}). Searching the network for it; " +
                        if (nearby) "pair it when it shows up under Nearby again." else "try again in a moment."
                )
            } finally {
                _state.update { if (it.pairing?.uuid == uuid) it.copy(pairing = null) else it }
            }
        }
    }

    private fun alreadyPaired(name: String) =
        "$name is paired with another controller. To pair it here, remove that controller on $name first " +
            "(tap its middle or ☰, then Remove under Paired controllers)."

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
        links[k.uuid]?.first?.repaired()
    }

    fun render(fileId: String?, page: Int, w: Int, h: Int, dark: Boolean) = app.renderers.render(fileId, page, w, h, dark)

    // --- notes ---

    /** Notes drawn on the controller's own screen. */
    fun editNotes(key: PageKey, strokes: List<Stroke>) {
        notes.save(key, strokes)
    }

    override fun load(key: PageKey): NotesStore.Page = notes.load(key)

    /** Opens Copy notes from… for the song on the leftmost screen: the other songs with its PDF that have notes. */
    fun showCopyNotes() {
        val s = _state.value.session
        val song = s.set?.let { set -> set.songIndexAt(s.start).takeIf { it >= 0 }?.let { set.songs[it] } } ?: return
        scope.launch {
            val sources = NotesSources.of(folders.all(), song.fileId, song.notesKey)
                .mapNotNull { src -> notes.lastChanged(src.notesKey)?.let { src to it } }
                .sortedByDescending { it.second }
            val ui = CopyNotesUi(song.name, song.notesKey, notes.lastChanged(song.notesKey) != null, sources)
            _state.update { it.copy(copyNotes = ui) }
        }
    }

    fun closeCopyNotes() = _state.update { it.copy(copyNotes = null) }

    /**
     * Replaces the notes of the song in Copy notes from… by a copy of [source]'s. The song gets a
     * new notes ID: the old notes are deleted, so late edits to them from a secondary are dropped,
     * and every screen showing the song gets the copy with its next display command.
     */
    fun copyNotes(source: NotesSource) {
        val ui = _state.value.copyNotes ?: return
        _state.update { it.copy(copyNotes = null) }
        val setId = ui.notesKey.substringBefore('/')
        scope.launch(orderWrites) {
            val record = folders.all().firstOrNull { it.id == setId }
            val entry = record?.songs?.get(ui.song)
            if (entry == null || record.notesKey(ui.song) != ui.notesKey) {
                showMessage("Could not copy the notes: the set has changed meanwhile.")
                return@launch
            }
            val notesId = NotesKey.newId()
            val key = NotesKey.of(setId, notesId)
            notes.copy(source.notesKey, key)
            folders.save(Uri.parse(record.folder), record.copy(songs = record.songs + (ui.song to entry.copy(notesId = notesId))))
            notes.delete(ui.notesKey)
            withContext(Dispatchers.Main) {
                updateSession { s ->
                    val set = s.set ?: return@updateSession s
                    s.copy(set = set.withNotesKey(ui.notesKey, key))
                }
            }
        }
    }

    override fun saveFromSecondary(uuid: String, notes: SecondaryClient.Notes) {
        val key = PageKey(notes.notesKey?.takeIf(NotesKey::isValid) ?: return, notes.page)
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

    fun setDark(on: Boolean) = setTools(_state.value.tools.copy(dark = on))

    /** Picked here or on a secondary (main thread); every screen follows. */
    private fun setTools(t: ToolState) {
        if (t == _state.value.tools) return
        _state.update { it.copy(tools = t) }
        prefs.tools = t
        links.values.forEach { it.first.setTools(t) }
    }

    // --- export and import all app data ---

    /** File name offered for Export. */
    fun backupName(): String = "mtpv-backup-${LocalDate.now()}.zip"

    /** Writes notes, set records and settings to [uri] ([Backup]), after fetching unsaved notes from the screens. */
    fun exportData(uri: Uri) {
        scope.launch(orderWrites) {
            try {
                val missed = flushSecondaries()
                _state.update { it.copy(busy = "Writing backup…") }
                val manifest = JSONObject()
                    .put("format", Backup.FORMAT)
                    .put("app", BuildConfig.VERSION_NAME)
                    .put("commit", BuildConfig.GIT_COMMIT ?: "")
                    .put("created", System.currentTimeMillis())
                    .put("device", prefs.deviceName)
                val settings = JSONObject().put("tools", ToolSync.toJson(_state.value.tools))
                val summary = app.contentResolver.openOutputStream(uri, "w")?.use {
                    Backup.write(it, app.filesDir, manifest.toString(), settings.toString())
                } ?: throw IOException("cannot write the file")
                showMessage("Exported the notes of ${count(summary.songs, "song")} and ${count(summary.sets, "set")}." + missedNote(missed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Export failed: ${e.message}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * Replaces notes, set records and settings by those in the backup at [uri]. The controller then
     * starts afresh, without an open set: opening a set from the library finds its order, ticks and notes.
     */
    fun importData(uri: Uri) {
        scope.launch(orderWrites) {
            val staging = File(app.filesDir, "import.tmp")
            try {
                _state.update { it.copy(busy = "Reading backup…") }
                val u = app.contentResolver.openInputStream(uri)?.use { Backup.unpack(it, staging) }
                    ?: throw IOException("cannot read the file")
                val format = runCatching { JSONObject(u.manifest).getInt("format") }.getOrNull()
                if (format != Backup.FORMAT) {
                    throw Backup.BadBackupException(
                        if (format != null && format > Backup.FORMAT) "it was made by a newer MTPV; update MTPV first" else "not an MTPV backup"
                    )
                }
                val tools = u.settings?.let { s -> runCatching { ToolSync.fromJson(JSONObject(s).getJSONObject("tools")) }.getOrNull() }
                // The secondaries hand in their unsaved notes now, so none turn up later among the imported ones.
                flushSecondaries()
                val next = if (prefs.library == null) {
                    "Now choose the Library folder (in the menu, under Library): its sets get their order, ticks and notes back."
                } else {
                    "Open a set from the library: it gets its order, ticks and notes back."
                }
                withContext(Dispatchers.Main) {
                    app.restartController {
                        Backup.replace(app.filesDir, staging)
                        tools?.let { prefs.tools = it }
                        prefs.saveSession(prefs.loadSession().copy(set = null, folder = null, start = 1))
                    }?.showMessage("Imported the notes of ${count(u.summary.songs, "song")} and ${count(u.summary.sets, "set")}. $next")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Import failed: ${e.message}. Nothing was changed.")
            } finally {
                _state.update { it.copy(busy = null) }
                staging.deleteRecursively()
            }
        }
    }

    /**
     * Fetches unsaved notes from every secondary in the arrangement. Returns the names of those not
     * reached in time: notes still unsaved on them are missed.
     */
    private suspend fun flushSecondaries(): List<String> {
        _state.update { it.copy(busy = "Fetching notes from the screens…") }
        val all = withContext(Dispatchers.Main) { links.mapValues { it.value.first } }
        val known = _state.value.session.known
        return coroutineScope {
            all.map { (uuid, link) ->
                async { uuid.takeIf { withTimeoutOrNull(FLUSH_TIMEOUT_MS) { link.flush() } == null } }
            }.awaitAll().filterNotNull().map { known[it]?.name ?: it.take(4) }
        }
    }

    private fun missedNote(missed: List<String>) =
        if (missed.isEmpty()) "" else " Not reached: ${missed.joinToString()}; notes not yet saved from there are missing."

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun showMessage(m: String) = _state.update { it.copy(message = m) }

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
        if (!active) return
        s.arrangement.forEachIndexed { slot, uuid ->
            if (uuid == Paging.SELF) return@forEachIndexed
            val link = links.getOrPut(uuid) {
                val l = SecondaryLink(
                    uuid, prefs.deviceUuid, cache, versions, this,
                    report = { status ->
                        val was = _state.getAndUpdate { it.copy(links = it.links + (uuid to status)) }.links[uuid]
                        // Lost contact: its address may have changed; older Android does not tell by itself.
                        if (was?.online == true && !status.online) scope.launch(Dispatchers.Main) {
                            discovery?.refresh()
                            scanEvery = SCAN_EVERY_MS
                            lastScan = 0L
                        }
                    },
                    onInput = { event -> scope.launch(Dispatchers.Main) { onInput(uuid, event) } },
                )
                l to l.launchIn(scope)
            }.first
            s.known[uuid]?.let { k ->
                k.host?.let { link.setEndpoint(Endpoint(it, k.port)) }
                link.setFingerprint(k.fingerprint)
            }
            // Without a set to come back to (closed, or none yet), the secondaries go blank.
            link.setTarget(st.targetForSlot(slot) ?: Target(null, null, 0).takeIf { s.folder == null })
            link.setTools(st.tools)
            link.setDirectCode(directCodeToSend())
        }
    }

    private fun onInput(uuid: String, event: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "input from ${uuid.take(4)}: $event")
        when (event) {
            "prev" -> prev()
            "next" -> next()
            else -> ToolSync.apply(_state.value.tools, event)?.let(::setTools)
        }
    }

    companion object {
        private const val TAG = "MtpvController"
        private const val PAIR_TIMEOUT_MS = 60_000L
        private const val FLUSH_TIMEOUT_MS = 5_000L
        /** mDNS gets this long after start before the first scan. */
        private const val SCAN_FIRST_MS = 2_000L
        private const val SCAN_EVERY_MS = 30_000L
        private const val SCAN_MAX_MS = 300_000L
        private const val SCAN_CHECK_MS = 1_000L
        private val DIRECT_JOIN_SCAN_MS = listOf(1_000L, 3_000L)
        private const val SCREENS_SCAN_MS = 10_000L
    }
}
