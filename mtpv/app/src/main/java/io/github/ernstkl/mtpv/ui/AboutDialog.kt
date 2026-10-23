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
                Text("Controller: tap the middle to show the toolbar. Secondary: long-press for its address and settings. " +
                    "If a secondary isn't found, enter its address under Screens.")
                Text("Notes: draw with a stylus; pick pen, marker or eraser with the dot at the bottom left. " +
                    "Without a stylus, tap 👆 next to it (green = on) to draw with a finger; taps on the page then no longer turn it. " +
                    "Both settings change on all screens at once.")
                Text("When MTPV comes back to the front on one screen, the screens showing another app " +
                    "get a notification; tap it to bring MTPV back there too.")
                Text("The controller needs Android 14, secondaries Android 10.")
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
