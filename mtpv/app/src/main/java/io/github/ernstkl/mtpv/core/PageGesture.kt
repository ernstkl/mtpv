package io.github.ernstkl.mtpv.core

import kotlin.math.abs

/** What a finger touch on a page asks for. */
enum class PageGesture { TAP, NEXT, PREV, NONE }

object PageGestures {
    /** A tap may move this many times Android's touch slop: some touchscreens jitter more than that. */
    const val TAP_SLOP_FACTOR = 3f

    /** A swipe is at least this part of the screen width, ... */
    const val SWIPE_MIN_WIDTH = 1 / 8f

    /** ... done within this time, ... */
    const val SWIPE_MAX_MS = 500L

    /** ... and at least this many times as wide as high. */
    const val SWIPE_MIN_RATIO = 2f

    /**
     * A touch that moved [dx], [dy] (px) from down to lift in [durationMs], and at most [maxMove]
     * from where it went down on the way, on a screen [width] px wide. A tap stays within
     * [tapSlop]; a swipe is quick, mostly horizontal and long enough, and to the left turns to the
     * next page, like paper. Anything else, a resting palm for example, is nothing.
     */
    fun classify(dx: Float, dy: Float, maxMove: Float, durationMs: Long, width: Int, tapSlop: Float): PageGesture = when {
        maxMove <= tapSlop -> PageGesture.TAP
        durationMs <= SWIPE_MAX_MS && abs(dx) >= width * SWIPE_MIN_WIDTH && abs(dx) >= SWIPE_MIN_RATIO * abs(dy) ->
            if (dx < 0) PageGesture.NEXT else PageGesture.PREV
        else -> PageGesture.NONE
    }
}
