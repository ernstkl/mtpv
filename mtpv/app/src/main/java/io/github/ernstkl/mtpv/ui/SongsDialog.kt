package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.controller.ControllerState

/** A song's file name without ".pdf". */
fun songTitle(name: String) = if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name

/** The set as a table of contents: tap a song to show it, ▲ ▼ to change the order. */
@Composable
fun SongsDialog(rt: ControllerRuntime, st: ControllerState, onDismiss: () -> Unit) {
    val s = st.session
    val set = s.set ?: return
    val onScreen = (0 until st.slots).map { set.songIndexAt(st.pageForSlot(it)) }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(set.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                set.songs.forEachIndexed { i, song ->
                    if (i > 0) HorizontalDivider()
                    val shown = i in onScreen
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { rt.jumpToSong(i); onDismiss() }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "${i + 1}. ${songTitle(song.name)}",
                                fontWeight = if (shown) FontWeight.Bold else null,
                                color = if (shown) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                            Text(
                                (if (song.pageCount == 1) "1 page" else "${song.pageCount} pages") + if (shown) " · on screen" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { rt.moveSong(i, -1) }, enabled = i > 0) { Text("▲") }
                        TextButton(onClick = { rt.moveSong(i, +1) }, enabled = i < set.songs.lastIndex) { Text("▼") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap a song to show it from the leftmost screen." +
                        if (s.folder != null) " Reload folder picks up PDFs added to or removed from the folder." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        dismissButton = {
            if (s.folder != null) TextButton(onClick = { rt.reloadFolder(); onDismiss() }) { Text("Reload folder") }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
