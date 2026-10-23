package io.github.ernstkl.mtpv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesMergeTest {
    private fun s(id: Long) = Stroke(id, Tool.PEN, 0, 0.01f, floatArrayOf(0f, 0f, 1f, 1f))

    @Test
    fun unchangedOnControllerTakesSecondaryVersionIncludingErasures() {
        val (result, conflict) = NotesMerge.merge(listOf(s(1), s(2)), 5, listOf(s(2), s(3)), baseRev = 5)
        assertEquals(listOf(2L, 3L), result.map { it.id })
        assertFalse(conflict)
    }

    @Test
    fun changedOnBothSidesKeepsEveryStroke() {
        val (result, conflict) = NotesMerge.merge(listOf(s(1), s(4)), 6, listOf(s(1), s(3)), baseRev = 5)
        assertEquals(listOf(1L, 4L, 3L), result.map { it.id })
        assertTrue(conflict)
    }
}

class NotesGeometryTest {
    // Horizontal stroke across the middle of a 1000 x 2000 px page, 10 px wide.
    private val line = Stroke(1, Tool.PEN, 0, 0.01f, floatArrayOf(0.1f, 0.5f, 0.9f, 0.5f))

    @Test
    fun hitsNearTheLine() {
        assertTrue(NotesGeometry.hits(line, 500f, 1010f, 1000f, 2000f, slop = 6f))
    }

    @Test
    fun missesFarFromTheLine() {
        assertFalse(NotesGeometry.hits(line, 500f, 1050f, 1000f, 2000f, slop = 6f))
        assertFalse(NotesGeometry.hits(line, 950f, 1000f, 1000f, 2000f, slop = 6f))
    }

    @Test
    fun singleDot() {
        val dot = Stroke(2, Tool.PEN, 0, 0.01f, floatArrayOf(0.5f, 0.5f))
        assertTrue(NotesGeometry.hits(dot, 505f, 1000f, 1000f, 2000f, slop = 6f))
    }
}

class ToolSyncTest {
    private val start = ToolState(NoteTool.PEN_BLACK, fingers = false)

    @Test
    fun toolEventChangesOnlyTheTool() {
        NoteTool.entries.forEach { assertEquals(ToolState(it, false), ToolSync.apply(start, ToolSync.event(it))) }
        assertEquals(ToolState(NoteTool.MARKER, true), ToolSync.apply(ToolState(NoteTool.PEN_RED, true), ToolSync.event(NoteTool.MARKER)))
    }

    @Test
    fun fingersEventChangesOnlyTheSwitch() {
        assertEquals(ToolState(NoteTool.PEN_RED, true), ToolSync.apply(ToolState(NoteTool.PEN_RED, false), ToolSync.event(true)))
        assertEquals(ToolState(NoteTool.PEN_RED, false), ToolSync.apply(ToolState(NoteTool.PEN_RED, true), ToolSync.event(false)))
    }

    @Test
    fun pageTurnsAndUnknownToolsAreNoToolEvents() {
        assertNull(ToolSync.apply(start, "next"))
        assertNull(ToolSync.apply(start, "prev"))
        assertNull(ToolSync.apply(start, "tool:LASER"))
        assertNull(ToolSync.apply(start, "fingers:maybe"))
        assertNull(ToolSync.parse(""))
    }
}
