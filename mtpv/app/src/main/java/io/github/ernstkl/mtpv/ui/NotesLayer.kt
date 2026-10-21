package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ernstkl.mtpv.core.NotesGeometry
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.Stroke
import io.github.ernstkl.mtpv.core.Tool
import kotlin.random.Random

/** What the stylus does. Widths are relative to the page width. */
enum class NoteTool(val tool: Tool?, val color: Long, val width: Float) {
    PEN_BLACK(Tool.PEN, 0xFF000000, 0.0025f),
    PEN_RED(Tool.PEN, 0xFFD32F2F, 0.0025f),
    PEN_BLUE(Tool.PEN, 0xFF1565C0, 0.0025f),
    MARKER(Tool.MARKER, 0xFFFFEB3B, 0.02f),
    ERASER(null, 0xFFFFFFFF, 0f),
}

/**
 * Notes over a page; it must exactly cover the rendered page. Only the stylus draws (its eraser end,
 * or the eraser tool, removes whole strokes), so fingers still turn pages underneath. Each change
 * names the page the stroke started on, even if the screen has moved to another page meanwhile.
 * [onPen] reports whether the stylus is down.
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
    onPen: (Boolean) -> Unit = {},
) {
    val currentPage by rememberUpdatedState(page)
    val current by rememberUpdatedState(strokes)
    val pen by rememberUpdatedState(onPen)
    val currentTool by rememberUpdatedState(tool)
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
                    if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture
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
        strokes.forEach { drawNote(it.tool, it.color, it.width, it.points) }
        liveTool?.let { t -> if (t.tool != null) drawNote(t.tool, t.color.toInt(), t.width, live.toFloatArray()) }
    }
}

private fun DrawScope.drawNote(tool: Tool, color: Int, width: Float, points: FloatArray) {
    if (points.size < 2) return
    val w = size.width
    val h = size.height
    // The marker multiplies with the page, so it tints the paper but leaves black notes black.
    val blend = if (tool == Tool.MARKER) BlendMode.Multiply else BlendMode.SrcOver
    val c = Color(color)
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

/** Small tool picker for the stylus: tap the dot to open, then pick pen colour, marker or eraser. */
@Composable
fun NoteToolPicker(tool: NoteTool, onTool: (NoteTool) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(20.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val choices = if (open) NoteTool.entries else listOf(tool)
        choices.forEach { t ->
            Box(
                Modifier
                    .size(28.dp)
                    .background(Color(t.color), CircleShape)
                    .border(if (t == tool && open) 3.dp else 1.dp, Color.White, CircleShape)
                    .clickable {
                        if (open) onTool(t)
                        open = !open
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (t == NoteTool.ERASER) Text("⌫", color = Color.Black, fontSize = 14.sp)
            }
        }
    }
}
