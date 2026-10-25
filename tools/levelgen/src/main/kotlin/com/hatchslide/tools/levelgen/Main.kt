package com.hatchslide.tools.levelgen

import com.hatchslide.core.game.DailyFile
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.MechanicUsage
import com.hatchslide.core.game.SolveResult
import com.hatchslide.core.game.Solver
import com.hatchslide.core.game.WorldFile
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Level generator CLI (spec section 4). Run from the repository root:
 *
 *   ./gradlew :tools:levelgen:run --args="generate"            all worlds and the Daily Tide
 *   ./gradlew :tools:levelgen:run --args="generate --worlds 3"  one world
 *   ./gradlew :tools:levelgen:run --args="tutorial"            solver report for the hand-made levels
 *   ./gradlew :tools:levelgen:run --args="report"              difficulty report for the committed files
 *
 * Output is deterministic: every slot has a fixed seed, so regenerating reproduces the same files.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "generate"
    val outDir = File(option(args, "--out") ?: "app/src/main/assets/levels")
    val threads = option(args, "--threads")?.toInt() ?: Runtime.getRuntime().availableProcessors()
    when (command) {
        "tutorial" -> tutorialReport()

        "generate" -> {
            val worlds = option(args, "--worlds")?.split(",")?.map { it.trim().toInt() } ?: (1..5).toList()
            val daily = option(args, "--worlds") == null || args.contains("--daily")
            generate(outDir, worlds, daily, threads)
        }

        "report" -> report(outDir)

        "bench" -> bench(outDir)

        "hintbench" -> hintBench(outDir)

        "enginebench" -> {
            val level = LevelCodec.parseWorld(File(outDir, "world_4.json").readText()).first { it.id == "w4_l30" }
            repeat(3) { com.hatchslide.core.game.EngineBenchmark.nanosPerMove(level, 2_000_000) }
            println("ns/move: " + com.hatchslide.core.game.EngineBenchmark.nanosPerMove(level, 5_000_000).first)
        }

        "probe" -> probe(File(args.getOrElse(1) { error("probe needs a file of blank-line separated grids") }))

        else -> {
            System.err.println("unknown command '$command' (use generate, tutorial or report)")
            exitProcess(2)
        }
    }
}

private fun option(args: Array<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun generate(outDir: File, worlds: List<Int>, daily: Boolean, threads: Int) {
    outDir.mkdirs()
    val pool = Executors.newFixedThreadPool(threads)
    val builder = WorldBuilder(pool) { println(it) }
    try {
        for (world in worlds) {
            val start = System.currentTimeMillis()
            val spec = Worlds.all.first { it.world == world }
            val levels = builder.buildWorld(spec)
            File(outDir, "world_$world.json").writeText(LevelCodec.encodeWorld(WorldFile(world, levels)) + "\n")
            println("world $world: ${levels.size} levels, pars ${levels.map { it.par }} in ${(System.currentTimeMillis() - start) / 1000}s")
        }
        if (daily) {
            val start = System.currentTimeMillis()
            val puzzles = builder.buildDaily()
            File(outDir, "daily.json").writeText(LevelCodec.encodeDaily(DailyFile(puzzles)) + "\n")
            println("daily: ${puzzles.size} puzzles in ${(System.currentTimeMillis() - start) / 1000}s")
        }
    } finally {
        pool.shutdown()
    }
}

private fun describe(level: Level): String {
    val started = System.nanoTime()
    Solver(level).solve()
    val solveMs = (System.nanoTime() - started) / 1_000_000.0
    val analysis = Solver(level).analyze()
    val solve = analysis.solve as? SolveResult.Solved ?: return "${level.id}: UNSOLVABLE"
    val used = MechanicUsage.used(level, solve.path) - com.hatchslide.core.game.Mechanic.BASICS
    return "%-9s %-22s %dx%-2d par=%-2d gull=%-2d first=%d sols=%-4d states=%-6d dead=%-6d solve=%5.1fms path=%s uses=%s".format(
        level.id,
        level.name,
        level.board.width,
        level.board.height,
        solve.moves,
        level.gullMoves,
        solve.optimalFirstMoves.size,
        solve.optimalSolutions,
        analysis.states,
        analysis.deadEnds,
        solveMs,
        solve.path.joinToString("") { it.name.first().toString() },
        used.joinToString(","),
    )
}

private fun tutorialReport() {
    val directions = mutableSetOf<Direction>()
    Tutorial.levels.forEachIndexed { i, hand ->
        val draft = Draft.of(*hand.rows.toTypedArray())
        val level = LevelCodec.parse(draft.toLevelFile("w1_l%02d".format(i + 1), 1, i + 1, hand.name, 1, 20, 0))
        (Solver(level).solve() as? SolveResult.Solved)?.let { directions += it.path }
        println(describe(level) + "  cumulative=" + directions.joinToString("") { it.name.first().toString() })
    }
}

/** Solves every grid in a text file (grids separated by blank lines); used while designing levels by hand. */
private fun probe(file: File) {
    file.readText().trim().split(Regex("\\n\\s*\\n")).forEachIndexed { i, block ->
        val rows = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val level = LevelCodec.parse(Draft.of(*rows.toTypedArray()).toLevelFile("probe_$i", 1, i + 1, "Probe $i", 1, 20, 0))
        println(describe(level))
    }
}

/** Warm solver timings (best of 5) for every shipped level, slowest first. */
private fun bench(outDir: File) {
    val levels = (1..5).flatMap { LevelCodec.parseWorld(File(outDir, "world_$it.json").readText()) } +
        LevelCodec.parseDaily(File(outDir, "daily.json").readText())
    repeat(2) { levels.forEach { Solver(it).solve() } }
    val timings = levels.map { level ->
        val best = (1..5).minOf {
            val t = System.nanoTime()
            Solver(level).solve()
            System.nanoTime() - t
        }
        level to best / 1_000_000.0
    }.sortedByDescending { it.second }
    timings.take(12).forEach { (level, ms) ->
        val s = Solver(level).solve()
        println("%-10s %6.1f ms  states=%d par=%d".format(level.id, ms, s.statesExplored, level.par))
    }
    println("median %.2f ms, total %.0f ms".format(timings[timings.size / 2].second, timings.sumOf { it.second }))
}

/** Hint latency from random mid-game positions (including dead ones that need an exhaustive proof). */
private fun hintBench(outDir: File) {
    val levels = (1..5).flatMap { LevelCodec.parseWorld(File(outDir, "world_$it.json").readText()) } +
        LevelCodec.parseDaily(File(outDir, "daily.json").readText())
    val random = kotlin.random.Random(5)
    repeat(2) { levels.forEach { com.hatchslide.core.game.Hints.next(it, com.hatchslide.core.game.GameState.initial(it)) } }
    var worst = 0.0
    var worstId = ""
    var unsolvable = 0
    var samples = 0
    for (level in levels) {
        val sim = com.hatchslide.core.game.Simulator(level)
        repeat(30) {
            var state = sim.initialState()
            repeat(random.nextInt(0, level.gullMoves)) {
                if (state.status ==
                    com.hatchslide.core.game.GameStatus.PLAYING
                ) {
                    state = sim.apply(state, Direction.entries.random(random)).state
                }
            }
            if (state.status != com.hatchslide.core.game.GameStatus.PLAYING) return@repeat
            val t = System.nanoTime()
            val hint = com.hatchslide.core.game.Hints.next(level, state)
            val ms = (System.nanoTime() - t) / 1e6
            samples++
            if (hint == com.hatchslide.core.game.Hint.Unsolvable) unsolvable++
            if (ms > worst) {
                worst = ms
                worstId = "${level.id} after ${state.movesUsed} moves ($hint)"
            }
        }
    }
    println("hint samples=$samples unsolvable=$unsolvable worst=%.1f ms at %s".format(worst, worstId))
}

private fun report(outDir: File) {
    for (world in 1..5) {
        val file = File(outDir, "world_$world.json")
        if (!file.exists()) continue
        LevelCodec.parseWorld(file.readText()).forEach { println(describe(it)) }
    }
    val daily = File(outDir, "daily.json")
    if (daily.exists()) {
        val levels = LevelCodec.parseDaily(daily.readText())
        println("daily: ${levels.size} puzzles, pars ${levels.groupingBy { it.par }.eachCount().toSortedMap()}")
    }
}
