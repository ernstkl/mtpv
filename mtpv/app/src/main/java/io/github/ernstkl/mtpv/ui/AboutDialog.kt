package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ernstkl.mtpv.BuildConfig

/** What MTPV is and how to set it up; shown from every role, and once on the first start. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        ?.let { name -> BuildConfig.GIT_COMMIT?.let { "$name ($it)" } ?: name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MTPV – Multi-Tablet PDF Viewer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Spreads a PDF across several tablets, one page per tablet.")
                Spacer(Modifier.height(12.dp))
                Text("1. Put all tablets in the same Wi-Fi.")
                Text("2. On the extra tablets, choose Secondary.")
                Text("3. On one tablet, choose Controller and open a PDF. Then Screens → Pair; " +
                    "check that the secondary shows the same code and tap Allow there.")
                Text("4. Tap the right or left third of any screen, or use a Bluetooth pedal: " +
                    "every screen moves on by one page, until the last page is on the leftmost screen.")
                Spacer(Modifier.height(12.dp))
                Text("Song sets: put one PDF per song in a folder, the set folders below one Library folder, " +
                    "then pick a set under Open set from library…. A folder with subfolders opens to show them; ↑ goes back up.")
                Text("The songs follow each other in alphabetical order, like one long PDF. " +
                    "Under Songs, tap a song to jump to it, drag ≡ to change the order, untick a song to leave it out.")
                Text("Controller: tap the middle or ☰ for the menu; ♫ next to it lists the songs (only the controller has it). " +
                    "Secondary: tap the middle or ☰ for its address and settings. " +
                    "If a secondary isn't found, enter its address under Screens.")
                Text("The bottom right corner shows where the page is: \"song 2 of 7: page 1 of 3\", the page counted within its song (just \"page 1 of 3\" for a single PDF).")
                Text("◐ at the top right turns dark pages on or off on all screens: white paper turns black, notes keep their colours.")
                Text("Notes: draw with a stylus; pick pen, marker or eraser in the bar at the bottom left, ↶ undoes. " +
                    "Without a stylus, tap the square finger button (green = on) to draw with a finger; taps on the page then no longer turn it. " +
                    "Tool and finger button change on all screens at once.")
                Text("The set folder is only read, never changed. Order, ticks and notes belong to the set and stay in MTPV, " +
                    "so a new set starts without notes, and a copy of a PDF in another set has notes of its own. " +
                    "Renaming a file keeps its notes; deleting it or changing its content deletes them. " +
                    "Copy notes from… in the menu takes the notes of the same PDF from another song.")
                Text("Moved the library? Pick it again under Library folder: sets keep their order, ticks and notes.")
                Text("When MTPV comes back to the front on one screen, the screens showing another app " +
                    "get a notification; tap it to bring MTPV back there too.")
                Text("Needs Android 10 or newer.")
                Spacer(Modifier.height(12.dp))
                Text(
                    listOfNotNull(version?.let { "Version $it" }, "GPL-3.0-or-later", "github.com/ernstkl/mtpv")
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
