package io.github.ernstkl.multitabletpdfviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

private val Faint = Color.White.copy(alpha = 0.6f)

/** A round, faint button in a corner of the page. A tap target only, so the pen still draws right next to it. */
@Composable
private fun CornerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .padding(4.dp)
            .size(40.dp)
            .background(Color(0x66000000), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** ☰, on every screen. */
@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) = CornerButton("Menu", onClick, modifier) {
    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Faint)
}

/** ♫, on the controller only: opens the songs. It also tells the controller from the secondaries at a glance. */
@Composable
fun SongsButton(onClick: () -> Unit, modifier: Modifier = Modifier) = CornerButton("Songs", onClick, modifier) {
    // Drawn rather than the ♫ glyph, whose thin strokes are hard to see.
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stem = 0.11f * w
        val head = Size(0.34f * w, 0.24f * h)
        fun note(x: Float, y: Float) {
            rotate(-20f, Offset(x + head.width / 2, y + head.height / 2)) { drawOval(Faint, Offset(x, y), head) }
            drawRect(Faint, Offset(x + head.width - stem, 0.10f * h), Size(stem, y + head.height / 2 - 0.10f * h))
        }
        note(0.04f * w, 0.70f * h)
        note(0.56f * w, 0.60f * h)
        val beam = Path().apply {
            moveTo(0.38f * w - stem, 0.10f * h)
            lineTo(0.90f * w, 0.0f)
            lineTo(0.90f * w, 0.20f * h)
            lineTo(0.38f * w - stem, 0.30f * h)
            close()
        }
        drawPath(beam, Faint)
    }
}

/** ◐, on every screen: dark pages on or off, on all screens at once. */
@Composable
fun DarkPagesButton(dark: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) =
    CornerButton(if (dark) "Light pages" else "Dark pages", { onToggle(!dark) }, modifier) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2
            drawArc(Faint, 90f, 180f, useCenter = true, topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r))
            drawCircle(Faint, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }
