package com.hatchslide.core.game

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates every shipped puzzle (spec section 12): all 150 world levels and 366 Daily Tide puzzles
 * load, are solvable within their gull counter, store the solver's optimal par, and every intro or
 * showcase level uses its mechanic on the optimal path.
 */
class LevelValidationTest {
    private val dir = File(System.getProperty("hatchslide.levels") ?: "../../app/src/main/assets/levels")
    private val parRanges = mapOf(1 to 1..6, 2 to 4..8, 3 to 5..10, 4 to 6..13, 5 to 8..18)

    private val worlds: Map<Int, List<Level>> by lazy {
        (1..5).associateWith { LevelCodec.parseWorld(File(dir, "world_$it.json").readText()) }
    }
    private val daily: List<Level> by lazy { LevelCodec.parseDaily(File(dir, "daily.json").readText()) }

    private fun checkSolvable(level: Level) {
        val result = Solver(level).solve()
        assertTrue("${level.id} is not solvable within ${level.gullMoves} moves: $result", result is SolveResult.Solved)
        result as SolveResult.Solved
        assertEquals("${level.id} stored par", result.moves, level.par)
        val replay = Simulator(level).play(result.path)
        assertEquals("${level.id} replay", GameStatus.WON, replay.last().state.status)
        assertTrue("${level.id} explores ${result.statesExplored} states", result.statesExplored < HINT_SOLVE_BUDGET)
        level.mechanic?.let { mechanic ->
            val used = MechanicUsage.used(level, result.path)
            assertTrue("${level.id} introduces $mechanic but its optimal path uses only $used", mechanic in used)
        }
    }

    @Test
    fun everyWorldLevelIsValid() {
        assertEquals(5, worlds.size)
        for ((world, levels) in worlds) {
            assertEquals("world $world level count", 30, levels.size)
            assertEquals((1..30).toList(), levels.map { it.index })
            assertEquals(levels.size, levels.map { it.name }.toSet().size)
            for (level in levels) {
                assertEquals(world, level.world)
                assertEquals("w${world}_l%02d".format(level.index), level.id)
                assertTrue("${level.id} par ${level.par}", level.par in parRanges.getValue(world))
                checkSolvable(level)
            }
        }
        assertEquals(150, worlds.values.sumOf { it.size })
    }

    @Test
    fun everyDailyTidePuzzleIsValid() {
        assertEquals(366, daily.size)
        assertEquals(daily.size, daily.map { it.id }.toSet().size)
        daily.forEach(::checkSolvable)
    }

    @Test
    fun tutorialFollowsTheTeachingPlan() {
        val tutorial = worlds.getValue(1).take(10)
        val first = tutorial[0]
        assertEquals(1, first.board.totalHatchlings)
        assertEquals(1, first.par)
        assertEquals(2, tutorial[1].board.totalHatchlings)
        val directions = mutableSetOf<Direction>()
        tutorial.forEach { directions += (Solver(it).solve() as SolveResult.Solved).path }
        assertEquals("all four directions by level 10", Direction.entries.toSet(), directions)
        assertEquals(Mechanic.ROCK, tutorial[3].mechanic)
    }

    @Test
    fun everyNewMechanicHasTwoShowcaseLevelsAndOneTip() {
        val expected = mapOf(
            2 to listOf(Mechanic.WET_SAND, Mechanic.CRACKED_SHELL),
            3 to listOf(Mechanic.CURRENT, Mechanic.COCONUT, Mechanic.PIT),
            4 to listOf(Mechanic.CRAB, Mechanic.LATE_EGG),
            5 to listOf(Mechanic.TIDE_POOL, Mechanic.GULL_SHADOW),
        )
        for ((world, mechanics) in expected) {
            val levels = worlds.getValue(world)
            for (mechanic in mechanics) {
                val showcases = levels.filter { it.mechanic == mechanic }
                assertEquals("world $world $mechanic showcases", 2, showcases.size)
                assertEquals("world $world $mechanic tips", 1, showcases.count { it.tip != null })
            }
        }
    }

    companion object {
        /** Matches the generator's cap; keeps the in-game hint under 200 ms on a mid-range phone. */
        const val HINT_SOLVE_BUDGET = 12_000
    }
}
