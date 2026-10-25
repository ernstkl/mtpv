package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ernstkl.mtpv.BuildConfig

/** A topic of the About dialog: one line on the overview, [details] on a page of its own. */
private class AboutSection(val title: String, val summary: String, val details: List<String>)

private val Sections = listOf(
    AboutSection(
        "Setting up",
        "Same Wi-Fi; extra tablets are Secondary, one is Controller; pair them under Screens.",
        listOf(
            "1. Put all tablets in the same Wi-Fi.",
            "2. On the extra tablets, choose Secondary.",
            "3. On one tablet, choose Controller and open a PDF. Then Screens → Pair; " +
                "check that the secondary shows the same code and tap Allow there.",
            "Screens also sets the order of the screens, left to right. If a secondary isn't found, enter its address there.",
            "Needs Android 10 or newer.",
        ),
    ),
    AboutSection(
        "Turning pages",
        "Tap the left or right third of any screen, or use a Bluetooth pedal.",
        listOf(
            "Every screen moves on by one page, until the last page is on the leftmost screen.",
            "Controller: tap the middle or ☰ for the menu; ♫ next to it lists the songs (only the controller has it). " +
                "Secondary: tap the middle or ☰ for its address and settings.",
            "The bottom right corner shows where the page is: \"song 2 of 7: page 1 of 3\", " +
                "the page counted within its song (just \"page 1 of 3\" for a single PDF).",
            "◐ at the top right turns dark pages on or off on all screens: white paper turns dark gray, notes light, colours stay.",
            "When MTPV comes back to the front on one screen, the screens showing another app " +
                "get a notification; tap it to bring MTPV back there too.",
        ),
    ),
    AboutSection(
        "Song sets",
        "One PDF per song in a folder below the library folder, shown like one long PDF.",
        listOf(
            "Put one PDF per song in a folder, the set folders below one library folder (menu → Library → Library folder), " +
                "then pick a set under Open set from library…. A folder with subfolders opens to show them; ↑ goes back up.",
            "The songs follow each other in alphabetical order. " +
                "Under Songs, tap a song to jump to it, drag ≡ to change the order, untick a song to leave it out.",
            "The set folder is only read, never changed. Order, ticks and notes stay in MTPV.",
            "Moved the library? Pick it again under Library → Library folder: sets keep their order, ticks and notes.",
        ),
    ),
    AboutSection(
        "Notes",
        "Draw with a stylus, or with a finger after tapping the finger button.",
        listOf(
            "Pick pen, marker or eraser in the bar at the bottom left; ↶ undoes.",
            "Without a stylus, tap the square finger button (green = on) to draw with a finger; " +
                "taps on the page then no longer turn it. Tool and finger button change on all screens at once.",
            "Notes belong to the song in its set: a new set starts without notes, and a copy of a PDF in another set " +
                "has notes of its own. Renaming a file keeps its notes; deleting it or changing its content deletes them.",
            "Copy notes from… in the menu takes the notes of the same PDF from another song.",
        ),
    ),
    AboutSection(
        "Backup and new tablets",
        "Library → Export all data… saves notes, order and ticks to one file.",
        listOf(
            "Export all data… saves the notes, song order and ticks of all sets to one zip file, " +
                "after fetching unsaved notes from the secondaries. The PDFs stay in the library.",
            "Import all data… on a new install brings them back; it replaces what is there. " +
                "Then pick the library folder again, and pair the screens again.",
        ),
    ),
)

/**
 * What MTPV is and how to set it up; shown from every role, and once on the first start. An overview
 * of short sections, each with a page of details.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        ?.let { name -> BuildConfig.GIT_COMMIT?.let { "$name ($it)" } ?: name }
    var open by remember { mutableStateOf<AboutSection?>(null) }
    val section = open
    AlertDialog(
        // Back on a details page goes back to the overview.
        onDismissRequest = { if (section != null) open = null else onDismiss() },
        title = { Text(section?.title ?: "MTPV – Multi-Tablet PDF Viewer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (section != null) {
                    section.details.forEach { Text(it) }
                } else {
                    Text("Spreads a PDF across several tablets, one page per tablet.")
                    Sections.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open = s }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.titleSmall)
                                Text(s.summary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { open = s }) { Text("More ›") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(version?.let { "Version $it" }, "GPL-3.0-or-later", "github.com/ernstkl/mtpv")
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = section?.let { { TextButton(onClick = { open = null }) { Text("‹ Back") } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
