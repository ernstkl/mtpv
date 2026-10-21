package io.github.ernstkl.mtpv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
@Composable
fun ControllerScreen(rt: ControllerRuntime, onLeaveRole: () -> Unit) {
    val st by rt.state.collectAsStateWithLifecycle()
    val s = st.session
    var showBar by remember { mutableStateOf(s.fileId == null) }
    var showScreens by remember { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf(NoteTool.PEN_BLACK) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(rt::openPdf)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    when {
                        o.x < size.width / 3f -> rt.prev()
                        o.x > size.width * 2 / 3f -> rt.next()
                        else -> showBar = !showBar
                    }
                }
            },
    ) {
        PageView(s.fileId, st.ownPage, rt::render) { fileId, page ->
            val key = PageKey(fileId, page)
            val changes by rt.notesChanges.collectAsStateWithLifecycle()
            val strokes = remember(key, changes) { rt.load(key).strokes }
            // While a secondary still holds this page's latest notes, wait for them before drawing.
            NotesLayer(strokes, tool, enabled = st.holderOf(key) == null, onChange = { rt.editNotes(key, it) })
        }
        if (s.fileId != null) NoteToolPicker(tool, { tool = it }, Modifier.align(Alignment.BottomStart).padding(8.dp))

        if (s.fileId == null) {
            Text(
                "No PDF open. Tap the middle of the screen for the toolbar.",
                Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        StatusDots(st, Modifier.align(Alignment.TopEnd).padding(6.dp))

        if (showBar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.fileName ?: "MTPV controller", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(pageSummary(st), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Open PDF") }
                Button(onClick = { showScreens = true }) { Text("Screens (${s.arrangement.size})") }
                TextButton(onClick = onLeaveRole) { Text("Change role") }
            }
        }

        st.busy?.let {
            Text(it, Modifier.align(Alignment.BottomCenter).background(Color(0xCC000000)).padding(12.dp))
        }
    }

    if (showScreens) ScreensDialog(rt, st) { showScreens = false }

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
    if (s.fileId == null) return "No PDF open"
    val last = minOf(s.start + s.arrangement.size - 1, s.pageCount)
    return "Pages ${s.start}–$last of ${s.pageCount}"
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
    l == null || !l.online -> Offline
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (id != Paging.SELF) {
                            Box(Modifier.size(10.dp).background(colorFor(st.links[id]), CircleShape))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(if (id == Paging.SELF) "This device" else nameOf(st, id))
                            Text(
                                (if (s.fileId == null) "" else if (page == 0) "blank · " else "page $page · ") +
                                    if (id == Paging.SELF) "controller" else describe(st.links[id]),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { rt.move(slot, -1) }, enabled = slot > 0) { Text("◀") }
                        TextButton(onClick = { rt.move(slot, +1) }, enabled = slot < s.arrangement.lastIndex) { Text("▶") }
                        if (id != Paging.SELF) TextButton(onClick = { rt.exclude(id) }) { Text("Remove") }
                    }
                }

                val others = s.known.keys.filter { it !in s.arrangement }
                if (others.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Known, not used", style = MaterialTheme.typography.labelLarge)
                    others.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nameOf(st, id), Modifier.weight(1f))
                            TextButton(onClick = { rt.include(id) }) { Text("Add") }
                            TextButton(onClick = { rt.forget(id) }) { Text("Forget") }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Secondaries on the same Wi-Fi appear automatically. If not, enter the address shown on the secondary.",
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

private fun nameOf(st: ControllerState, uuid: String): String =
    "${st.session.known[uuid]?.name ?: "Unknown"} (${uuid.take(4)})"

private fun describe(l: LinkStatus?): String = when {
    l == null -> "connecting…"
    l.upload != null -> "sending PDF ${(l.upload * 100).toInt()}%"
    l.online -> "online"
    else -> "offline" + (l.error?.let { ": $it" } ?: "")
}
