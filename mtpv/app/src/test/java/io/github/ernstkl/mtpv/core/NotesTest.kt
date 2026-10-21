package io.github.ernstkl.mtpv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
