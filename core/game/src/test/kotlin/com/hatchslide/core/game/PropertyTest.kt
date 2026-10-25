package com.hatchslide.core.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10,000 random move sequences on random boards never crash and conserve pieces (spec section 12). */
class PropertyTest {
    @Test
    fun randomSequencesConservePiecesAndNeverCrash() {
        val random = Random(20261025)
        var sequences = 0
        var moves = 0
        while (sequences < 10_000) {
            val level = RandomBoards.level(random)
            val board = level.board
            val simulator = Simulator(level)
            repeat(10) {
                var state = simulator.initialState()
                val length = random.nextInt(1, 30)
                for (step in 0 until length) {
                    if (state.status != GameStatus.PLAYING) break
                    val outcome = simulator.apply(state, Direction.entries.random(random))
                    moves++
                    check(level, state, outcome)
                    state = outcome.state
                }
                sequences++
            }
            assertTrue(board.cellCount > 0)
        }
        assertTrue(moves > 50_000)
    }

    private fun check(level: Level, before: GameState, outcome: MoveOutcome) {
        val board = level.board
        val after = outcome.state
        val events = outcome.result.events
        if (outcome.result.noOp) {
            assertEquals(before, after)
            return
        }
        assertEquals(before.movesUsed + 1, after.movesUsed)

        val saved = events.count { it is MoveEvent.Saved }
        val fell = events.count { it is MoveEvent.FellInPit }
        val hatched = events.count { it is MoveEvent.Hatched }
        val sank = events.count { it is MoveEvent.CoconutSank }
        val filled = events.count { it is MoveEvent.PitFilled }
        assertEquals(before.hatchlings.size - saved - fell + hatched, after.hatchlings.size)
        assertEquals(before.coconuts.size - sank - filled, after.coconuts.size)
        assertEquals(before.savedCount + saved, after.savedCount)
        assertEquals(after.pieces.size, after.pieces.map { it.cell }.toSet().size)

        val blocked = after.crabCells(board).toSet() + after.eggCells(board).toSet()
        for (piece in after.pieces) {
            val tile = after.effectiveTile(board, piece.cell)
            assertFalse("piece on $tile", tile == TileType.ROCK || tile == TileType.SHELL || tile == TileType.CRACKED_SHELL)
            assertFalse("piece left on $tile", tile == TileType.OCEAN || tile == TileType.PIT)
            assertFalse("piece under a crab or egg", piece.cell in blocked)
        }
        if (fell > 0) assertEquals(GameStatus.LOST_PIT, after.status)
        if (after.status == GameStatus.WON) {
            assertTrue(after.hatchlings.isEmpty())
            assertTrue(after.eggTimers.all { it == 0 })
        }
    }
}
