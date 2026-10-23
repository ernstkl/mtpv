package io.github.ernstkl.mtpv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.controller.ControllerState
import io.github.ernstkl.mtpv.controller.LinkStatus
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.Paging
import io.github.ernstkl.mtpv.net.Protocol

private val Online = Color(0xFF2E7D32)
private val Busy = Color(0xFFF9A825)
private val Offline = Color(0xFFC62828)

/**
 * The controller's own page. Tap the left third to go back, the right third to go forward,
 * the middle to show or hide the toolbar. Pedal keys are handled in MainActivity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControllerScreen(rt: ControllerRuntime, onLeaveRole: () -> Unit) {
    val st by rt.state.collectAsStateWithLifecycle()
    val s = st.session
    var showBar by remember { mutableStateOf(s.set == null) }
    var showScreens by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val changes by rt.notesChanges.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(rt::openPdf)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(rt::openFolder)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pageTaps(onTap = { o, size ->
                when {
                    o.x < size.width / 3f -> rt.prev()
                    o.x > size.width * 2 / 3f -> rt.next()
                    else -> showBar = !showBar
                }
            }),
    ) {
        val own = st.ownTarget
        PageView(own?.fileId, own?.page ?: 0, rt::render) { fileId, page ->
            val key = PageKey(fileId, page)
            val strokes = remember(key, changes) { rt.load(key).strokes }
            // While a secondary still holds this page's latest notes, wait for them before drawing.
            NotesLayer(key, strokes, st.tools.tool, enabled = st.holderOf(key) == null, onChange = rt::editNotes, fingers = st.tools.fingers)
        }
        if (own?.fileId != null && own.page > 0) {
            val key = PageKey(own.fileId, own.page)
            val strokes = remember(key, changes) { rt.load(key).strokes }
            NoteToolPicker(
                st.tools.tool, rt::setTool, st.tools.fingers, rt::setFingers,
                canUndo = strokes.isNotEmpty() && st.holderOf(key) == null,
                onUndo = { rt.editNotes(key, strokes.dropLast(1)) },
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }

        if (s.set == null) {
            Text(
                "No PDF open. Tap the middle of the screen for the toolbar.",
                Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        StatusDots(st, Modifier.align(Alignment.TopEnd).padding(6.dp))

        if (showBar) {
            // Title and buttons on separate lines, so a long PDF name fits on narrow screens too.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(s.set?.name ?: "MTPV controller", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(pageSummary(st), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Open PDF") }
                    Button(onClick = { folderPicker.launch(null) }) { Text("Open folder") }
                    Button(onClick = { showScreens = true }) { Text("Screens (${s.arrangement.size})") }
                    TextButton(onClick = onLeaveRole) { Text("Change role") }
                    TextButton(onClick = { showAbout = true }) { Text("?") }
                }
            }
        }

        st.busy?.let {
            Text(it, Modifier.align(Alignment.BottomCenter).background(Color(0xCC000000)).padding(12.dp))
        }
    }

    if (showScreens) ScreensDialog(rt, st) { showScreens = false }
    if (showAbout) AboutDialog { showAbout = false }

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

private fun pageSummary(st: ControllerState): String {
    val s = st.session
    if (s.set == null) return "No PDF open"
    val last = minOf(s.start + s.arrangement.size - 1, st.totalPages)
    return "Pages ${s.start}–$last of ${st.totalPages}"
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

                SectionDivider()
                Text(
"Secondaries on the same Wi-Fi appear under Nearby. Pair one, then tap Allow on it. If it does not " +
                        "appear, enter the address shown on the secondary.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
