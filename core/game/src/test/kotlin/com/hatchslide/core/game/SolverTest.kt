package com.hatchslide.core.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolverTest {
    @Test
    fun solvesOneMoveLevel() {
        val level = testLevel("~", "T")
        val result = Solver(level).solve() as SolveResult.Solved
        assertEquals(1, result.moves)
        assertEquals(listOf(Direction.UP), result.path)
    }

    @Test
    fun countsOptimalFirstMovesAndSolutions() {
        val level = testLevel(
            "~...",
            ".#..",
            "...T",
        )
        val result = Solver(level).solve() as SolveResult.Solved
        assertEquals(2, result.moves)
        assertEquals(setOf(Direction.LEFT, Direction.UP), result.optimalFirstMoves)
        assertEquals(2L, result.optimalSolutions)
        assertEquals(GameStatus.WON, Simulator(level).play(result.path).last().state.status)
    }

    @Test
    fun reportsUnsolvableWithinTheGullLimit() {
        val boxed = testLevel(
            "~....",
            "..#..",
            ".#T#.",
            "..#..",
        )
        assertTrue(Solver(boxed).solve() is SolveResult.Unsolvable)
        assertEquals(Hint.Unsolvable, Hints.next(boxed, GameState.initial(boxed)))

        val tooFew = testLevel(
            "~...",
            ".#..",
            "...T",
            gullMoves = 1,
        )
        assertTrue(Solver(tooFew).solve() is SolveResult.Unsolvable)
    }

    @Test
    fun hintFromMidGameUsesRemainingMoves() {
        val level = testLevel(
            "~...",
            ".#..",
            "...T",
            gullMoves = 3,
        )
        val sim = Simulator(level)
        // DOWN is a no-op (the hatchling is on the bottom row) and does not use a move.
        assertTrue(sim.apply(GameState.initial(level), Direction.DOWN).result.noOp)
        val wasted = sim.play(listOf(Direction.LEFT, Direction.RIGHT)).last().state
        assertEquals(2, wasted.movesUsed)
        // One move left and the hatchling is back at (3,2): it needs two moves, so the hint says unsolvable.
        assertEquals(Hint.Unsolvable, Hints.next(level, wasted))
        val oneIn = sim.apply(GameState.initial(level), Direction.LEFT).state
        assertEquals(Hint.Move(Direction.UP, 1), Hints.next(level, oneIn))
    }

    @Test
    fun solverMatchesNaiveSearchOnRandomBoards() {
        val random = Random(7)
        var compared = 0
        var solvable = 0
        while (compared < 300) {
            val level = RandomBoards.level(random, maxWidth = 6, maxHeight = 7, gullMoves = 6)
            val fast = Solver(level).solve()
            val naive = naiveSolve(level)
            when (fast) {
                is SolveResult.Solved -> {
                    solvable++
                    assertEquals(naive, fast.moves)
                    val replay = Simulator(level).play(fast.path)
                    assertEquals(GameStatus.WON, replay.last().state.status)
                    assertEquals(fast.moves, replay.size)
                }

                is SolveResult.Unsolvable -> assertEquals(null, naive)

                is SolveResult.TooLarge -> error("unexpected budget overflow")
            }
            compared++
        }
        assertTrue("too few solvable random boards: $solvable", solvable > 20)
    }

    /** Plain BFS over [GameState]s through the public simulator, keyed by interchangeable positions. */
    private fun naiveSolve(level: Level): Int? {
        val sim = Simulator(level)
        fun key(s: GameState) = listOf(
            s.cellsOf(PieceKind.HATCHLING),
            s.cellsOf(PieceKind.COCONUT),
            s.brokenShells,
            s.filledPits,
            s.crabPhases,
            s.eggTimers,
            s.shadowPhases,
        )
        var frontier = listOf(GameState.initial(level))
        val seen = hashSetOf(key(frontier[0]))
        var depth = 0
        while (frontier.isNotEmpty() && depth < level.gullMoves) {
            depth++
            val next = mutableListOf<GameState>()
            for (state in frontier) {
                for (d in Direction.entries) {
                    val outcome = sim.apply(state, d)
                    if (outcome.result.noOp) continue
                    when (outcome.state.status) {
                        GameStatus.WON -> return depth
                        GameStatus.PLAYING -> if (seen.add(key(outcome.state))) next += outcome.state
                        else -> Unit
                    }
                }
            }
            frontier = next
        }
        return null
    }

    @Test
    fun analysisCountsDeadEnds() {
        val level = testLevel(
            "~....",
            "..O..",
            "....T",
            gullMoves = 4,
        )
        val analysis = Solver(level).analyze()
        val solved = analysis.solve as SolveResult.Solved
        assertEquals(2, solved.moves)
        assertTrue(analysis.states >= 2)
        assertTrue(analysis.branching > 0.0)
    }
}
