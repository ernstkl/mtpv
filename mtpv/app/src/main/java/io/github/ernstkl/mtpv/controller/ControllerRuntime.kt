package io.github.ernstkl.mtpv.controller

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.ernstkl.mtpv.ControllerSession
import io.github.ernstkl.mtpv.FrontNotification
import io.github.ernstkl.mtpv.KnownSecondary
import io.github.ernstkl.mtpv.MtpvApp
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
import io.github.ernstkl.mtpv.net.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

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
    /** Writes of a folder's song order, one after the other, so the last one wins. */
    private val orderWrites = Dispatchers.IO.limitedParallelism(1)
    private val versions = VersionClock({ prefs.lastVersion }, { prefs.lastVersion = it })
    private val links = mutableMapOf<String, Pair<SecondaryLink, Job>>()
    private var discovery: Discovery? = null

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
        discovery = Discovery(app, ::onFound).also { it.start() }
        syncLinks()
        if (_state.value.session.set == null) reloadFolder() else relocateFolder()
    }

    fun stop() {
        discovery?.stop()
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

    /** A library folder picked with the system's folder picker; the Sets dialog starts at its top. */
    fun setLibrary(tree: Uri) {
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
                    report = { status ->
                        val was = _state.getAndUpdate { it.copy(links = it.links + (uuid to status)) }.links[uuid]
                        // Lost contact: its address may have changed; older Android does not tell by itself.
                        if (was?.online == true && !status.online) scope.launch(Dispatchers.Main) { discovery?.refresh() }
                    },
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
