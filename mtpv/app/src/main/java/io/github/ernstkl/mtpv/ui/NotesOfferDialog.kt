package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ernstkl.mtpv.controller.NotesOfferUi
import java.text.DateFormat
import java.util.Date

/**
 * New songs whose PDF has notes in another song: one list for the whole folder, every song ticked.
 * Ticked songs start with a copy of those notes, the others without. Asked once per song, before
 * the set is shown; Cancel does not open the set.
 */
@Composable
fun NotesOfferDialog(ui: NotesOfferUi, onAnswer: (Set<String>?) -> Unit) {
    var take by remember(ui) { mutableStateOf(ui.offers.map { it.name }.toSet()) }
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Notes from other copies") },
        text = {
            Column {
                Text(
                    "These PDFs of ${ui.set} have notes elsewhere. Ticked songs start with a copy of them; " +
                        "after that, each copy keeps its own notes. You are asked only once.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(ui.offers, key = { it.name }) { o ->
                        val ticked = o.name in take
                        Row(
                            Modifier.fillMaxWidth().clickable { take = if (ticked) take - o.name else take + o.name },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = ticked, onCheckedChange = { take = if (it) take + o.name else take - o.name })
                            Column {
                                Text(songTitle(o.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "from ${o.set} / ${songTitle(o.file)} · ${date.format(Date(o.changed))}",
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
        confirmButton = { TextButton(onClick = { onAnswer(take) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}
