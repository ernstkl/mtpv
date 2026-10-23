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

/** What MTPV is and how to set it up; shown from every role, and once on the first start. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
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
                Text("Song sets: put one PDF per song in a folder and tap Open folder as set. The songs follow each other " +
                    "in alphabetical order, like one long PDF. Under Songs, tap a song to jump to it, drag ≡ to change the order, untick a song to leave it out.")
                Text("Many sets: put their folders below one Library folder, then pick a set under Open set from library…. " +
                    "A folder with subfolders opens to show them; ↑ goes back up.")
                Text("Controller: tap the middle or ☰ for the menu. Secondary: tap the middle or ☰ for its address and settings. " +
                    "If a secondary isn't found, enter its address under Screens.")
                Text("Notes: draw with a stylus; pick pen, marker or eraser in the bar at the bottom left, ↶ undoes. " +
                    "Without a stylus, tap 👆 (green = on) to draw with a finger; taps on the page then no longer turn it. " +
                    "Tool and 👆 change on all screens at once.")
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
