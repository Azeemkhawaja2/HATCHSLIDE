package com.hatchslide.tools.levelgen

import com.hatchslide.core.game.Analysis
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFormatException
import com.hatchslide.core.game.Mechanic
import com.hatchslide.core.game.MechanicUsage
import com.hatchslide.core.game.Simulator
import com.hatchslide.core.game.SolveResult
import com.hatchslide.core.game.Solver
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** An accepted board with its solver metrics. */
data class Candidate(
    val slot: SlotSpec,
    val draft: Draft,
    val level: Level,
    val solve: SolveResult.Solved,
    val analysis: Analysis,
    val seed: Long,
) {
    val par: Int get() = solve.moves

    /** Higher is harder: par, few optimal openings, few optimal lines, many dead ends, more hatchlings. */
    val difficulty: Double
        get() {
            val deadEndRatio = if (analysis.states == 0) 0.0 else analysis.deadEnds.toDouble() / analysis.states
            return par +
                (4 - solve.optimalFirstMoves.size) * 0.6 +
                1.5 / sqrt(solve.optimalSolutions.coerceAtLeast(1).toDouble()) +
                deadEndRatio * 2.0 +
                level.board.totalHatchlings * 0.3
        }
}

/**
 * Searches for a board that meets a [SlotSpec]: random start, then simulated annealing over small
 * mutations, scoring each draft with the solver. Deterministic for a given slot seed.
 */
class Generator(private val slot: SlotSpec) {
    private val random = Random(slot.seed)
    private val searchLimit = slot.targetPar + slot.parTolerance + 3

    private data class Scored(val draft: Draft, val cost: Double, val level: Level?, val solve: SolveResult.Solved?)

    fun generate(restarts: Int = 60, iterations: Int = 500): Candidate? {
        repeat(restarts) { restart ->
            var current = score(randomDraft())
            var temperature = 3.0
            repeat(iterations) {
                val next = score(mutate(current.draft))
                val delta = next.cost - current.cost
                if (delta <= 0 || random.nextDouble() < exp(-delta / temperature)) current = next
                temperature = (temperature * 0.992).coerceAtLeast(0.15)
                if (current.cost == 0.0) {
                    val accepted = finish(current, restart)
                    if (accepted != null) return accepted
                    current = score(mutate(current.draft))
                }
            }
        }
        return null
    }

    private fun finish(scored: Scored, restart: Int): Candidate? {
        val par = scored.solve!!.moves
        if (par !in slot.parRange) return null
        val gullMoves = par + slot.slack
        val file = scored.draft.toLevelFile(
            id = "pending",
            world = slot.world,
            index = slot.index,
            name = "pending",
            par = par,
            gullMoves = gullMoves,
            seed = slot.seed + restart,
            tip = slot.tip,
            mechanic = slot.mechanic,
        )
        val level = LevelCodec.parse(file)
        val analysis = Solver(level).analyze(maxStates = HINT_STATE_CAP)
        val solve = analysis.solve as? SolveResult.Solved ?: return null
        if (solve.moves != par) return null
        if (analysis.states >= HINT_STATE_CAP) return null
        // The in-game hint solves from the current state; from the start it must stay small.
        if (Solver(level).solve(maxStates = SOLVE_STATE_CAP).statesExplored >= SOLVE_STATE_CAP) return null
        return Candidate(slot, scored.draft.copy(), level, solve, analysis, file.seed)
    }

    private fun score(draft: Draft): Scored {
        val file = draft.toLevelFile("draft", slot.world, slot.index, "draft", 1, searchLimit, slot.seed)
        val level = try {
            LevelCodec.parse(file)
        } catch (_: LevelFormatException) {
            return Scored(draft, 1000.0, null, null)
        }
        val result = Solver(level).solve(maxStates = SEARCH_STATE_CAP)
        if (result !is SolveResult.Solved) return Scored(draft, 40.0 + slot.targetPar, level, null)
        var cost = clutter(draft)
        val miss = abs(result.moves - slot.targetPar)
        if (miss > slot.parTolerance) cost += miss * 6.0
        if (result.moves !in slot.parRange) cost += 6.0
        val used = MechanicUsage.used(level, result.path)
        if (slot.requiredAny.none { it in used }) cost += 8.0
        if (solvedByOneDirection(level)) cost += 25.0
        if (result.optimalFirstMoves.size == 4) cost += 2.0
        return Scored(draft, cost, level, result)
    }

    /** Penalty for boards denser than the world's palette intends; keeps layouts readable. */
    private fun clutter(draft: Draft): Double {
        var fixed = 0
        for (y in 1 until draft.height) for (x in 0 until draft.width) if (isFixedTile(draft[x, y])) fixed++
        val density = fixed.toDouble() / (draft.width * (draft.height - 1))
        val cap = slot.palette.density + 0.06
        return if (density > cap) (density - cap) * 60.0 else 0.0
    }

    private fun solvedByOneDirection(level: Level): Boolean {
        val simulator = Simulator(level)
        for (d in Direction.entries) {
            var state = simulator.initialState()
            repeat(level.gullMoves) {
                if (state.status == GameStatus.PLAYING) {
                    val outcome = simulator.apply(state, d)
                    if (outcome.result.noOp) return@repeat
                    state = outcome.state
                }
            }
            if (state.status == GameStatus.WON) return true
        }
        return false
    }

    // ----- Drafts -----

    private fun randomDraft(): Draft {
        val width = slot.widths.random(random)
        val height = slot.heights.random(random)
        val d = Draft(width, height)
        // Shoreline: ocean with a few reef rocks, never fewer than two open surf cells.
        for (x in 0 until width) d[x, 0] = if (random.nextDouble() < 0.28) '#' else '~'
        if (d.count('~') < 2) {
            d[0, 0] = '~'
            d[width - 1, 0] = '~'
        }
        if (random.nextDouble() < slot.extras.inletChance) {
            val x = if (random.nextBoolean()) 0 else width - 1
            for (y in 1..random.nextInt(1, 3)) d[x, y] = '~'
        }
        for (y in 1 until height) {
            for (x in 0 until width) {
                if (d[x, y] == '.' && random.nextDouble() < slot.palette.density) d[x, y] = randomTile()
            }
        }
        repeat(slot.hatchlings.random(random)) { placeOnFreeSand(d, 'T') }
        repeat(slot.extras.coconuts.random(random)) { placeOnFreeSand(d, 'o') }
        repeat(slot.extras.pools.random(random)) { addPool(d) }
        repeat(slot.extras.crabs.random(random)) { addCrab(d) }
        repeat(slot.extras.eggs.random(random)) { addEgg(d) }
        repeat(slot.extras.shadows.random(random)) { addShadow(d) }
        return d
    }

    private fun randomTile(): Char {
        val p = slot.palette
        var r = random.nextDouble() * p.density
        r -= p.rock
        if (r < 0) return '#'
        r -= p.shell
        if (r < 0) return 'S'
        r -= p.wet
        if (r < 0) return 'w'
        r -= p.cracked
        if (r < 0) return 'C'
        r -= p.current
        if (r < 0) return "<>^v"[random.nextInt(4)]
        return 'O'
    }

    private fun freeSand(d: Draft): List<Pair<Int, Int>> {
        val crabCells = d.crabCells()
        val shadowCells = d.shadows.flatten().toSet()
        return d.cells().filter { (x, y) -> y > 0 && d[x, y] == '.' && (x to y) !in crabCells && (x to y) !in shadowCells }
    }

    private fun placeOnFreeSand(d: Draft, ch: Char): Pair<Int, Int>? {
        val cell = freeSand(d).randomOrNull(random) ?: return null
        d[cell.first, cell.second] = ch
        return cell
    }

    private fun addPool(d: Draft) {
        val number = (1..4).firstOrNull { n -> d.count('0' + n) == 0 } ?: return
        val free = freeSand(d)
        repeat(20) {
            val a = free.randomOrNull(random) ?: return
            val b = free.randomOrNull(random) ?: return
            if (abs(a.first - b.first) + abs(a.second - b.second) >= 3) {
                d[a.first, a.second] = '0' + number
                d[b.first, b.second] = '0' + number
                return
            }
        }
    }

    private fun addCrab(d: Draft) {
        val start = freeSand(d).randomOrNull(random) ?: return
        val path = mutableListOf(start)
        val horizontal = random.nextBoolean()
        val length = random.nextInt(2, 5)
        val step = if (horizontal) (if (random.nextBoolean()) 1 to 0 else -1 to 0) else (if (random.nextBoolean()) 0 to 1 else 0 to -1)
        while (path.size < length) {
            val (x, y) = path.last()
            val next = x + step.first to y + step.second
            if (!d.inside(next.first, next.second) || next.second == 0 || d[next.first, next.second] !in ".w") break
            if (next in d.crabCells()) break
            path += next
        }
        if (path.size < 2) return
        d[start.first, start.second] = 'c'
        d.crabs += path.toList()
    }

    private fun addEgg(d: Draft) {
        val cell = placeOnFreeSand(d, 'e') ?: return
        d.eggs += Triple(cell.first, cell.second, random.nextInt(2, 6))
    }

    private fun addShadow(d: Draft) {
        val w = random.nextInt(2, 4)
        val h = random.nextInt(2, 4)
        if (d.width <= w || d.height <= h + 1) return
        val x0 = random.nextInt(0, d.width - w + 1)
        val y0 = random.nextInt(1, d.height - h + 1)
        val loop = mutableListOf<Pair<Int, Int>>()
        for (x in x0 until x0 + w) loop += x to y0
        for (y in y0 + 1 until y0 + h) loop += x0 + w - 1 to y
        for (x in x0 + w - 2 downTo x0) loop += x to y0 + h - 1
        for (y in y0 + h - 2 downTo y0 + 1) loop += x0 to y
        val rotation = random.nextInt(loop.size)
        d.shadows += loop.drop(rotation) + loop.take(rotation)
    }

    // ----- Mutations -----

    private fun mutate(source: Draft): Draft {
        val d = source.copy()
        repeat(if (random.nextDouble() < 0.3) 2 else 1) {
            when (random.nextInt(10)) {
                0, 1, 2 -> retile(d)
                3, 4 -> moveChar(d, 'T')
                5 -> swapTiles(d)
                6 -> toggleReef(d)
                7 -> moveExtra(d)
                8 -> if (d.count('o') > 0) moveChar(d, 'o') else retile(d)
                else -> retile(d)
            }
        }
        return d
    }

    private fun isFixedTile(ch: Char) = ch in "#SwC<>^vO"

    private fun retile(d: Draft) {
        val x = random.nextInt(d.width)
        val y = random.nextInt(1, d.height)
        val ch = d[x, y]
        if (ch != '.' && !isFixedTile(ch)) return
        if ((x to y) in d.crabCells() && ch == '.') return
        d[x, y] = if (ch == '.' || random.nextDouble() < 0.4) randomTile() else '.'
        if ((x to y) in d.crabCells() && d[x, y] !in ".w") d[x, y] = '.'
    }

    private fun moveChar(d: Draft, ch: Char) {
        val from = d.cellsWith(ch).randomOrNull(random) ?: return
        val to = freeSand(d).randomOrNull(random) ?: return
        d[from.first, from.second] = '.'
        d[to.first, to.second] = ch
    }

    private fun swapTiles(d: Draft) {
        val a = random.nextInt(d.width) to random.nextInt(1, d.height)
        val b = random.nextInt(d.width) to random.nextInt(1, d.height)
        val ca = d[a.first, a.second]
        val cb = d[b.first, b.second]
        val movable = { c: Char -> c == '.' || isFixedTile(c) }
        if (!movable(ca) || !movable(cb)) return
        val crabCells = d.crabCells()
        if ((a in crabCells && cb !in ".w") || (b in crabCells && ca !in ".w")) return
        d[a.first, a.second] = cb
        d[b.first, b.second] = ca
    }

    private fun toggleReef(d: Draft) {
        val x = random.nextInt(d.width)
        if (d[x, 0] == '~' && d.count('~') > 2) {
            d[x, 0] = '#'
        } else if (d[x, 0] == '#') {
            d[x, 0] = '~'
        }
    }

    private fun moveExtra(d: Draft) {
        when (random.nextInt(4)) {
            0 -> if (d.crabs.isNotEmpty()) {
                val i = random.nextInt(d.crabs.size)
                val (sx, sy) = d.crabs[i][0]
                d[sx, sy] = '.'
                d.crabs.removeAt(i)
                addCrab(d)
            }

            1 -> if (d.eggs.isNotEmpty()) {
                val i = random.nextInt(d.eggs.size)
                val (x, y, t) = d.eggs[i]
                if (random.nextBoolean()) {
                    d.eggs[i] = Triple(x, y, (t + if (random.nextBoolean()) 1 else -1).coerceIn(2, 7))
                } else {
                    val to = freeSand(d).randomOrNull(random) ?: return
                    d[x, y] = '.'
                    d[to.first, to.second] = 'e'
                    d.eggs[i] = Triple(to.first, to.second, t)
                }
            }

            2 -> {
                val number = (1..4).filter { d.count('0' + it) == 2 }.randomOrNull(random) ?: return
                val cells = d.cellsWith('0' + number)
                val (x, y) = cells.random(random)
                val other = cells.first { it != (x to y) }
                val to =
                    freeSand(d).filter { abs(it.first - other.first) + abs(it.second - other.second) >= 3 }.randomOrNull(random) ?: return
                d[x, y] = '.'
                d[to.first, to.second] = '0' + number
            }

            else -> if (d.shadows.isNotEmpty()) {
                d.shadows.removeAt(random.nextInt(d.shadows.size))
                addShadow(d)
            }
        }
    }

    companion object {
        /** Exhaustive state count allowed for a shipped level; bounds every in-game hint search. */
        const val HINT_STATE_CAP = 60_000

        /** States a solve from the start may explore (about 70 ms on a desktop JVM). */
        const val SOLVE_STATE_CAP = 12_000
        const val SEARCH_STATE_CAP = 60_000
    }
}
