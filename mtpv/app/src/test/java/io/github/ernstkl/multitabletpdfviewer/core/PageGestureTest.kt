package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGestureTest {
    private fun classify(dx: Float, dy: Float = 0f, maxMove: Float = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)), ms: Long = 150) =
        PageGestures.classify(dx, dy, maxMove, ms, width = 1200, tapSlop = 30f)

    @Test
    fun jitterWithinTheSlopIsATap() {
        assertEquals(PageGesture.TAP, classify(0f))
        assertEquals(PageGesture.TAP, classify(12f, -9f))
        assertEquals(PageGesture.TAP, classify(4f, maxMove = 30f))
    }

    @Test
    fun swipeLeftTurnsToTheNextPage() {
        assertEquals(PageGesture.NEXT, classify(-200f, 40f))
        assertEquals(PageGesture.PREV, classify(200f, -40f))
    }

    @Test
    fun shortSlowOrSteepMovesAreNothing() {
        assertEquals(PageGesture.NONE, classify(-100f))
        assertEquals(PageGesture.NONE, classify(-300f, ms = 900))
        assertEquals(PageGesture.NONE, classify(-200f, 150f))
        // Out and back again: moved, but not a swipe.
        assertEquals(PageGesture.NONE, classify(10f, maxMove = 300f))
    }

    @Test
    fun touchesInTheButtonStripesAreIgnored() {
        assertEquals(true, PageGestures.inButtonStripe(10f, 1000, top = 100f, bottom = 150f))
        assertEquals(false, PageGestures.inButtonStripe(100f, 1000, top = 100f, bottom = 150f))
        assertEquals(false, PageGestures.inButtonStripe(850f, 1000, top = 100f, bottom = 150f))
        assertEquals(true, PageGestures.inButtonStripe(851f, 1000, top = 100f, bottom = 150f))
    }
}
