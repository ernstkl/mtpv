package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ernstkl.mtpv.controller.ControllerRuntime
import io.github.ernstkl.mtpv.controller.ControllerState
import io.github.ernstkl.mtpv.core.Library
import io.github.ernstkl.mtpv.core.LibraryTap

/**
 * The library folder, browsed like a file manager. A folder of songs without subfolders opens as
 * a set when tapped; a folder with subfolders is browsed into, and its own PDFs, if any, open from
 * the top row. The open set and the folders on the way to it are marked.
 */
@Composable
fun SetsDialog(rt: ControllerRuntime, st: ControllerState, onChooseLibrary: () -> Unit, onDismiss: () -> Unit) {
    // Read the folder again each time, in case files were added since.
    LaunchedEffect(Unit) { rt.browse() }
    val lib = st.library
    val path = lib?.path.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (path.size > 1) TextButton(onClick = { rt.browse(path.dropLast(1)) }) { Text("↑", style = MaterialTheme.typography.titleLarge) }
                Text(if (path.isEmpty()) "Library" else Library.title(path.map { it.name }), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            when {
                lib == null -> Text(
                    "Choose a library folder: the folder that holds your song sets, one folder of PDFs per set. " +
                        "It may have folders in folders, such as Gigs / 2026.",
                )
                lib.error != null -> Text("Cannot read the library folder. Choose it again, or another one.")
                lib.level == null -> Text("Reading…")
                else -> LazyColumn {
                    val level = lib.level
                    if (level.pdfs > 0) {
                        item {
                            FolderRow(
                                "Show this folder as a set", pdfs(level.pdfs), marked = lib.showsOpenSet,
                            ) { rt.openLibrarySet(path.last()); onDismiss() }
                        }
                    }
                    items(level.folders, key = { it.documentId }) { e ->
                        if (level.pdfs > 0 || e != level.folders.first()) HorizontalDivider()
                        val browse = Library.tapAction(e) == LibraryTap.BROWSE_INTO
                        FolderRow(
                            e.name + if (browse) "  ›" else "",
                            listOfNotNull(e.pdfs.takeIf { it > 0 }?.let(::pdfs), e.folders.takeIf { it > 0 }?.let(::folders)).joinToString(" · "),
                            marked = e.documentId in lib.openPath,
                        ) {
                            if (browse) rt.browse(path + e) else { rt.openLibrarySet(e); onDismiss() }
                        }
                    }
                    if (level.pdfs == 0 && level.folders.isEmpty()) item { Text("No PDFs or folders with PDFs here.") }
                }
            }
        },
        dismissButton = {
            if (lib == null || lib.error != null) TextButton(onClick = onChooseLibrary) { Text("Choose library folder…") }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun FolderRow(title: String, subtitle: String, marked: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (marked) FontWeight.Bold else null,
            color = if (marked) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
        Text(subtitle + if (marked) " · open" else "", style = MaterialTheme.typography.bodySmall)
    }
}

private fun pdfs(n: Int) = if (n == 1) "1 PDF" else "$n PDFs"

private fun folders(n: Int) = if (n == 1) "1 folder" else "$n folders"
