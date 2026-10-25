package com.hatchslide.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardsTest {
    @Test
    fun currentLoopStopsSafely() {
        val level = testLevel(
            "~~~~",
            ".>v.",
            ".^<.",
            ".T..",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        assertEquals(level.cell(1, 2), outcome.state.pieces.single().cell)
        assertEquals(StopCause.LOOP, outcome.result.eventsOf<MoveEvent.Stopped>().single().cause)
        assertEquals(4, outcome.result.eventsOf<MoveEvent.Redirected>().size)
    }

    @Test
    fun coconutFillsPitAndHatchlingCrossesAfterwards() {
        val level = testLevel(
            "~....",
            ".....",
            "O.o..",
            "T....",
        )
        val sim = Simulator(level)
        val left = sim.apply(GameState.initial(level), Direction.LEFT)
        assertTrue(left.state.isPitFilled(level.board, level.cell(0, 2)))
        assertEquals(1, left.result.eventsOf<MoveEvent.PitFilled>().size)
        val up = sim.apply(left.state, Direction.UP)
        assertEquals(GameStatus.WON, up.state.status)
    }

    @Test
    fun hatchlingIntoUnfilledPitLoses() {
        val level = testLevel(
            "~....",
            "O....",
            "T....",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        assertEquals(GameStatus.LOST_PIT, outcome.state.status)
    }

    @Test
    fun crabStepsAlongPingPongPathAndWaitsWhenBlocked() {
        val level = testLevel(
            "~.....",
            ".c....",
            "......",
            "T.....",
            crabs = listOf(listOf(1 to 1, 2 to 1, 3 to 1)),
        )
        val board = level.board
        val sim = Simulator(level)
        var state = GameState.initial(level)
        val cells = mutableListOf(state.crabCells(board).single())
        repeat(5) {
            state = sim.apply(state, if (it % 2 == 0) Direction.RIGHT else Direction.LEFT).state
            cells += state.crabCells(board).single()
        }
        // Ping-pong: 1,2,3,2,1,2 along x.
        assertEquals(listOf(1, 2, 3, 2, 1, 2), cells.map { it % board.width })
    }

    @Test
    fun crabWaitsWhenItsNextCellIsOccupied() {
        val level = testLevel(
            "~#....",
            "c.....",
            "T.....",
            crabs = listOf(listOf(0 to 1, 1 to 1)),
        )
        val sim = Simulator(level)
        // Swipe RIGHT: the hatchling at (0,2) slides to (5,2); crab steps to (1,1).
        val right = sim.apply(GameState.initial(level), Direction.RIGHT)
        assertEquals(level.cell(1, 1), right.state.crabCells(level.board).single())
        // Swipe UP: the hatchling moves from (5,2) to (5,0). Crab steps back to (0,1).
        val up = sim.apply(right.state, Direction.UP)
        assertEquals(level.cell(0, 1), up.state.crabCells(level.board).single())
        // Blocked case: the hatchling slides up column 1 and stops under the rock on (1,1), the crab's next cell.
        val forced = testLevel(
            "~#....",
            "c.....",
            "......",
            ".T....",
            crabs = listOf(listOf(0 to 1, 1 to 1)),
        )
        val waited = Simulator(forced).apply(GameState.initial(forced), Direction.UP)
        assertEquals(forced.cell(1, 1), waited.state.hatchlings.single().cell)
        assertEquals(1, waited.result.eventsOf<MoveEvent.CrabWaited>().size)
        assertEquals(forced.cell(0, 1), waited.state.crabCells(forced.board).single())
    }

    @Test
    fun lateEggHatchesOnTheRightMoveAndMustBeSaved() {
        val level = testLevel(
            "~.....",
            "......",
            "..e...",
            "T.....",
            eggs = listOf(Triple(2, 2, 2)),
        )
        val sim = Simulator(level)
        val first = sim.apply(GameState.initial(level), Direction.UP)
        // First hatchling saved at once, but the egg has not hatched: the level continues.
        assertEquals(GameStatus.PLAYING, first.state.status)
        assertEquals(listOf(1), first.state.eggTimers)
        assertEquals(1, first.result.eventsOf<MoveEvent.EggWobble>().size)
        // A no-op does not advance the egg timer.
        val noop = sim.apply(first.state, Direction.LEFT)
        assertTrue(noop.result.noOp)
        assertEquals(listOf(1), noop.state.eggTimers)
        // With a second hatchling on the board, the next counted move hatches the egg.
        val level2 = testLevel(
            "~.....",
            "......",
            "..e...",
            "T....T",
            eggs = listOf(Triple(2, 2, 2)),
        )
        val sim2 = Simulator(level2)
        val m1 = sim2.apply(GameState.initial(level2), Direction.UP)
        assertEquals(listOf(1), m1.state.eggTimers)
        val m2 = sim2.apply(m1.state, Direction.LEFT)
        val hatched = m2.result.eventsOf<MoveEvent.Hatched>().single()
        assertEquals(level2.cell(2, 2), hatched.cell)
        assertEquals(listOf(0), m2.state.eggTimers)
        // The other hatchling reached the surf on this move, so only the new one remains.
        assertEquals(1, m2.state.hatchlings.size)
        assertEquals(GameStatus.PLAYING, m2.state.status)
        val m3 = sim2.apply(m2.state, Direction.UP)
        val m4 = sim2.apply(m3.state, Direction.LEFT)
        assertEquals(GameStatus.WON, m4.state.status)
    }

    @Test
    fun gullShadowOnlyMattersWhereAMoveEnds() {
        // The shadow moves from (2,1) to (3,1) after each move. The hatchling slides under (2,1) during
        // the move and ends at (5,1), so it is safe.
        val level = testLevel(
            "~.....",
            "T.....",
            "......",
            shadows = listOf(listOf(2 to 1, 3 to 1, 3 to 2, 2 to 2)),
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.RIGHT)
        assertEquals(GameStatus.PLAYING, outcome.state.status)
        assertEquals(level.cell(3, 1), outcome.state.shadowCells(level.board).single())
        assertEquals(1, outcome.result.eventsOf<MoveEvent.GullShadowMoved>().size)
    }
}
