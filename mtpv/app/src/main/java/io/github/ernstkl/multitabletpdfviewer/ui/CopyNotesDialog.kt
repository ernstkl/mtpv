package io.github.ernstkl.multitabletpdfviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ernstkl.multitabletpdfviewer.controller.CopyNotesUi
import io.github.ernstkl.multitabletpdfviewer.core.NotesSource
import java.text.DateFormat
import java.util.Date

/**
 * Copy notes from…: the other songs with exactly this song's PDF that have notes, in any set,
 * newest first. The copy replaces the song's own notes; after that each keeps its own.
 */
@Composable
fun CopyNotesDialog(ui: CopyNotesUi, onCopy: (NotesSource) -> Unit, onDismiss: () -> Unit) {
    var picked by remember(ui) { mutableStateOf(ui.sources.firstOrNull()?.first) }
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy notes to ${songTitle(ui.song)}") },
        text = {
            Column {
                if (ui.sources.isEmpty()) {
                    Text("No other song with exactly this PDF has notes.")
                    return@Column
                }
                Text(
                    if (ui.hasNotes) "This song's own notes are replaced by the copy." else "Copy the notes of:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(ui.sources, key = { it.first.notesKey }) { (src, changed) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = src },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = src == picked, onClick = { picked = src })
                            Column {
                                Text(songTitle(src.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${src.set} · ${date.format(Date(changed))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (ui.sources.isNotEmpty()) {
                TextButton(onClick = { picked?.let(onCopy) }, enabled = picked != null) {
                    Text(if (ui.hasNotes) "Replace notes" else "Copy")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (ui.sources.isEmpty()) "OK" else "Cancel") } },
    )
}
