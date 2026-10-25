package com.hatchslide.tools.levelgen

import com.hatchslide.core.game.Mechanic

/** Relative weights of fixed tiles scattered over the sand (rows below the shoreline). */
data class Palette(
    val rock: Double = 0.0,
    val shell: Double = 0.0,
    val wet: Double = 0.0,
    val cracked: Double = 0.0,
    val current: Double = 0.0,
    val pit: Double = 0.0,
) {
    val density: Double get() = rock + shell + wet + cracked + current + pit
}

/** Pieces and scripted hazards for a slot. */
data class Extras(
    val coconuts: IntRange = 0..0,
    val crabs: IntRange = 0..0,
    val eggs: IntRange = 0..0,
    val pools: IntRange = 0..0,
    val shadows: IntRange = 0..0,
    val inletChance: Double = 0.0,
)

/** One level slot to fill: size, pieces, palette, target par and the mechanic it must exercise. */
data class SlotSpec(
    val world: Int,
    val index: Int,
    val widths: IntRange,
    val heights: IntRange,
    val hatchlings: IntRange,
    val targetPar: Int,
    val parTolerance: Int,
    /** Hard limits for the accepted par (the world's range); tolerance never escapes it. */
    val parRange: IntRange,
    val slack: Int,
    val palette: Palette,
    val extras: Extras,
    /** The optimal path must use at least one of these. */
    val requiredAny: Set<Mechanic>,
    /** Intro and showcase levels name their mechanic in the JSON. */
    val mechanic: Mechanic? = null,
    val tip: String? = null,
    val seed: Long,
)

data class WorldSpec(
    val world: Int,
    val name: String,
    val widths: IntRange,
    val heights: IntRange,
    val hatchlings: IntRange,
    val par: IntRange,
    val newMechanics: List<Mechanic>,
    val palette: Palette,
    val extras: Extras,
    /** Showcase sequence: each new mechanic gets two easy levels; the first one carries the tip. */
    val showcases: List<Pair<Mechanic, String>>,
    val slack: (index: Int) -> Int,
    /** Level numbers filled by hand-made levels (world 1 tutorial). */
    val handMade: Int = 0,
)

object Worlds {
    val all: List<WorldSpec> = listOf(
        WorldSpec(
            world = 1,
            name = "Sunrise Cove",
            widths = 5..6,
            heights = 7..8,
            hatchlings = 1..3,
            par = 2..6,
            newMechanics = listOf(Mechanic.ROCK, Mechanic.SHELL),
            palette = Palette(rock = 0.11, shell = 0.07),
            extras = Extras(),
            showcases = emptyList(),
            slack = { 5 },
            handMade = 10,
        ),
        WorldSpec(
            world = 2,
            name = "Coral Coast",
            widths = 6..7,
            heights = 8..9,
            hatchlings = 2..4,
            par = 4..8,
            newMechanics = listOf(Mechanic.WET_SAND, Mechanic.CRACKED_SHELL),
            palette = Palette(rock = 0.08, shell = 0.04, wet = 0.07, cracked = 0.05),
            extras = Extras(inletChance = 0.3),
            showcases = listOf(Mechanic.WET_SAND to "tip_wet_sand", Mechanic.CRACKED_SHELL to "tip_cracked_shell"),
            slack = { 4 },
        ),
        WorldSpec(
            world = 3,
            name = "Moonlit Bay",
            widths = 6..7,
            heights = 9..10,
            hatchlings = 2..4,
            par = 5..10,
            newMechanics = listOf(Mechanic.CURRENT, Mechanic.COCONUT, Mechanic.PIT),
            palette = Palette(rock = 0.08, shell = 0.03, wet = 0.02, cracked = 0.02, current = 0.05, pit = 0.03),
            extras = Extras(coconuts = 1..2, inletChance = 0.25),
            showcases = listOf(Mechanic.CURRENT to "tip_current", Mechanic.COCONUT to "tip_coconut", Mechanic.PIT to "tip_pit"),
            slack = { 3 },
        ),
        WorldSpec(
            world = 4,
            name = "Crab Reef",
            widths = 7..8,
            heights = 9..11,
            hatchlings = 3..5,
            par = 6..13,
            newMechanics = listOf(Mechanic.CRAB, Mechanic.LATE_EGG),
            palette = Palette(rock = 0.09, shell = 0.03, wet = 0.03, cracked = 0.02, current = 0.02, pit = 0.01),
            extras = Extras(coconuts = 0..1, crabs = 1..3, eggs = 0..2, inletChance = 0.25),
            showcases = listOf(Mechanic.CRAB to "tip_crab", Mechanic.LATE_EGG to "tip_late_egg"),
            slack = { 2 },
        ),
        WorldSpec(
            world = 5,
            name = "Volcanic Isle",
            widths = 7..9,
            heights = 10..12,
            hatchlings = 3..6,
            par = 8..18,
            newMechanics = listOf(Mechanic.TIDE_POOL, Mechanic.GULL_SHADOW),
            palette = Palette(rock = 0.09, shell = 0.02, wet = 0.02, cracked = 0.02, current = 0.02, pit = 0.01),
            extras = Extras(crabs = 0..1, eggs = 0..1, pools = 1..2, shadows = 0..1, inletChance = 0.2),
            showcases = listOf(Mechanic.TIDE_POOL to "tip_tide_pool", Mechanic.GULL_SHADOW to "tip_gull_shadow"),
            slack = { index -> if (index <= 15) 2 else 1 },
        ),
    )

    /** Showcase levels keep only their own mechanic's extras and tiles (plus the basics). */
    fun showcaseSlot(spec: WorldSpec, mechanic: Mechanic, index: Int, tip: String?, seed: Long): SlotSpec {
        val base = Palette(rock = 0.10, shell = 0.04)
        val palette = when (mechanic) {
            Mechanic.WET_SAND -> base.copy(wet = 0.10)
            Mechanic.CRACKED_SHELL -> base.copy(cracked = 0.08)
            Mechanic.CURRENT -> base.copy(current = 0.07)
            Mechanic.PIT -> base.copy(pit = 0.04)
            else -> base
        }
        val extras = when (mechanic) {
            Mechanic.COCONUT -> Extras(coconuts = 1..2)
            Mechanic.PIT -> Extras(coconuts = 1..1)
            Mechanic.CRAB -> Extras(crabs = 1..2)
            Mechanic.LATE_EGG -> Extras(eggs = 1..1)
            Mechanic.TIDE_POOL -> Extras(pools = 1..1)
            Mechanic.GULL_SHADOW -> Extras(shadows = 1..1)
            else -> Extras()
        }
        val first = spec.par.first
        return SlotSpec(
            world = spec.world,
            index = index,
            widths = spec.widths.first..spec.widths.first,
            heights = spec.heights.first..spec.heights.first,
            hatchlings = spec.hatchlings.first..spec.hatchlings.first,
            targetPar = if (tip != null) first else first + 1,
            parTolerance = 0,
            parRange = spec.par,
            slack = spec.slack(index),
            palette = palette,
            extras = extras,
            requiredAny = setOf(mechanic),
            mechanic = mechanic,
            tip = tip,
            seed = seed,
        )
    }
}
