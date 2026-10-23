package io.github.ernstkl.mtpv.core

import org.json.JSONObject
import kotlin.math.hypot

enum class Tool { PEN, MARKER }

/** What the stylus does. Widths are relative to the page width. */
enum class NoteTool(val tool: Tool?, val color: Long, val width: Float) {
    PEN_BLACK(Tool.PEN, 0xFF000000, 0.0025f),
    PEN_RED(Tool.PEN, 0xFFD32F2F, 0.0025f),
    PEN_BLUE(Tool.PEN, 0xFF1565C0, 0.0025f),
    MARKER(Tool.MARKER, 0xFFFFEB3B, 0.02f),
    ERASER(null, 0xFFFFFFFF, 0f),
}

/** How notes are drawn on every screen: the tool, and whether a finger draws too (the stylus always does). */
data class ToolState(val tool: NoteTool = NoteTool.PEN_BLACK, val fingers: Boolean = false)

/**
 * The tool state picked on one screen is picked on all of them. A secondary reports a pick to the
 * controller as an input event, "tool:<name>" or "fingers:on|off"; the controller sends the whole
 * state as JSON {tool, fingers}.
 */
object ToolSync {
    private const val TOOL = "tool:"
    private const val FINGERS = "fingers:"

    fun event(tool: NoteTool) = TOOL + tool.name

    fun event(fingers: Boolean) = FINGERS + if (fingers) "on" else "off"

    /** [state] with the pick from input event [e], or null if it is another event. */
    fun apply(state: ToolState, e: String): ToolState? = when {
        e.startsWith(TOOL) -> parse(e.removePrefix(TOOL))?.let { state.copy(tool = it) }
        e == event(true) -> state.copy(fingers = true)
        e == event(false) -> state.copy(fingers = false)
        else -> null
    }

    /** Null for an empty or unknown name, e.g. a tool that only a newer version has. */
    fun parse(name: String): NoteTool? = NoteTool.entries.firstOrNull { it.name == name }

    fun toJson(state: ToolState, into: JSONObject = JSONObject()): JSONObject =
        into.put("tool", state.tool.name).put("fingers", state.fingers)

    /** Null if [json] names no known tool. */
    fun fromJson(json: JSONObject): ToolState? =
        parse(json.optString("tool"))?.let { ToolState(it, json.optBoolean("fingers")) }
}

/**
 * One handwritten stroke. Coordinates are relative to the page (0..1 on each axis), and [width] is
 * relative to the page width, so notes land in the same place on screens of any size. Two strokes
 * are the same stroke if they have the same [id].
 */
class Stroke(val id: Long, val tool: Tool, val color: Int, val width: Float, val points: FloatArray) {
    override fun equals(other: Any?) = other is Stroke && other.id == id
    override fun hashCode() = id.hashCode()
}

/** Identifies the notes of one page: the PDF's SHA-256 and the 1-based page number. */
data class PageKey(val fileId: String, val page: Int)

object NotesMerge {
    /**
     * Notes edited on a secondary come back to the controller. If the controller's copy has not
     * changed since it was sent ([currentRev] == [baseRev]), the secondary's version replaces it,
     * including its erasures. Otherwise both screens changed the page (only possible when a
     * secondary was offline), and the union keeps every stroke. Returns the result and whether
     * that conflict happened.
     */
    fun merge(current: List<Stroke>, currentRev: Long, incoming: List<Stroke>, baseRev: Long): Pair<List<Stroke>, Boolean> =
        if (currentRev == baseRev) {
            incoming to false
        } else {
            (current + incoming.filter { it !in current }) to true
        }
}

object NotesGeometry {
    /** Whether [x],[y] (pixels) touches [stroke] drawn on a page of [w] x [h] pixels, with [slop] pixels of tolerance. */
    fun hits(stroke: Stroke, x: Float, y: Float, w: Float, h: Float, slop: Float): Boolean {
        val reach = stroke.width * w / 2 + slop
        val p = stroke.points
        if (p.size < 2) return false
        if (p.size == 2) return hypot(p[0] * w - x, p[1] * h - y) <= reach
        for (i in 0 until p.size / 2 - 1) {
            if (segmentDistance(x, y, p[2 * i] * w, p[2 * i + 1] * h, p[2 * i + 2] * w, p[2 * i + 3] * h) <= reach) return true
        }
        return false
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
