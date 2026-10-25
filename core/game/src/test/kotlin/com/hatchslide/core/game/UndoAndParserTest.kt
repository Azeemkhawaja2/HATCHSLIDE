package com.hatchslide.core.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoAndParserTest {
    @Test
    fun undoRestoresIdenticalStateIncludingHazards() {
        val random = Random(99)
        repeat(200) {
            val level = RandomBoards.level(random)
            val session = GameSession(level)
            val snapshots = mutableListOf(session.state)
            repeat(15) {
                if (session.state.status == GameStatus.PLAYING) {
                    val outcome = session.swipe(Direction.entries.random(random))
                    if (!outcome.result.noOp) snapshots += session.state
                }
            }
            while (session.canUndo) {
                snapshots.removeAt(snapshots.lastIndex)
                val restored = session.undo()!!
                assertEquals(snapshots.last(), restored)
                assertEquals(snapshots.last().hashCode(), restored.hashCode())
            }
            assertEquals(GameState.initial(level), session.state)
            assertNull(session.undo())
        }
    }

    @Test
    fun noOpMoveDoesNotEnterHistory() {
        val level = testLevel("~#~", ".T.")
        val session = GameSession(level)
        session.swipe(Direction.UP)
        assertFalse(session.canUndo)
        session.swipe(Direction.LEFT)
        assertTrue(session.canUndo)
        session.restart()
        assertEquals(GameState.initial(level), session.state)
        assertFalse(session.canUndo)
    }

    @Test
    fun levelJsonRoundTrips() {
        val file = LevelFile(
            id = "w3_l07",
            world = 3,
            index = 7,
            name = "Moon Current",
            grid = listOf("~~~~~~~", "..#...>", ".T..o..", "#..O..S", "..T...#", "S.....T"),
            par = 7,
            gullMoves = 10,
            seed = 918273,
        )
        val world = WorldFile(3, listOf(file))
        val decoded = LevelCodec.parseWorld(LevelCodec.encodeWorld(world)).single()
        assertEquals(file, decoded.file)
        assertEquals(7, decoded.board.width)
        assertEquals(6, decoded.board.height)
        assertEquals(3, decoded.board.hatchlingStarts.size)
        assertEquals(1, decoded.board.coconutStarts.size)
        assertEquals(TileType.CURRENT_RIGHT, decoded.board.tile(6, 1))
        assertEquals(TileType.PIT, decoded.board.tile(3, 3))
    }

    @Test
    fun invalidLevelsAreRejectedWithReasons() {
        fun bad(vararg grid: String, crabs: List<CrabSpec> = emptyList(), eggs: List<EggSpec> = emptyList(), gull: Int = 5) {
            val file = LevelFile("bad", 1, 1, "Bad", grid.toList(), crabs = crabs, eggs = eggs, par = 1, gullMoves = gull, seed = 0)
            assertThrows(LevelFormatException::class.java) { LevelCodec.parse(file) }
        }
        bad("~~", "T") // ragged rows
        bad("~~", "TX") // unknown char
        bad("..", "T.") // no ocean
        bad("~~", "..") // no hatchlings
        bad("~~", "T.", gull = 0) // gull below par
        bad("~~~", "11T") // touching tide-pool twins
        bad("~~~", "1T.") // pool without twin
        bad("~~~", "c.T") // crab mark without crab spec
        bad("~~~", "c.T", crabs = listOf(CrabSpec(listOf(listOf(0, 1), listOf(2, 1))))) // path not contiguous
        bad("~~~", "e.T") // egg mark without egg spec
        bad("~~~", "e.T", eggs = listOf(EggSpec(listOf(0, 1), 0))) // timer out of range
    }
}
