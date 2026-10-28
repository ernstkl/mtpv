package io.github.ernstkl.multitabletpdfviewer.ui

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.ernstkl.multitabletpdfviewer.BuildConfig
import io.github.ernstkl.multitabletpdfviewer.core.PageGesture
import io.github.ernstkl.multitabletpdfviewer.core.PageGestures

/**
 * Finger taps and swipes for page turning ([PageGestures]). Unlike detectTapGestures, a tap may
 * move a little more than the touch slop, for touchscreens that jitter, and a touch that moves
 * further is a swipe only if quick and mostly horizontal, so a resting palm doesn't turn pages.
 * A second finger cancels the touch, and a touch going down in the stripes along the top and
 * bottom, where the buttons are, does nothing. Stylus strokes never get here, because the notes
 * layer consumes them.
 */
fun Modifier.pageTaps(
    onTap: (position: Offset, size: IntSize) -> Unit,
    onSwipe: (next: Boolean) -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop * PageGestures.TAP_SLOP_FACTOR
    val top = PageGestures.TOP_STRIPE_DP.dp.toPx()
    val bottom = PageGestures.BOTTOM_STRIPE_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        if (PageGestures.inButtonStripe(down.position.y, size.height, top, bottom)) return@awaitEachGesture
        val touch = Touch(down)
        val released = if (onLongPress == null) {
            track(touch)
        } else {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { track(touch) } ?: run {
                if (touch.maxMove <= slop && !touch.multi) onLongPress()
                return@awaitEachGesture
            }
        }
        val up = touch.up
        val gesture = if (released && up != null && !up.isConsumed && !touch.multi) {
            val d = up.position - down.position
            PageGestures.classify(d.x, d.y, touch.maxMove, up.uptimeMillis - down.uptimeMillis, size.width, slop)
        } else {
            PageGesture.NONE
        }
        if (BuildConfig.DEBUG) {
            Log.d("MtpvTaps", "$gesture at x=${down.position.x.toInt()} of ${size.width}, moved ${touch.maxMove.toInt()} px (slop ${slop.toInt()})")
        }
        when (gesture) {
            PageGesture.TAP -> onTap(down.position, size)
            PageGesture.NEXT -> onSwipe(true)
            PageGesture.PREV -> onSwipe(false)
            PageGesture.NONE -> Unit
        }
    }
}

private class Touch(val down: PointerInputChange) {
    /** Farthest distance from where the touch went down. */
    var maxMove = 0f
    /** Another finger touched meanwhile. */
    var multi = false
    var up: PointerInputChange? = null
}

/** Follows the touch until it is lifted (true) or disappears (false). */
private suspend fun AwaitPointerEventScope.track(t: Touch): Boolean {
    while (true) {
        val changes = awaitPointerEvent().changes
        if (changes.any { it.id != t.down.id && it.pressed }) t.multi = true
        val c = changes.firstOrNull { it.id == t.down.id } ?: return false
        t.maxMove = maxOf(t.maxMove, (c.position - t.down.position).getDistance())
        if (!c.pressed) {
            t.up = c
            return true
        }
    }
}
