package com.hatchslide.feature.game.input

import com.hatchslide.core.game.Direction
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Swipe recognition (spec section 2): a swipe fires during the move, as soon as the finger has
 * travelled [thresholdPx] (24 dp) within [maxAngleDegrees] (35 degrees) of an axis, not on lift.
 * One gesture produces at most one swipe. Gestures that start inside system gesture insets are
 * ignored so the game never fights the back gesture.
 */
class SwipeDetector(
    private val thresholdPx: Float,
    private val maxAngleDegrees: Float = 35f,
) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var fired = false

    /** [inGestureInset] is true when the touch starts in a system gesture area. */
    fun down(x: Float, y: Float, inGestureInset: Boolean) {
        startX = x
        startY = y
        tracking = !inGestureInset
        fired = false
    }

    /** Returns a direction the first time the movement qualifies, otherwise null. */
    fun move(x: Float, y: Float): Direction? {
        if (!tracking || fired) return null
        val dx = x - startX
        val dy = y - startY
        if (hypot(dx, dy) < thresholdPx) return null
        val horizontal = abs(dx) >= abs(dy)
        val angle = Math.toDegrees(atan2(if (horizontal) abs(dy) else abs(dx), if (horizontal) abs(dx) else abs(dy)).toDouble())
        if (angle > maxAngleDegrees) return null
        fired = true
        return when {
            horizontal && dx > 0 -> Direction.RIGHT
            horizontal -> Direction.LEFT
            dy > 0 -> Direction.DOWN
            else -> Direction.UP
        }
    }

    fun up() {
        tracking = false
    }

    fun cancel() {
        tracking = false
    }
}
