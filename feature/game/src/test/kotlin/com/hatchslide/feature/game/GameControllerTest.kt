package com.hatchslide.feature.game

import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.feature.game.input.SwipeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameControllerTest {
    private val level = LevelCodec.parse(
        LevelFile("t", 1, 1, "T", listOf("~...", ".#..", "...T", "...."), par = 2, gullMoves = 6, seed = 1),
    )

    @Test
    fun swipeDuringAnimationIsBufferedOnce() {
        val c = GameController(level)
        assertNotNull(c.swipe(Direction.LEFT))
        assertTrue(c.isAnimating)
        assertNull(c.swipe(Direction.UP))
        assertNull(c.swipe(Direction.RIGHT)) // dropped: only one swipe is buffered
        val buffered = c.animationFinished()
        assertEquals(Direction.UP, buffered!!.result.direction)
        assertEquals(GameStatus.WON, c.state.status)
        assertNull(c.animationFinished())
    }

    @Test
    fun noOpDoesNotLockInput() {
        val c = GameController(level)
        // The first DOWN slides the hatchling to the bottom row; the second is a no-op.
        val outcome = c.swipe(Direction.DOWN)
        assertNotNull(outcome)
        c.animationFinished()
        val noop = c.swipe(Direction.DOWN)!!
        assertTrue(noop.result.noOp)
        assertTrue(!c.isAnimating)
    }

    @Test
    fun undoAndRestartRestoreState() {
        val c = GameController(level)
        c.swipe(Direction.LEFT)
        c.animationFinished()
        c.swipe(Direction.RIGHT)
        c.animationFinished()
        assertEquals(2, c.state.movesUsed)
        assertEquals(1, c.undo()!!.movesUsed)
        assertEquals(GameState.initial(level), c.restart())
        assertTrue(!c.canUndo)
    }

    @Test
    fun savedRunRoundTripsThroughJson() {
        val c = GameController(level)
        c.swipe(Direction.LEFT)
        c.animationFinished()
        val text = GameController.encode(c.snapshot())
        val restored = GameController(level, GameController.decode(text))
        assertEquals(c.state, restored.state)
        assertTrue(restored.canUndo)
        assertEquals(GameState.initial(level), restored.undo())
    }

    @Test
    fun starThresholds() {
        assertEquals(3, GameController.starsFor(7, 7))
        assertEquals(2, GameController.starsFor(9, 7))
        assertEquals(1, GameController.starsFor(10, 7))
    }

    @Test
    fun swipeFiresOnMoveAfterThresholdWithinAngle() {
        val d = SwipeDetector(thresholdPx = 60f)
        d.down(100f, 100f, inGestureInset = false)
        assertNull(d.move(130f, 105f)) // not far enough yet
        assertEquals(Direction.RIGHT, d.move(170f, 120f)) // 70 px, 16 degrees
        assertNull(d.move(300f, 120f)) // one swipe per gesture
        d.down(100f, 100f, inGestureInset = false)
        assertNull(d.move(150f, 150f)) // 45 degrees: rejected
        assertEquals(Direction.UP, d.move(110f, 20f))
        d.down(5f, 500f, inGestureInset = true)
        assertNull(d.move(200f, 500f)) // started in the back-gesture area
    }
}
