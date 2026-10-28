package io.github.ernstkl.multitabletpdfviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ernstkl.multitabletpdfviewer.controller.ControllerRuntime
import io.github.ernstkl.multitabletpdfviewer.controller.ControllerState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A song's file name without ".pdf". */
fun songTitle(name: String) = if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name

/**
 * The set as a table of contents: tap a song to show it, drag ≡ to change the order, untick a
 * song to leave it out of the pages shown.
 */
@Composable
fun SongsDialog(rt: ControllerRuntime, st: ControllerState, onDismiss: () -> Unit) {
    val s = st.session
    val set = s.set ?: return
    val onScreen = (0 until st.slots).map { set.songIndexAt(st.pageForSlot(it)) }.toSet()
    // The order while a drag is going on; it is applied to the set when the song is dropped.
    var order by remember(set) { mutableStateOf(set.songs.indices.toList()) }
    var dragFrom by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(set.name) },
        text = {
            Column {
                LazyColumn(Modifier.weight(1f, fill = false), state = listState) {
                    items(order, key = { set.songs[it].name }) { i ->
                        val song = set.songs[i]
                        val shown = i in onScreen
                        ReorderableItem(reorder, key = song.name) { dragging ->
                            Column(Modifier.background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)) {
                                if (order.first() != i) HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val titleColor = when {
                                        song.excluded -> MaterialTheme.colorScheme.onSurfaceVariant
                                        shown -> MaterialTheme.colorScheme.primary
                                        else -> Color.Unspecified
                                    }
                                    // The number in its own column, so it does not read as part of the title.
                                    Text(
                                        if (song.excluded) "" else "${set.numberOf(i)}",
                                        Modifier.width(28.dp),
                                        maxLines = 1,
                                        textAlign = TextAlign.End,
                                        fontWeight = if (shown) FontWeight.Bold else null,
                                        color = titleColor,
                                    )
                                    Checkbox(checked = !song.excluded, onCheckedChange = { rt.excludeSong(i, !it) })
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable(enabled = !song.excluded) { rt.jumpToSong(i); onDismiss() }
                                            .padding(vertical = 8.dp),
                                    ) {
                                        Text(
                                            songTitle(song.name),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (shown) FontWeight.Bold else null,
                                            color = titleColor,
                                        )
                                        Text(
                                            (if (song.pageCount == 1) "1 page" else "${song.pageCount} pages") +
                                                when {
                                                    song.excluded -> " · left out"
                                                    shown -> " · on screen"
                                                    else -> ""
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "Drag to move",
                                        modifier = Modifier
                                            .draggableHandle(
                                                onDragStarted = { dragFrom = order.indexOf(i) },
                                                onDragStopped = {
                                                    val to = order.indexOf(i)
                                                    if (dragFrom >= 0 && to != dragFrom) rt.moveSong(dragFrom, to - dragFrom)
                                                    dragFrom = -1
                                                },
                                            )
                                            .padding(12.dp)
                                            .size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap a song to show it from the leftmost screen, drag ≡ to move it. " +
                        "Untick a song to leave it out of the pages; it stays in this list." +
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
