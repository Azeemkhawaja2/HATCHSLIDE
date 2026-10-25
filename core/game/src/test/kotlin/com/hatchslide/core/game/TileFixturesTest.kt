package com.hatchslide.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One fixture per cell type, entering the tile from all four directions (spec section 3).
 * Boards are 7x7 with the tile under test at the centre (3,3); the hatchling starts two cells
 * away on the side it comes from. A single ocean cell sits in the corner (6,6), off every path.
 */
class TileFixturesTest {
    private val center = 3

    private fun board(
        centerChar: Char,
        extra: Map<Pair<Int, Int>, Char> = emptyMap(),
        start: Pair<Int, Int>?,
        startChar: Char = 'T',
    ): Array<String> {
        val rows = Array(7) { CharArray(7) { '.' } }
        rows[6][6] = '~'
        rows[center][center] = centerChar
        extra.forEach { (xy, ch) -> rows[xy.second][xy.first] = ch }
        if (start != null) rows[start.second][start.first] = startChar
        return rows.map { String(it) }.toTypedArray()
    }

    private fun startFor(d: Direction) = (center - 2 * d.dx) to (center - 2 * d.dy)

    private fun edgeFrom(x: Int, y: Int, d: Direction): Pair<Int, Int> {
        var cx = x
        var cy = y
        while (cx + d.dx in 0..6 && cy + d.dy in 0..6) {
            cx += d.dx
            cy += d.dy
        }
        return cx to cy
    }

    private fun single(outcome: MoveOutcome): Int = outcome.state.pieces.single().cell

    @Test
    fun sandLetsPiecesSlideThrough() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('.', start = start))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            val (ex, ey) = edgeFrom(start.first, start.second, d)
            assertEquals("direction $d", level.cell(ex, ey), single(outcome))
            assertEquals(StopCause.EDGE, outcome.result.eventsOf<MoveEvent.Stopped>().single().cause)
        }
    }

    @Test
    fun rockAndShellBlockFromEverySide() {
        for (blocker in listOf('#', 'S')) {
            for (d in Direction.entries) {
                val start = startFor(d)
                val level = testLevel(*board(blocker, start = start))
                val outcome = Simulator(level).apply(GameState.initial(level), d)
                assertEquals("$blocker $d", level.cell(center - d.dx, center - d.dy), single(outcome))
                assertEquals(StopCause.BLOCKER, outcome.result.eventsOf<MoveEvent.Stopped>().single().cause)
            }
        }
    }

    @Test
    fun oceanSavesHatchlingsAndSinksCoconuts() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('~', start = start))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            assertTrue(outcome.state.pieces.isEmpty())
            assertEquals(GameStatus.WON, outcome.state.status)
            assertEquals(level.cell(center, center), outcome.result.eventsOf<MoveEvent.Saved>().single().cell)

            val coconutLevel = testLevel(*board('~', extra = mapOf((0 to 0) to 'T'), start = start, startChar = 'o'))
            val sank = Simulator(coconutLevel).apply(GameState.initial(coconutLevel), d)
            assertEquals(1, sank.result.eventsOf<MoveEvent.CoconutSank>().size)
            assertTrue(sank.state.coconuts.isEmpty())
        }
    }

    @Test
    fun wetSandStopsPieceOnIt() {
        for (d in Direction.entries) {
            val level = testLevel(*board('w', start = startFor(d)))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            assertEquals("$d", level.cell(center, center), single(outcome))
            assertEquals(StopCause.WET_SAND, outcome.result.eventsOf<MoveEvent.Stopped>().single().cause)
            // Leaving wet sand is unrestricted.
            val leave = Simulator(level).apply(outcome.state, d)
            val (ex, ey) = edgeFrom(center, center, d)
            assertEquals(level.cell(ex, ey), single(leave))
        }
    }

    @Test
    fun crackedShellBlocksOnceThenBreaksIntoSand() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('C', start = start))
            val sim = Simulator(level)
            val first = sim.apply(GameState.initial(level), d)
            assertFalse("hitting a cracked shell counts as a move", first.result.noOp)
            assertEquals(level.cell(center - d.dx, center - d.dy), single(first))
            assertEquals(1, first.result.eventsOf<MoveEvent.ShellCracked>().size)
            assertTrue(first.state.isShellBroken(level.board, level.cell(center, center)))
            val second = sim.apply(first.state, d)
            val (ex, ey) = edgeFrom(center, center, d)
            assertEquals(level.cell(ex, ey), single(second))
        }
    }

    @Test
    fun crackedShellBreaksEvenWhenThePieceIsAlreadyTouchingIt() {
        val level = testLevel(
            "~....",
            ".TC..",
            ".....",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.RIGHT)
        assertFalse(outcome.result.noOp)
        assertEquals(1, outcome.state.movesUsed)
        assertEquals(level.cell(1, 1), single(outcome))
    }

    @Test
    fun currentsRedirectFromEveryEntrySide() {
        for (arrow in Direction.entries) {
            val ch = when (arrow) {
                Direction.UP -> '^'
                Direction.DOWN -> 'v'
                Direction.LEFT -> '<'
                Direction.RIGHT -> '>'
            }
            for (d in Direction.entries) {
                val level = testLevel(*board(ch, start = startFor(d)))
                val outcome = Simulator(level).apply(GameState.initial(level), d)
                val (ex, ey) = edgeFrom(center, center, arrow)
                assertEquals("arrow $arrow entered moving $d", level.cell(ex, ey), single(outcome))
                val redirects = outcome.result.eventsOf<MoveEvent.Redirected>()
                if (arrow == d) assertTrue(redirects.isEmpty()) else assertEquals(arrow, redirects.single().to)
            }
        }
    }

    @Test
    fun pitSwallowsHatchlingsAndIsFilledByCoconuts() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('O', start = start))
            val fell = Simulator(level).apply(GameState.initial(level), d)
            assertEquals(GameStatus.LOST_PIT, fell.state.status)
            assertEquals(1, fell.result.eventsOf<MoveEvent.FellInPit>().size)

            val coconutLevel = testLevel(*board('O', extra = mapOf((0 to 0) to 'T'), start = start, startChar = 'o'))
            val filled = Simulator(coconutLevel).apply(GameState.initial(coconutLevel), d)
            assertEquals(GameStatus.PLAYING, filled.state.status)
            assertTrue(filled.state.isPitFilled(coconutLevel.board, coconutLevel.cell(center, center)))
            assertEquals(TileType.SAND, filled.state.effectiveTile(coconutLevel.board, coconutLevel.cell(center, center)))
            assertTrue(filled.state.coconuts.isEmpty())
        }
    }

    @Test
    fun tidePoolsTeleportAndKeepDirection() {
        // Pool 1 at the centre, its twin at (1,1).
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('1', extra = mapOf((1 to 1) to '1'), start = start))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            val (ex, ey) = edgeFrom(1, 1, d)
            assertEquals("$d", level.cell(ex, ey), single(outcome))
            val teleport = outcome.result.eventsOf<MoveEvent.Teleported>().single()
            assertEquals(level.cell(center, center), teleport.fromCell)
            assertEquals(level.cell(1, 1), teleport.toCell)
        }
    }

    @Test
    fun tidePoolBlocksWhileItsTwinHoldsAStoppedPiece() {
        // Pool 1 at (3,3) with its twin at (6,2) on the right edge.
        val level = testLevel(
            "~......",
            "......#",
            "......1",
            "TT.1...",
            ".......",
        )
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.RIGHT)
        // The front hatchling teleports to (6,2) and stops there at the edge. The follower then finds the
        // twin occupied by a piece that is not moving, so pool (3,3) blocks it at (2,3).
        assertEquals(listOf(level.cell(6, 2), level.cell(2, 3)), outcome.hatchlingCells())
        assertEquals(1, outcome.result.eventsOf<MoveEvent.Teleported>().size)
        val follower = outcome.state.pieces.first { it.cell == level.cell(2, 3) }
        val stop = outcome.result.eventsOf<MoveEvent.Stopped>().first { it.pieceId == follower.id }
        assertEquals(StopCause.PIECE, stop.cause)
    }

    @Test
    fun coconutSlidesAndBlocksFromEverySide() {
        for (d in Direction.entries) {
            // A coconut parked against a rock at the centre-side, a hatchling sliding into it.
            val rockAt = (center + d.dx) to (center + d.dy)
            val start = startFor(d)
            val level = testLevel(*board('o', extra = mapOf(rockAt to '#'), start = start))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            assertEquals(listOf(level.cell(center, center)), outcome.state.cellsOf(PieceKind.COCONUT))
            assertEquals(listOf(level.cell(center - d.dx, center - d.dy)), outcome.state.cellsOf(PieceKind.HATCHLING))
        }
    }

    @Test
    fun crabBlocksFromEverySide() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('c', start = start), crabs = listOf(listOf(center to center)))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            assertEquals(level.cell(center - d.dx, center - d.dy), single(outcome))
            assertEquals(StopCause.CRAB, outcome.result.eventsOf<MoveEvent.Stopped>().single().cause)
        }
    }

    @Test
    fun lateEggBlocksFromEverySideUntilItHatches() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val level = testLevel(*board('e', start = start), eggs = listOf(Triple(center, center, 3)))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            assertEquals(level.cell(center - d.dx, center - d.dy), outcome.state.cellsOf(PieceKind.HATCHLING).single())
            assertEquals(StopCause.EGG, outcome.result.eventsOf<MoveEvent.Stopped>().single().cause)
            assertEquals(listOf(2), outcome.state.eggTimers)
        }
    }

    @Test
    fun gullShadowLosesWhenAHatchlingEndsUnderIt() {
        for (d in Direction.entries) {
            val start = startFor(d)
            val (ex, ey) = edgeFrom(start.first, start.second, d)
            // The shadow loop moves onto the stopping cell after the move.
            val neighbour = if (ex > 0) (ex - 1) to ey else (ex + 1) to ey
            val level = testLevel(*board('.', start = start), shadows = listOf(listOf(neighbour, ex to ey)))
            val outcome = Simulator(level).apply(GameState.initial(level), d)
            assertEquals("$d", GameStatus.LOST_GULL_SHADOW, outcome.state.status)
        }
    }
}
