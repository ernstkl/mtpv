package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ernstkl.mtpv.core.DarkPages
import io.github.ernstkl.mtpv.core.NoteTool
import io.github.ernstkl.mtpv.core.NotesGeometry
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.Stroke
import io.github.ernstkl.mtpv.core.Tool
import kotlin.random.Random

/**
 * Notes over a page; it must exactly cover the rendered page. Normally only the stylus draws (its eraser
 * end, or the eraser tool, removes whole strokes), so fingers still turn pages underneath. With
 * [fingers], a finger draws like the stylus, and taps on the page no longer turn it. Each change
 * names the page the stroke started on, even if the screen has moved to another page meanwhile.
 * [onPen] reports whether the stylus (or drawing finger) is down. With [dark], strokes are drawn for
 * a dark page ([DarkPages]).
 */
@OptIn(ExperimentalComposeUiApi::class) // historical points: smooth strokes at high stylus sample rates
@Composable
fun NotesLayer(
    page: PageKey,
    strokes: List<Stroke>,
    tool: NoteTool,
    enabled: Boolean,
    onChange: (PageKey, List<Stroke>) -> Unit,
    modifier: Modifier = Modifier,
    fingers: Boolean = false,
    dark: Boolean = false,
    onPen: (Boolean) -> Unit = {},
) {
    val currentPage by rememberUpdatedState(page)
    val current by rememberUpdatedState(strokes)
    val pen by rememberUpdatedState(onPen)
    val currentTool by rememberUpdatedState(tool)
    val withFingers by rememberUpdatedState(fingers)
    val change by rememberUpdatedState(onChange)
    val live = remember { mutableStateListOf<Float>() }
    var liveTool by remember { mutableStateOf<NoteTool?>(null) }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val draws = down.type == PointerType.Stylus || down.type == PointerType.Eraser ||
                        (withFingers && down.type == PointerType.Touch)
                    if (!draws) return@awaitEachGesture
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val erasing = down.type == PointerType.Eraser || currentTool == NoteTool.ERASER
                    val startPage = currentPage
                    val base = current
                    var remaining = base

                    fun at(p: Offset) {
                        if (erasing) {
                            val hit = remaining.filter { NotesGeometry.hits(it, p.x, p.y, w, h, slop = 8.dp.toPx()) }
                            if (hit.isNotEmpty()) {
                                remaining = remaining - hit.toSet()
                                change(startPage, remaining)
                            }
                        } else {
                            live.add((p.x / w).coerceIn(0f, 1f))
                            live.add((p.y / h).coerceIn(0f, 1f))
                        }
                    }

                    live.clear()
                    liveTool = if (erasing) null else currentTool
                    pen(true)
                    try {
                        at(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            c.historical.forEach { at(it.position) }
                            at(c.position)
                            c.consume()
                            if (!c.pressed) break
                        }
                        val t = liveTool
                        if (t?.tool != null && live.isNotEmpty()) {
                            change(startPage, base + Stroke(Random.nextLong(), t.tool, t.color.toInt(), t.width, live.toFloatArray()))
                        }
                    } finally {
                        live.clear()
                        liveTool = null
                        pen(false)
                    }
                }
            },
    ) {
        strokes.forEach { drawNote(it.tool, it.color, it.width, it.points, dark) }
        liveTool?.let { t -> if (t.tool != null) drawNote(t.tool, t.color.toInt(), t.width, live.toFloatArray(), dark) }
    }
}

private fun DrawScope.drawNote(tool: Tool, color: Int, width: Float, points: FloatArray, dark: Boolean) {
    if (points.size < 2) return
    val w = size.width
    val h = size.height
    // The marker multiplies with the page, so it tints the paper but leaves black notes black;
    // on a dark page it screens instead, tinting the black paper and leaving light notes light.
    val blend = when {
        tool != Tool.MARKER -> BlendMode.SrcOver
        dark -> BlendMode.Screen
        else -> BlendMode.Multiply
    }
    val c = Color(
        when {
            !dark -> color
            tool == Tool.MARKER -> DarkPages.marker(color)
            else -> DarkPages.pen(color)
        }
    )
    val px = width * w
    if (points.size == 2) {
        drawCircle(c, radius = px / 2, center = Offset(points[0] * w, points[1] * h), blendMode = blend)
        return
    }
    val path = Path().apply {
        moveTo(points[0] * w, points[1] * h)
        for (i in 1 until points.size / 2) lineTo(points[2 * i] * w, points[2 * i + 1] * h)
    }
    drawPath(path, c, style = StrokeStyle(width = px, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
}

/**
 * Tool bar: the square finger switch (a finger writing a line) turns finger drawing on (green) and off (grey, crossed out);
 * then pen colours (a small dot on white), marker and eraser, the selected one underlined, each picked with one tap; last
 * the undo button, which removes the page's last stroke, whoever drew it; dimmed on an empty page.
 */
@Composable
fun NoteToolPicker(
    tool: NoteTool,
    onTool: (NoteTool) -> Unit,
    fingers: Boolean,
    onFingers: (Boolean) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(20.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val square = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .size(28.dp)
                .alpha(if (fingers) 1f else 0.6f)
                .clip(square)
                .background(if (fingers) Color(0xFF2E7D32) else Color.DarkGray, square)
                .border(1.dp, Color.White, square)
                .drawWithContent {
                    drawContent()
                    drawFingerWriting()
                    if (!fingers) {
                        drawLine(Color(0xFFE53935), Offset(size.width * 0.2f, size.height * 0.8f),
                            Offset(size.width * 0.8f, size.height * 0.2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                .clickable { onFingers(!fingers) },
        )
        Separator()

        NoteTool.entries.forEach { t ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(28.dp)
                        // A pen is a small dot of its colour on white, so it looks thinner than the marker.
                        .background(if (t.tool == Tool.PEN) Color.White else Color(t.color), CircleShape)
                        .border(1.dp, Color.White, CircleShape)
                        .drawWithContent {
                            drawContent()
                            if (t.tool == Tool.PEN) drawCircle(Color(t.color), radius = size.minDimension / 4)
                            if (t == NoteTool.ERASER) drawEraser()
                        }
                        .clickable { onTool(t) },
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .size(width = 22.dp, height = 4.dp)
                        .background(if (t == tool) Color.White else Color.Transparent, RoundedCornerShape(2.dp)),
                )
            }
        }
        Separator()

        Box(
            Modifier
                .size(28.dp)
                .alpha(if (canUndo) 1f else 0.35f)
                .background(Color.DarkGray, CircleShape)
                .border(1.dp, Color.White, CircleShape)
                .clickable(enabled = canUndo, onClick = onUndo),
            contentAlignment = Alignment.Center,
        ) {
            Text("↶", color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Separator() = Box(Modifier.size(width = 1.dp, height = 24.dp).background(Color.Gray))

/** A finger from the top right, the hand cut off by the corner, writing a wavy line from its tip. */
private fun DrawScope.drawFingerWriting() {
    val w = size.width
    val h = size.height
    fun at(x: Float, y: Float) = Offset(x * w, y * h)
    drawLine(Color.White, at(0.48f, 0.56f), at(0.80f, 0.12f), strokeWidth = 0.18f * w, cap = StrokeCap.Round)
    drawCircle(Color.White, radius = 0.20f * w, center = at(0.92f, 0.10f))
    // Knuckle crease and nail.
    drawLine(Color(0xFF9E9E9E), at(0.62f, 0.38f), at(0.69f, 0.43f), strokeWidth = 0.025f * w, cap = StrokeCap.Round)
    drawLine(Color(0xFF9E9E9E), at(0.495f, 0.59f), at(0.545f, 0.52f), strokeWidth = 0.08f * w, cap = StrokeCap.Round)
    val line = Path().apply {
        moveTo(0.42f * w, 0.66f * h)
        cubicTo(0.36f * w, 0.78f * h, 0.30f * w, 0.58f * h, 0.24f * w, 0.70f * h)
        cubicTo(0.19f * w, 0.80f * h, 0.30f * w, 0.84f * h, 0.22f * w, 0.88f * h)
        cubicTo(0.16f * w, 0.91f * h, 0.12f * w, 0.82f * h, 0.08f * w, 0.90f * h)
    }
    drawPath(line, Color.White, style = StrokeStyle(width = 0.05f * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A tilted rubber: pink tip on the left, blue sleeve on the right. */
private fun DrawScope.drawEraser() {
    val w = size.width * 0.66f
    val h = size.height * 0.32f
    val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
    val corner = CornerRadius(2.dp.toPx())
    rotate(-35f) {
        drawRoundRect(Color(0xFF90CAF9), topLeft, Size(w, h), corner)
        drawRoundRect(Color(0xFFF48FB1), topLeft, Size(w * 0.4f, h), corner)
        drawRoundRect(Color.Black, topLeft, Size(w, h), corner, style = StrokeStyle(width = 1.dp.toPx()))
    }
}
