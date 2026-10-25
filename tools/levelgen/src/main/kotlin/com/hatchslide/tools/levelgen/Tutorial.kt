package com.hatchslide.tools.levelgen

import com.hatchslide.core.game.Mechanic

/** A hand-made level; par and gull moves are computed by the solver, never typed in. */
data class HandMade(val name: String, val tip: String?, val mechanic: Mechanic?, val rows: List<String>)

/**
 * World 1, levels 1-10 (spec section 4): one hatchling and one swipe, two hatchlings moving
 * together, one hatchling blocking another, rocks as stoppers, then shells, and by level 10 the
 * player has swiped in all four directions.
 */
object Tutorial {
    val levels: List<HandMade> = listOf(
        HandMade(
            "First Swim",
            "tip_swipe",
            Mechanic.BASICS,
            listOf(
                "~~~~~",
                ".....",
                "#...S",
                "..T..",
                ".....",
                "S...#",
                ".....",
            ),
        ),
        HandMade(
            "Side by Side",
            "tip_all_move",
            Mechanic.BASICS,
            listOf(
                "~~~~~",
                "..#.#",
                ".....",
                "..T.T",
                ".....",
                ".S...",
                ".....",
            ),
        ),
        HandMade(
            "Lean on Me",
            "tip_blocking",
            Mechanic.BASICS,
            listOf(
                "~~~~~",
                "###..",
                ".....",
                "T...T",
                ".....",
                ".....",
                ".S...",
            ),
        ),
        HandMade(
            "Rock Stop",
            "tip_rock",
            Mechanic.ROCK,
            listOf(
                "~~~~~",
                "##.##",
                ".....",
                ".....",
                "T..#.",
                ".....",
                "S....",
            ),
        ),
        HandMade(
            "Rock Garden",
            null,
            Mechanic.ROCK,
            listOf(
                "~~~~~~",
                "#.##.#",
                "......",
                "#....T",
                "T....#",
                "......",
                "..S...",
            ),
        ),
        HandMade(
            "Shell Stop",
            "tip_shell",
            Mechanic.SHELL,
            listOf(
                "~~~~~",
                "#.###",
                ".....",
                ".....",
                "S...T",
                ".....",
                "..#..",
            ),
        ),
        HandMade(
            "Shell Path",
            null,
            Mechanic.SHELL,
            listOf(
                "~~~~~~",
                "#.#.##",
                "....T.",
                "......",
                "S.....",
                "....S.",
                ".....T",
                "..S...",
            ),
        ),
        HandMade(
            "Down the Dunes",
            "tip_gull",
            null,
            listOf(
                "~~~~~",
                "##.##",
                "T....",
                ".....",
                ".....",
                ".....",
                "...#.",
                "#....",
            ),
        ),
        HandMade(
            "Three Little Turtles",
            null,
            null,
            listOf(
                "~~~~~~",
                "#...##",
                "..T.TT",
                "......",
                "S.....",
                "..#.SS",
                "......",
                ".S....",
            ),
        ),
        HandMade(
            "Every Which Way",
            null,
            null,
            listOf(
                "~~~~~",
                "#.###",
                "T....",
                ".....",
                ".....",
                "S....",
                "....#",
            ),
        ),
    )
}
