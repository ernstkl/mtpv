package io.github.ernstkl.mtpv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Rendered(val fileId: String?, val page: Int, val bitmap: Bitmap?, val label: String?)

/**
 * Shows one PDF page fitted to the available space, rendered off the main thread. A dim ∅ when a
 * PDF is open but this screen has no page (past the end); nothing without a PDF. [overlay] is laid exactly over the rendered page and gets the page it actually covers.
 * [dark] has [render] swap light and dark ([io.github.ernstkl.mtpv.core.DarkPages]). The bottom right corner shows
 * [label] ("song 2 of 7: page 1 of 3"), swapped together with the page.
 */
@Composable
fun PageView(
    fileId: String?,
    page: Int,
    render: (fileId: String?, page: Int, w: Int, h: Int, dark: Boolean) -> Bitmap?,
    label: String?,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    overlay: @Composable BoxScope.(fileId: String, page: Int) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // produceState keeps the previous page until the new one is ready, so page turns don't flash.
        val rendered by produceState<Rendered?>(null, fileId, page, label, w, h, dark) {
            value = Rendered(fileId, page, withContext(Dispatchers.IO) { render(fileId, page, w, h, dark) }, label)
        }
        val r = rendered
        if (r != null && r.fileId != null && r.page == 0) {
            Text("∅", Modifier.align(Alignment.Center), color = Color(0xFF616161), fontSize = 120.sp)
        }
        val bmp = r?.bitmap ?: return@BoxWithConstraints
        Box(Modifier.align(Alignment.Center).aspectRatio(bmp.width / bmp.height.toFloat())) {
            Image(bmp.asImageBitmap(), contentDescription = "page ${r.page}", Modifier.fillMaxSize())
            if (r.fileId != null) overlay(r.fileId, r.page)
        }
        val shownLabel = r.label
        if (r.page > 0 && shownLabel != null) {
            // Faint like the corner buttons; not a tap target, so the pen still draws underneath.
            Text(
                shownLabel,
                Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
