package io.github.ernstkl.mtpv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.controller.ControllerState
import io.github.ernstkl.mtpv.controller.LinkStatus
import io.github.ernstkl.mtpv.core.DirectCode
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.Paging
import io.github.ernstkl.mtpv.net.DirectState
import io.github.ernstkl.mtpv.net.DirectWifi
import io.github.ernstkl.mtpv.net.Protocol
import kotlinx.coroutines.launch

private val Online = Color(0xFF2E7D32)
private val Busy = Color(0xFFF9A825)
private val Offline = Color(0xFFC62828)

/** "Open folder as set" is hidden for now: next to "Open set from library…" it puzzles new users. */
private const val OPEN_FOLDER_ITEM = false

/**
 * The controller's own page. Tap the left third to go back, the right third to go forward,
 * the middle (or ☰) to open the menu drawer. Pedal keys are handled in MainActivity.
 */
@Composable
fun ControllerScreen(rt: ControllerRuntime, onLeaveRole: () -> Unit) {
    val st by rt.state.collectAsStateWithLifecycle()
    val s = st.session
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showScreens by remember { mutableStateOf(false) }
    var showDirect by remember { mutableStateOf(false) }
    val direct by rt.direct.state.collectAsStateWithLifecycle()
    val directCode by rt.directCode.collectAsStateWithLifecycle()
    val directAuto by rt.directAuto.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Started, or may start by itself because screens are paired.
    AskDirectPermission(
        !DirectWifi.hasPermission(context) && (direct != DirectState.Off || (directAuto && s.arrangement.size > 1)),
        onAnswered = { if (direct != DirectState.Off) rt.startDirect() },
    )
    var showSongs by remember { mutableStateOf(false) }
    var showSets by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(st.library == null) }
    var confirmImport by remember { mutableStateOf(false) }
    val changes by rt.notesChanges.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(rt::openPdf)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(rt::openPickedFolder)
    }
    val libraryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(rt::setLibrary)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(rt::exportData)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(rt::importData)
    }
    /** Closes the drawer, then does [action]. */
    fun menu(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }

    // No edge swipe: it would clash with the pen and with page turns. Open by tap, close by swipe or tap outside.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                        Text(s.set?.name ?: "MTPV controller", style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(pageSummary(st), style = MaterialTheme.typography.bodySmall)
                    }
                    s.set?.let { set ->
                        if (hasSongs(st)) {
                            val count = if (set.shownSongs == set.songs.size) "${set.songs.size}" else "${set.shownSongs}/${set.songs.size}"
                            MenuItem("Songs ($count)") { menu { showSongs = true } }
                        }
                        set.songIndexAt(s.start).takeIf { it >= 0 }?.let { i ->
                            MenuItem("Copy notes from…", songTitle(set.songs[i].name)) { menu(rt::showCopyNotes) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MenuItem("Open set from library…") { menu { showSets = true } }
                    if (OPEN_FOLDER_ITEM) MenuItem("Open folder as set") { menu { folderPicker.launch(null) } }
                    MenuItem("Open single PDF") { menu { picker.launch(arrayOf("application/pdf")) } }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MenuItem("Screens (${s.arrangement.size})") { menu { showScreens = true } }
                    MenuItem("Direct network", directSubtitle(direct, directCode, directAuto)) { menu { showDirect = true } }
                    MenuItem("Device role", "Controller") { menu(onLeaveRole) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MenuGroup("Library", showLibrary) { showLibrary = !showLibrary }
                    if (showLibrary) {
                        MenuItem("Library folder", st.library?.name ?: "Not chosen yet", indent = true) { menu { libraryPicker.launch(null) } }
                        MenuItem("Export all data…", "Notes, song order and ticks, to one file", indent = true) {
                            menu { exportPicker.launch(rt.backupName()) }
                        }
                        MenuItem("Import all data…", "Replaces them with a file from Export", indent = true) {
                            menu { confirmImport = true }
                        }
                    }
                    MenuItem("About") { menu { showAbout = true } }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pageTaps(onTap = { o, size ->
                    when {
                        o.x < size.width / 3f -> rt.prev()
                        o.x > size.width * 2 / 3f -> rt.next()
                        else -> scope.launch { drawer.open() }
                    }
                }),
        ) {
            val own = st.ownTarget
            PageView(own?.fileId, own?.page ?: 0, rt::render, own?.position?.label, dark = st.tools.dark) { fileId, page ->
                // The notes of the song shown, once its page is drawn (two songs may share a PDF).
                val notesKey = own?.notesKey?.takeIf { own.fileId == fileId && own.page == page }
                if (notesKey != null) {
                    val key = PageKey(notesKey, page)
                    val strokes = remember(key, changes) { rt.load(key).strokes }
                    // While a secondary still holds this page's latest notes, wait for them before drawing.
                    NotesLayer(key, strokes, st.tools.tool, enabled = st.holderOf(key) == null, onChange = rt::editNotes,
                        fingers = st.tools.fingers, dark = st.tools.dark)
                }
            }
            if (own?.notesKey != null && own.fileId != null && own.page > 0) {
                val key = PageKey(own.notesKey, own.page)
                val strokes = remember(key, changes) { rt.load(key).strokes }
                NoteToolPicker(
                    st.tools.tool, rt::setTool, st.tools.fingers, rt::setFingers,
                    canUndo = strokes.isNotEmpty() && st.holderOf(key) == null,
                    onUndo = { rt.editNotes(key, strokes.dropLast(1)) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }

            if (s.set == null) {
                Text("Tap ☰ to choose a set.", Modifier.align(Alignment.Center).padding(32.dp))
            }

            Row(Modifier.align(Alignment.TopStart)) {
                MenuButton({ scope.launch { drawer.open() } })
                SongsButton({ if (hasSongs(st)) showSongs = true else showSets = true })
            }

            Row(Modifier.align(Alignment.TopEnd)) {
                DarkPagesButton(st.tools.dark, rt::setDark)
                StatusDots(st, Modifier.padding(6.dp))
            }

            st.busy?.let {
                Text(it, Modifier.align(Alignment.BottomCenter).background(Color(0xCC000000)).padding(12.dp))
            }
        }
    }

    if (showScreens) ScreensDialog(rt, st) { showScreens = false }
    if (showDirect) DirectNetworkDialog(rt) { showDirect = false }
    if (showSets) SetsDialog(rt, st, onChooseLibrary = { libraryPicker.launch(null) }) { showSets = false }
    if (showSongs) SongsDialog(rt, st) { showSongs = false }
    if (showAbout) AboutDialog { showAbout = false }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import all data?") },
            text = {
                Text("The notes, song order and ticks of all sets on this device are replaced by those in the file " +
                    "you pick next. Export first if you want to keep them.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    // Some file managers show zip files as application/octet-stream.
                    importPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
    st.copyNotes?.let { CopyNotesDialog(it, rt::copyNotes, rt::closeCopyNotes) }

    st.pairing?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pairing with ${p.name}") },
            text = {
                Column {
                    if (p.code == null) {
                        Text("Contacting ${p.name}…")
                    } else {
                        Text("On ${p.name}, check that it shows this code and tap Allow:")
                        Spacer(Modifier.height(12.dp))
                        Text(p.code, style = MaterialTheme.typography.displayMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = rt::cancelPairing) { Text("Cancel") } },
        )
    }

    st.message?.let { msg ->
        AlertDialog(
            onDismissRequest = rt::clearMessage,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = rt::clearMessage) { Text("OK") } },
        )
    }
}

/** A drawer item that opens or closes the items of its group below it. */
@Composable
private fun MenuGroup(label: String, open: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        badge = { Text(if (open) "▾" else "▸") },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** A drawer item, with an optional second line; [indent] for one in a [MenuGroup]. */
@Composable
private fun MenuItem(label: String, subtitle: String? = null, indent: Boolean = false, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(label)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(start = if (indent) 28.dp else 12.dp, end = 12.dp),
    )
}

/** The open set is worth a song list: read from a folder, or more than one song. */
private fun hasSongs(st: ControllerState): Boolean {
    val set = st.session.set ?: return false
    return st.session.folder != null || set.songs.size > 1
}

private fun pageSummary(st: ControllerState): String {
    val s = st.session
    val set = s.set ?: return "No PDF open"
    val last = minOf(s.start + s.arrangement.size - 1, st.totalPages)
    val pages = "Pages ${s.start}–$last of ${st.totalPages}"
    if (set.shownSongs == 0) return "All songs are left out; tick them under Songs"
    if (set.songs.size < 2) return pages
    val i = set.songIndexAt(s.start)
    return "Song ${set.numberOf(i)} of ${set.shownSongs}: ${songTitle(set.songs[i].name)} · $pages"
}

/** One dot per secondary, in screen order: green shows the right page, yellow is busy, red is unreachable. */
@Composable
private fun StatusDots(st: ControllerState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        st.session.arrangement.filter { it != Paging.SELF }.forEach { uuid ->
            Box(Modifier.size(8.dp).background(colorFor(st.links[uuid]), CircleShape))
        }
    }
}

private fun colorFor(l: LinkStatus?): Color = when {
    l == null || !l.online || l.notPaired -> Offline
    l.upload != null -> Busy
    else -> Online
}

@Composable
private fun ScreensDialog(rt: ControllerRuntime, st: ControllerState, onDismiss: () -> Unit) {
    val s = st.session
    DisposableEffect(rt) {
        rt.screensOpened()
        onDispose(rt::screensClosed)
    }
    var host by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Screens, left to right") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                s.arrangement.forEachIndexed { slot, id ->
                    val page = st.pageForSlot(slot)
                    // Name and status get the full width; the buttons go on their own line below.
                    if (slot > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (id != Paging.SELF) {
                            Box(Modifier.size(10.dp).background(colorFor(st.links[id]), CircleShape))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column {
                            Text(if (id == Paging.SELF) "This device" else nameOf(st, id))
                            Text(
                                (if (s.set == null) "" else if (page == 0) "blank · " else "page $page · ") +
                                    if (id == Paging.SELF) "controller" else describe(st.links[id]),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { rt.move(slot, -1) }, enabled = slot > 0) { Text("◀") }
                        TextButton(onClick = { rt.move(slot, +1) }, enabled = slot < s.arrangement.lastIndex) { Text("▶") }
                        if (id != Paging.SELF) {
                            if (st.links[id]?.notPaired == true) TextButton(onClick = { rt.pair(id) }) { Text("Pair") }
                            TextButton(onClick = { rt.exclude(id) }) { Text("Remove") }
                        }
                    }
                }

                val nearby = st.nearby.values.filter { it.uuid !in s.known }
                if (nearby.isNotEmpty()) {
                    SectionDivider()
                    Text("Nearby, not paired", style = MaterialTheme.typography.labelLarge)
                    nearby.forEachIndexed { i, k ->
                        if (i > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${k.name} (${k.uuid.take(4)})", Modifier.weight(1f))
                            TextButton(onClick = { rt.pair(k.uuid) }) { Text("Pair") }
                        }
                    }
                }

                val others = s.known.keys.filter { it !in s.arrangement }
                if (others.isNotEmpty()) {
                    SectionDivider()
                    Text("Known, not used", style = MaterialTheme.typography.labelLarge)
                    others.forEachIndexed { i, id ->
                        if (i > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nameOf(st, id), Modifier.weight(1f))
                            TextButton(onClick = { rt.include(id) }) { Text("Add") }
                            TextButton(onClick = { rt.forget(id) }) { Text("Forget") }
                        }
                    }
                }

                val direct by rt.direct.state.collectAsStateWithLifecycle()
                val code by rt.directCode.collectAsStateWithLifecycle()
                if (direct != DirectState.Off && nearby.isEmpty()) {
                    SectionDivider()
                    Text("Direct network on, code ${DirectCode.format(code)}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Paired secondaries join it by themselves. Another one: ☰ → Join direct network… on it, " +
                            "type the code, and it shows up under Nearby.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SectionDivider()
                Text(
"Secondaries with MTPV on screen appear under Nearby; this keeps searching while it is open. " +
                        "Pair one, then tap Allow on it. If it does not appear, enter the address shown on it. " +
                        "Without a shared Wi-Fi, the direct network starts by itself (Direct network in the menu).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(if (st.scanning) "Searching the network…" else " ", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim() },
                        label = { Text("IP[:port]") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val h = host.substringBeforeLast(':').takeIf { ':' in host } ?: host
                            val p = host.substringAfterLast(':', "").toIntOrNull() ?: Protocol.DEFAULT_PORT
                            rt.addByAddress(h, p)
                            host = ""
                        },
                        enabled = host.isNotEmpty(),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Between the sections of the Screens dialog; stronger than the thin lines between devices. */
@Composable
private fun SectionDivider() =
    HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.outline)

private fun nameOf(st: ControllerState, uuid: String): String =
    "${st.session.known[uuid]?.name ?: "Unknown"} (${uuid.take(4)})"

private fun describe(l: LinkStatus?): String = when {
    l == null -> "connecting…"
    l.upload != null -> "sending PDF ${(l.upload * 100).toInt()}%"
    l.notPaired -> "not paired"
    l.online -> "online"
    else -> "offline" + (l.error?.let { ": $it" } ?: "")
}
