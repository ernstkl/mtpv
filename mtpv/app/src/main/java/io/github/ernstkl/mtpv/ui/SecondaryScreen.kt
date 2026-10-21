package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.secondary.SecondaryRuntime
import kotlinx.coroutines.delay

/**
 * Full-screen page. Until a controller sends something it shows how to reach this device.
 * Tapping the left or right third turns pages via the controller; long press for info.
 */
@Composable
fun SecondaryScreen(rt: SecondaryRuntime, onLeaveRole: () -> Unit) {
    val display by rt.display.collectAsStateWithLifecycle()
    val lastContact by rt.lastContact.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val connected = now - lastContact < 10_000
    var showInfo by remember { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf(NoteTool.PEN_BLACK) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pageTaps(
                onTap = { o, size ->
                    when {
                        o.x < size.width / 3f -> rt.sendInput("prev")
                        o.x > size.width * 2 / 3f -> rt.sendInput("next")
                    }
                },
                onLongPress = { showInfo = true },
            ),
    ) {
        PageView(display.fileId, display.page, rt::render) { fileId, page ->
            val notes by rt.notes.collectAsStateWithLifecycle()
            if (notes.fileId == fileId && notes.page == page) {
                NotesLayer(
                    PageKey(fileId, page), notes.strokes, tool, enabled = true,
                    onChange = rt::editNotes, onPen = rt::setPenDown,
                )
            }
        }
        if (display.fileId != null && display.page > 0) {
            NoteToolPicker(tool, { tool = it }, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }

        if (display.fileId == null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MTPV secondary", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                DeviceInfo(rt, connected)
            }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(8.dp)
                .background(if (connected) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape),
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("This secondary") },
            text = { DeviceInfo(rt, connected) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = onLeaveRole) { Text("Change role") } },
        )
    }
}

@Composable
private fun DeviceInfo(rt: SecondaryRuntime, connected: Boolean) {
    val display by rt.display.collectAsStateWithLifecycle()
    Column {
        Text(if (connected) "Connected to controller" else "Waiting for controller…")
        Text("Address: " + rt.addresses().joinToString { "$it:${rt.port}" }.ifEmpty { "no network" })
        Text("ID: ${rt.info().getString("uuid").take(8)}  Name: ${rt.info().getString("name")}")
        Text("Showing: " + if (display.fileId == null) "nothing" else "page ${display.page}")
        Text("Cached PDFs: ${rt.cache.ids().size}")
    }
}
