package io.github.ernstkl.multitabletpdfviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import io.github.ernstkl.multitabletpdfviewer.BuildConfig
import io.github.ernstkl.multitabletpdfviewer.R

/**
 * A topic of the help dialog: one line on the overview, [details] on a page of its own, below them
 * the image [image] if there is one.
 */
private class HelpSection(val title: String, val summary: String, val details: List<String>, val image: Int? = null)

private val Sections = listOf(
    HelpSection(
        "Setting up",
        "One tablet alone: Controller. More: same Wi-Fi (or a direct network), extras are Secondary, paired under Screens.",
        listOf(
            "1. Put all tablets in the same Wi-Fi, or see Direct network below.",
            "2. On the extra tablets, choose Secondary.",
            "3. On one tablet, choose Controller and open a PDF. Then Screens → Pair; " +
                "check that the secondary shows the same 6-digit code and tap Allow there. " +
                "Matching codes mean no other device is in between; everything between the tablets is encrypted.",
            "Screens also sets the order of the screens, left to right: drag = up or down. " +
                "Screens keeps searching while it is open; if a secondary isn't found, enter its address there. " +
                "MTPV must be on its screen.",
            "A controller and its secondaries need MTPV with the same API version. If one differs, the controller says so with a list of all screens; update MTPV on all of them.",
            "A paired secondary is no longer offered to other controllers. To pair it with another one, " +
                "remove its controller on it first (☰ → Paired controllers → Remove).",
            "No Wi-Fi the tablets can share? The controller then starts its own direct network, and paired secondaries " +
                "join it by themselves. Wi-Fi must be on, but no Wi-Fi network is needed. Up to Android 12 " +
                "this needs the location permission; MTPV never reads your location.",
            "A secondary never paired, with no Wi-Fi to share: ☰ → Join direct network… on it, type the code " +
                "shown on the controller under Screens, then pair as in 3.",
            "Only one tablet: choose Controller and open a PDF; no Wi-Fi or pairing needed.",
            "Needs Android 10 or newer.",
        ),
    ),
    HelpSection(
        "Turning pages",
        "Tap the left or right third of any screen, swipe, or use a Bluetooth pedal.",
        listOf(
            "Every screen moves on by one page, until the last page is on the leftmost screen.",
            "Controller: tap the middle or ☰ for the menu; ♫ next to it lists the songs (only the controller has it). " +
                "Secondary: tap the middle or ☰ for its address and settings.",
            "The bar at the top shows where the page is: \"Song 2 of 7 | Page 1 of 3\", " +
                "the page counted within its song (just \"Page 1 of 3\" for a single PDF).",
            "On the leftmost screen, « and » around the song number show the previous or next song from there.",
            "◐ at the top right turns dark pages on or off on all screens: white paper turns dark gray, notes light, colors stay.",
            "MTPV works on a tablet only while it is on screen there. Switched to another app, that tablet " +
                "pauses and uses no battery; back in MTPV, it catches up within seconds.",
        ),
    ),
    HelpSection(
        "Song sets",
        "One PDF per song in a folder below the library folder, shown like one long PDF.",
        listOf(
            "Put one PDF per song in a folder, the set folders below one library folder (menu → Library → Library folder), " +
                "then pick a set under Open set from library…. A folder with subfolders opens to show them; ↑ goes back up.",
            "The songs follow each other in alphabetical order. " +
                "Under Songs, tap a song to jump to it, drag = to change the order, untick a song to leave it out.",
            "The set folder is only read, never changed. Order, ticks and notes stay in MTPV.",
            "Moved the library? Pick it again under Library → Library folder: sets keep their order, ticks and notes.",
        ),
    ),
    HelpSection(
        "Notes",
        "Draw with a stylus, or with a finger after tapping the finger button.",
        listOf(
            "Pick pen, marker or eraser in the bar at the bottom left; ↶ undoes.",
            "Without a stylus, tap the square finger button (green = on) to draw with a finger; " +
                "taps and swipes on the page then no longer turn it. A stylus always draws. " +
                "Tool and finger button change on all screens at once.",
            "Notes belong to the song in its set: a new set starts without notes, and a copy of a PDF in another set " +
                "has notes of its own. Renaming a file keeps its notes; deleting it or changing its content deletes them.",
            "Copy notes from… in the menu takes the notes of the same PDF from another song.",
        ),
    ),
    HelpSection(
        "Backup and new tablets",
        "Library → Export all data… saves notes, order and ticks to one file.",
        listOf(
            "Export all data… saves the notes, song order and ticks of all sets to one zip file, " +
                "after fetching unsaved notes from the secondaries. The PDFs stay in the library.",
            "Import all data… on a new install brings them back; it replaces what is there. " +
                "Then pick the library folder again, and pair the screens again.",
        ),
    ),
    HelpSection(
        "Share MTPV",
        "A QR code for another device: where to get MTPV.",
        listOf(
            "Scan this with the camera of another device. The page it opens, ernstkl.github.io/fdroid, " +
                "tells how to install MTPV: through F-Droid, which also brings updates, or as an APK.",
        ),
        R.drawable.share_qr,
    ),
)

/**
 * What MTPV is and how to use it; shown from every role, and once on the first start. An overview
 * of short sections, each with a page of details.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        ?.let { name -> BuildConfig.GIT_COMMIT?.let { "$name ($it)" } ?: name }
    var open by remember { mutableStateOf<HelpSection?>(null) }
    val section = open
    AlertDialog(
        // Back on a details page goes back to the overview.
        onDismissRequest = { if (section != null) open = null else onDismiss() },
        title = { Text(section?.title ?: "MTPV – Multi-Tablet PDF Viewer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (section != null) {
                    section.details.forEach { Text(it) }
                    section.image?.let {
                        Image(
                            ImageBitmap.imageResource(it), "QR code of ernstkl.github.io/fdroid", Modifier.size(240.dp).align(Alignment.CenterHorizontally),
                            filterQuality = FilterQuality.None,
                        )
                    }
                } else {
                    Text(
                        "Spreads a PDF across several tablets, one page per tablet. On a single tablet, as Controller, " +
                            "it is a sheet music reader with song sets, pedal and notes.",
                    )
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
