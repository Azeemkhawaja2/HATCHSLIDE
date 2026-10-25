package com.hatchslide.feature.game

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.hatchslide.core.game.Direction
import com.hatchslide.feature.game.input.SwipeDetector

/**
 * Board swipes (spec section 2): recognised during the move after 24 dp within 35 degrees of an axis,
 * ignoring touches that start in system gesture insets.
 */
@Composable
fun Modifier.boardSwipes(onSwipe: (Direction) -> Unit): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val detector = remember(density) { SwipeDetector(with(density) { 24.dp.toPx() }) }
    return pointerInput(detector, gestures) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val left = gestures.getLeft(density, layoutDirection)
            val right = gestures.getRight(density, layoutDirection)
            val top = gestures.getTop(density)
            val bottom = gestures.getBottom(density)
            val p = down.position
            val inInset = p.x < left || p.x > size.width - right || p.y < top || p.y > size.height - bottom
            detector.down(p.x, p.y, inInset)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                detector.move(change.position.x, change.position.y)?.let(onSwipe)
            }
            detector.up()
        }
    }
}

/** Keyboard arrows, WASD and the gamepad D-pad map to swipes; U undoes, R restarts, H asks for a hint. */
fun Modifier.boardKeys(onSwipe: (Direction) -> Unit, onUndo: () -> Unit, onRestart: () -> Unit, onHint: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = when (event.key) {
            Key.DirectionUp, Key.W -> Direction.UP
            Key.DirectionDown, Key.S -> Direction.DOWN
            Key.DirectionLeft, Key.A -> Direction.LEFT
            Key.DirectionRight, Key.D -> Direction.RIGHT
            else -> null
        }
        when {
            direction != null -> {
                onSwipe(direction)
                true
            }

            event.key == Key.U || event.key == Key.Backspace || event.key == Key.ButtonX -> {
                onUndo()
                true
            }

            event.key == Key.R || event.key == Key.ButtonY -> {
                onRestart()
                true
            }

            event.key == Key.H || event.key == Key.ButtonL1 -> {
                onHint()
                true
            }

            else -> false
        }
    }
