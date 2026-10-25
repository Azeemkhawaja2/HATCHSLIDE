package com.hatchslide.tools.levelgen

import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.game.Mechanic
import com.hatchslide.core.game.SolveResult
import com.hatchslide.core.game.Solver
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.pow
import kotlin.math.roundToInt

/** Builds one world's 30 levels or the 366 Daily Tide puzzles from slot specs. */
class WorldBuilder(private val pool: ExecutorService, private val log: (String) -> Unit) {
    private fun slotSeed(world: Int, index: Int, attempt: Int) =
        0x5EA7L * 1_000_003L + world * 10_007L + index * 101L + attempt * 7_777_777L

    /** Runs slots in parallel; a failed slot is retried with a new seed and a looser par tolerance. */
    private fun fill(slots: List<SlotSpec>): List<Candidate> {
        val results = slots.map { slot ->
            pool.submit(
                Callable {
                    var candidate: Candidate? = null
                    var attempt = 0
                    while (candidate == null && attempt < 6) {
                        val relaxed = slot.copy(
                            seed = slot.seed + attempt * 7_777_777L,
                            parTolerance = slot.parTolerance + attempt / 2,
                        )
                        candidate = Generator(relaxed).generate()
                        attempt++
                    }
                    candidate ?: error("could not fill slot w${slot.world} #${slot.index} (target par ${slot.targetPar})")
                },
            )
        }
        return results.map { it.get() }
    }

    fun buildWorld(spec: WorldSpec): List<LevelFile> {
        val levels = mutableListOf<LevelFile>()
        if (spec.handMade > 0) levels += tutorialLevels(spec)

        // Showcase levels: two easy levels per new mechanic, the first carrying the tip card.
        val showcaseSlots = spec.showcases.flatMapIndexed { i, (mechanic, tip) ->
            val first = levels.size + 2 * i + 1
            listOf(
                Worlds.showcaseSlot(spec, mechanic, first, tip, slotSeed(spec.world, first, 0)),
                Worlds.showcaseSlot(spec, mechanic, first + 1, null, slotSeed(spec.world, first + 1, 0)),
            )
        }
        val firstRegular = levels.size + showcaseSlots.size + 1
        val regularIndexes = (firstRegular..30).toList()
        val extra = regularIndexes.size / 4 + 2
        val count = regularIndexes.size + extra
        val lowPar = spec.par.first + 1
        val targets = (0 until count).map { j ->
            val t = j.toDouble() / (count - 1)
            (lowPar + (spec.par.last - lowPar) * t.pow(0.85)).roundToInt()
        }
        val regularSlots = targets.mapIndexed { j, target ->
            val index = 100 + j
            SlotSpec(
                world = spec.world,
                index = index,
                widths = spec.widths,
                heights = spec.heights,
                hatchlings = spec.hatchlings,
                targetPar = target,
                parTolerance = if (target >= 12) 1 else 0,
                parRange = spec.par,
                // Final order is decided after generation; check with the largest slack this world uses.
                slack = (1..30).maxOf { spec.slack(it) },
                palette = spec.palette,
                extras = spec.extras,
                requiredAny = spec.newMechanics.toSet(),
                seed = slotSeed(spec.world, index, 0),
            )
        }
        log("world ${spec.world}: ${showcaseSlots.size} showcase + ${regularSlots.size} regular candidates")
        val showcases = fill(showcaseSlots)
        val regular = fill(regularSlots).sortedBy { it.difficulty }

        // Keep an even spread of the candidates, then carve out breathers for every 5th level.
        val chosen = regularIndexes.indices.map { i ->
            regular[
                (
                    i * (regular.size - 1).toDouble() /
                        (regularIndexes.size - 1).coerceAtLeast(1)
                    ).roundToInt(),
            ]
        }
        val breatherPositions = regularIndexes.filter { it % 5 == 0 && it != 30 }
        val breatherPicks = breatherPositions.indices.map { k ->
            (k * 0.6 * chosen.size / breatherPositions.size.coerceAtLeast(1)).toInt() +
                1
        }
            .map { it.coerceIn(0, chosen.size - 1) }
            .distinct()
        val breathers = breatherPicks.map { chosen[it] }
        val rest = chosen.filterIndexed { i, _ -> i !in breatherPicks }
        val ordered = mutableListOf<Candidate>()
        var b = 0
        var r = 0
        for (index in regularIndexes) {
            ordered += if (index in breatherPositions && b < breathers.size) breathers[b++] else rest[r++]
        }

        val reserved = levels.map { it.name }.toSet()
        val names = Names.forWorld(spec.world, showcases.size + ordered.size, seed = spec.world * 31L, reserved = reserved)
        var n = 0
        showcases.forEach { c -> levels += finalize(c, spec, c.slot.index, names[n++]) }
        ordered.forEachIndexed { i, c -> levels += finalize(c, spec, regularIndexes[i], names[n++]) }
        return levels.sortedBy { it.index }
    }

    private fun finalize(c: Candidate, spec: WorldSpec, index: Int, name: String): LevelFile = c.draft.toLevelFile(
        id = "w${spec.world}_l%02d".format(index),
        world = spec.world,
        index = index,
        name = name,
        par = c.par,
        gullMoves = c.par + spec.slack(index),
        seed = c.seed,
        tip = c.slot.tip,
        mechanic = c.slot.mechanic,
    )

    fun tutorialLevels(spec: WorldSpec): List<LevelFile> = Tutorial.levels.mapIndexed { i, hand ->
        val index = i + 1
        val draft = Draft.of(*hand.rows.toTypedArray())
        // Solve with a generous limit to find par; then the gull counter is par + world slack.
        val probe = LevelCodec.parse(draft.toLevelFile("probe", 1, index, hand.name, 1, 30, 0))
        val solve = Solver(probe).solve() as? SolveResult.Solved ?: error("tutorial level $index is unsolvable")
        draft.toLevelFile(
            id = "w1_l%02d".format(index),
            world = spec.world,
            index = index,
            name = hand.name,
            par = solve.moves,
            gullMoves = solve.moves + spec.slack(index),
            seed = 1_000L + index,
            tip = hand.tip,
            mechanic = hand.mechanic,
        )
    }

    fun buildDaily(count: Int = 366): List<LevelFile> {
        val profiles = Worlds.all.drop(1)
        val slots = (1..count).map { day ->
            val profile = profiles[(day * 7 + day / 3) % profiles.size]
            val weekday = (day - 1) % 7
            val target = 4 + weekday * 5 / 6 + if (weekday >= 5) 1 else 0
            val seed = slotSeed(9, day, 0)
            SlotSpec(
                world = 0,
                index = day,
                widths = 6..8,
                heights = 8..10,
                hatchlings = 2..4,
                targetPar = target,
                parTolerance = 0,
                parRange = 4..10,
                slack = 3,
                palette = profile.palette,
                extras = profile.extras.copy(crabs = profile.extras.crabs.first..minOf(2, profile.extras.crabs.last)),
                requiredAny = profiles.flatMap { it.newMechanics }.toSet() + setOf(Mechanic.ROCK, Mechanic.SHELL),
                seed = seed,
            )
        }
        log("daily: ${slots.size} puzzles")
        val candidates = fill(slots)
        val names = Names.forDaily(count, seed = 366L)
        return candidates.mapIndexed { i, c ->
            val day = i + 1
            c.draft.toLevelFile(
                id = "daily_%03d".format(day),
                world = 0,
                index = day,
                name = names[i],
                par = c.par,
                gullMoves = c.par + 3,
                seed = c.seed,
            )
        }
    }
}
