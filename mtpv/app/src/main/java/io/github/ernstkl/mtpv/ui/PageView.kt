package io.github.ernstkl.mtpv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows one PDF page fitted to the available space, rendered off the main thread. Blank when page is 0. */
@Composable
fun PageView(
    fileId: String?,
    page: Int,
    render: (fileId: String?, page: Int, w: Int, h: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // produceState keeps the previous bitmap until the new one is ready, so page turns don't flash.
        val bitmap by produceState<Bitmap?>(null, fileId, page, w, h) {
            value = withContext(Dispatchers.IO) { render(fileId, page, w, h) }
        }
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "page $page", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
