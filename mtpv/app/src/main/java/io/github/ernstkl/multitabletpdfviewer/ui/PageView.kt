package io.github.ernstkl.multitabletpdfviewer.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ernstkl.multitabletpdfviewer.core.PagePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Rendered(val fileId: String?, val page: Int, val bitmap: Bitmap?, val position: PagePosition?)

/**
 * Shows one PDF page fitted to the available space, rendered off the main thread. A dim ∅ when a
 * PDF is open but this screen has no page (past the end); nothing without a PDF. [overlay] is laid exactly over the rendered page and gets the page it actually covers.
 * [dark] has [render] swap light and dark ([io.github.ernstkl.multitabletpdfviewer.core.DarkPages]). The top center shows
 * [position] ("Song 2 of 7 | Page 1 of 3"), swapped together with the page; where it says so, with « and »
 * around the song, which call [onSkip].
 */
@Composable
fun PageView(
    fileId: String?,
    page: Int,
    render: (fileId: String?, page: Int, w: Int, h: Int, dark: Boolean) -> Bitmap?,
    position: PagePosition?,
    onSkip: (next: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    overlay: @Composable BoxScope.(fileId: String, page: Int) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // produceState keeps the previous page until the new one is ready, so page turns don't flash.
        val rendered by produceState<Rendered?>(null, fileId, page, position, w, h, dark) {
            value = Rendered(fileId, page, withContext(Dispatchers.IO) { render(fileId, page, w, h, dark) }, position)
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
        val shown = r.position
        if (r.page > 0 && shown != null) PositionBar(shown, onSkip, Modifier.align(Alignment.TopCenter))
    }
}

/** "Song « 3 of 10 » | Page 1 of 2"; « and » only where [p] says so and the set has more than one song. */
@Composable
private fun PositionBar(p: PagePosition, onSkip: (next: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val text = Color.White
    Row(
        modifier
            .padding(4.dp)
            .background(Color(0x99000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val song = p.songLabel
        if (song != null) {
            Text("Song", color = text, fontSize = 18.sp)
            if (p.skip) SkipButton(next = false, enabled = p.hasPrevious, onSkip)
            Text(song, color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (p.skip) SkipButton(next = true, enabled = p.hasNext, onSkip)
            Box(Modifier.padding(vertical = 8.dp).fillMaxHeight().width(1.dp).background(text.copy(alpha = 0.5f)))
        }
        // Without arrows the bar is lower; the padding keeps it as easy to read.
        Text(p.pageLabel, Modifier.padding(vertical = if (p.skip) 0.dp else 4.dp), color = text, fontSize = 18.sp)
    }
}

/** « or », black on a white square twice as high as the symbol, so it reads as a button. */
@Composable
private fun SkipButton(next: Boolean, enabled: Boolean, onSkip: (next: Boolean) -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .background(Color.White, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClickLabel = if (next) "Next song" else "Previous song") { onSkip(next) },
    ) {
        // Centred on the glyph's ink, not its line: as text, « and » sit low in the square.
        val glyph = if (next) "»" else "«"
        Canvas(Modifier.fillMaxSize()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 32.sp.toPx()
                typeface = Typeface.DEFAULT_BOLD
                color = android.graphics.Color.BLACK
            }
            val ink = Rect().also { paint.getTextBounds(glyph, 0, glyph.length, it) }
            drawContext.canvas.nativeCanvas.drawText(glyph, center.x - ink.exactCenterX(), center.y - ink.exactCenterY(), paint)
        }
    }
}
