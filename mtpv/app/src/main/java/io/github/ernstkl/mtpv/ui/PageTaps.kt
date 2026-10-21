package io.github.ernstkl.mtpv.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/**
 * Finger taps for page turning. Unlike detectTapGestures, a touch that moves more than the touch
 * slop is not a tap, so swipes and a resting palm don't turn pages. Stylus strokes never get here,
 * because the notes layer consumes them.
 */
fun Modifier.pageTaps(
    onTap: (position: Offset, size: IntSize) -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val touch = Touch(down, viewConfiguration.touchSlop)
        val released = if (onLongPress == null) {
            track(touch)
        } else {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { track(touch) } ?: run {
                if (!touch.moved) onLongPress()
                return@awaitEachGesture
            }
        }
        val up = touch.up
        if (released && up != null && !touch.moved && !up.isConsumed) onTap(down.position, size)
    }
}

private class Touch(val down: PointerInputChange, val slop: Float) {
    var moved = false
    var up: PointerInputChange? = null
}

/** Follows the touch until it is lifted (true) or disappears (false). */
private suspend fun AwaitPointerEventScope.track(t: Touch): Boolean {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == t.down.id } ?: return false
        if ((c.position - t.down.position).getDistance() > t.slop) t.moved = true
        if (!c.pressed) {
            t.up = c
            return true
        }
    }
}
