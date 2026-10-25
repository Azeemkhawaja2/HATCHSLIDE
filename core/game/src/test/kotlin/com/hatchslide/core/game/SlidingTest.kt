package com.hatchslide.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simultaneous sliding, conflicts and saves (spec section 2, rules 1-8). */
class SlidingTest {
    @Test
    fun lineOfHatchlingsFollowsIntoTheSurfAndAllAreSaved() {
        val level = testLevel(
            "~~~",
            ".T.",
            ".T.",
            ".T.",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        assertEquals(GameStatus.WON, outcome.state.status)
        val saves = outcome.result.eventsOf<MoveEvent.Saved>()
        assertEquals(listOf(0, 1, 2), saves.map { it.order })
        // Each save frees the ocean cell for the follower: all three enter the same cell on successive ticks.
        assertEquals(listOf(0, 1, 2), saves.map { it.tick })
        assertTrue(saves.all { it.cell == level.cell(1, 0) })
        assertEquals(3, outcome.result.ticks)
    }

    @Test
    fun followersStopBehindAStoppedPiece() {
        val level = testLevel(
            "~#~",
            "...",
            ".T.",
            ".T.",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        assertEquals(listOf(level.cell(1, 1), level.cell(1, 2)), outcome.hatchlingCells())
        val causes = outcome.result.eventsOf<MoveEvent.Stopped>().map { it.cause }.toSet()
        assertEquals(setOf(StopCause.BLOCKER, StopCause.PIECE), causes)
    }

    @Test
    fun noOpMoveChangesNothingAndDoesNotCount() {
        val level = testLevel(
            "~#~",
            ".T.",
            ".T.",
        )
        val state = GameState.initial(level)
        val outcome = Simulator(level).apply(state, Direction.UP)
        assertTrue(outcome.result.noOp)
        assertEquals(state, outcome.state)
        assertEquals(0, outcome.state.movesUsed)
    }

    @Test
    fun sameCellConflictGoesToThePieceFurthestAlongTheSwipe() {
        // D rides the '>' current across row 2 while C slides up column 1; both claim (1,2) on tick 1.
        // D is on row 2, C on row 3: D is further along UP, so D takes the cell and C stops.
        val level = testLevel(
            "~~~",
            "...",
            ">..",
            "T..",
            ".T.",
        )
        val d = level.cell(0, 3)
        val c = level.cell(1, 4)
        val start = GameState.initial(level)
        val dId = start.pieces.first { it.cell == d }.id
        val cId = start.pieces.first { it.cell == c }.id
        val outcome = Simulator(level).apply(start, Direction.UP)
        val stops = outcome.result.eventsOf<MoveEvent.Stopped>()
        assertEquals(StopCause.CONFLICT, stops.first { it.pieceId == cId }.cause)
        assertEquals(level.cell(1, 3), outcome.state.pieces.first { it.id == cId }.cell)
        assertEquals(level.cell(2, 2), outcome.state.pieces.first { it.id == dId }.cell)
    }

    @Test
    fun equalProgressConflictBreaksTieByLowerCellIndex() {
        // Both pieces ride currents toward (1,2) on the same row: the left piece (lower index) wins.
        val level = testLevel(
            "~~~",
            "...",
            ">.<",
            "T.T",
        )
        val start = GameState.initial(level)
        val leftId = start.pieces.first { it.cell == level.cell(0, 3) }.id
        val rightId = start.pieces.first { it.cell == level.cell(2, 3) }.id
        val outcome = Simulator(level).apply(start, Direction.UP)
        assertEquals(StopCause.CONFLICT, outcome.result.eventsOf<MoveEvent.Stopped>().first { it.pieceId == rightId }.cause)
        assertEquals(level.cell(1, 2), outcome.state.pieces.first { it.id == leftId }.cell)
        assertEquals(level.cell(2, 2), outcome.state.pieces.first { it.id == rightId }.cell)
    }

    @Test
    fun headOnSwapStopsBothPieces() {
        // After entering the currents the two pieces stand side by side facing each other, so each
        // would take the other's cell.
        val level = testLevel(
            "~~~~",
            "....",
            ".><.",
            ".TT.",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        assertEquals(listOf(level.cell(1, 2), level.cell(2, 2)), outcome.hatchlingCells())
        assertTrue(outcome.result.eventsOf<MoveEvent.Stopped>().all { it.cause == StopCause.PIECE })
    }

    @Test
    fun tracksRecordEveryTickAndEndAtTheOcean() {
        val level = testLevel(
            "~...",
            "....",
            "...T",
        )
        val sim = Simulator(level)
        val left = sim.apply(GameState.initial(level), Direction.LEFT)
        val track = left.result.tracks.single()
        assertEquals(listOf(level.cell(3, 2), level.cell(2, 2), level.cell(1, 2), level.cell(0, 2)), track.cells)
        val up = sim.apply(left.state, Direction.UP)
        val upTrack = up.result.tracks.single()
        assertEquals(listOf(level.cell(0, 2), level.cell(0, 1), level.cell(0, 0)), upTrack.cells)
        assertEquals(GameStatus.WON, up.state.status)
    }

    @Test
    fun loseWhenGullCounterRunsOut() {
        val level = testLevel(
            "~...",
            "#...",
            "...T",
            gullMoves = 2,
        )
        val sim = Simulator(level)
        val first = sim.apply(GameState.initial(level), Direction.LEFT)
        assertEquals(GameStatus.PLAYING, first.state.status)
        val second = sim.apply(first.state, Direction.RIGHT)
        assertEquals(GameStatus.LOST_OUT_OF_MOVES, second.state.status)
        assertFalse(sim.apply(second.state, Direction.LEFT).state.status == GameStatus.PLAYING)
    }

    @Test
    fun winOnTheLastAllowedMove() {
        val level = testLevel(
            "~...",
            "....",
            "...T",
            gullMoves = 2,
        )
        val outcomes = Simulator(level).play(listOf(Direction.LEFT, Direction.UP))
        assertEquals(GameStatus.WON, outcomes.last().state.status)
        assertEquals(2, outcomes.last().state.movesUsed)
    }
}
