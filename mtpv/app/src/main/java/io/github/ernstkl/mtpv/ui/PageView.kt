package io.github.ernstkl.mtpv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Rendered(val fileId: String?, val page: Int, val bitmap: Bitmap?)

/**
 * Shows one PDF page fitted to the available space, rendered off the main thread. Blank when page
 * is 0. [overlay] is laid exactly over the rendered page and gets the page it actually covers.
 */
@Composable
fun PageView(
    fileId: String?,
    page: Int,
    render: (fileId: String?, page: Int, w: Int, h: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(fileId: String, page: Int) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // produceState keeps the previous page until the new one is ready, so page turns don't flash.
        val rendered by produceState<Rendered?>(null, fileId, page, w, h) {
            value = Rendered(fileId, page, withContext(Dispatchers.IO) { render(fileId, page, w, h) })
        }
        val r = rendered
        val bmp = r?.bitmap ?: return@BoxWithConstraints
        Box(Modifier.align(Alignment.Center).aspectRatio(bmp.width / bmp.height.toFloat())) {
            Image(bmp.asImageBitmap(), contentDescription = "page ${r.page}", Modifier.fillMaxSize())
            if (r.fileId != null) overlay(r.fileId, r.page)
        }
    }
}
