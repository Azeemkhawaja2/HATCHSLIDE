package com.hatchslide.core.designsystem

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** String resources for worlds, species and achievements, shared by every screen. */
object GameText {
    @StringRes
    fun worldName(world: Int): Int = when (world) {
        1 -> R.string.world_1_name
        2 -> R.string.world_2_name
        3 -> R.string.world_3_name
        4 -> R.string.world_4_name
        else -> R.string.world_5_name
    }

    private val species = arrayOf(
        Triple(
            R.string.species_green,
            R.string.species_green_fact_1,
            intArrayOf(R.string.species_green_fact_2, R.string.species_green_fact_3),
        ),
        Triple(
            R.string.species_loggerhead,
            R.string.species_loggerhead_fact_1,
            intArrayOf(R.string.species_loggerhead_fact_2, R.string.species_loggerhead_fact_3),
        ),
        Triple(
            R.string.species_hawksbill,
            R.string.species_hawksbill_fact_1,
            intArrayOf(R.string.species_hawksbill_fact_2, R.string.species_hawksbill_fact_3),
        ),
        Triple(
            R.string.species_leatherback,
            R.string.species_leatherback_fact_1,
            intArrayOf(R.string.species_leatherback_fact_2, R.string.species_leatherback_fact_3),
        ),
        Triple(
            R.string.species_kemps_ridley,
            R.string.species_kemps_ridley_fact_1,
            intArrayOf(R.string.species_kemps_ridley_fact_2, R.string.species_kemps_ridley_fact_3),
        ),
        Triple(
            R.string.species_olive_ridley,
            R.string.species_olive_ridley_fact_1,
            intArrayOf(R.string.species_olive_ridley_fact_2, R.string.species_olive_ridley_fact_3),
        ),
        Triple(
            R.string.species_flatback,
            R.string.species_flatback_fact_1,
            intArrayOf(R.string.species_flatback_fact_2, R.string.species_flatback_fact_3),
        ),
    )

    @StringRes
    fun speciesName(index: Int): Int = species[index.mod(species.size)].first

    /** The three facts of a species. */
    fun speciesFacts(index: Int): IntArray = species[index.mod(species.size)].let { intArrayOf(it.second) + it.third }

    @PluralsRes
    val speciesUnlockDaily: Int = R.plurals.species_unlock_daily

    private val achievements = mapOf(
        "first_save" to (R.string.achievement_first_save to R.string.achievement_first_save_body),
        "saves_100" to (R.string.achievement_saves_100 to R.string.achievement_saves_100_body),
        "saves_1000" to (R.string.achievement_saves_1000 to R.string.achievement_saves_1000_body),
        "first_three_stars" to (R.string.achievement_first_three_stars to R.string.achievement_first_three_stars_body),
        "stars_world_1" to (R.string.achievement_stars_world_1 to R.string.achievement_stars_world_1_body),
        "stars_world_2" to (R.string.achievement_stars_world_2 to R.string.achievement_stars_world_2_body),
        "stars_world_3" to (R.string.achievement_stars_world_3 to R.string.achievement_stars_world_3_body),
        "stars_world_4" to (R.string.achievement_stars_world_4 to R.string.achievement_stars_world_4_body),
        "stars_world_5" to (R.string.achievement_stars_world_5 to R.string.achievement_stars_world_5_body),
        "complete_world_1" to (R.string.achievement_complete_world_1 to R.string.achievement_complete_world_1_body),
        "complete_world_2" to (R.string.achievement_complete_world_2 to R.string.achievement_complete_world_2_body),
        "complete_world_3" to (R.string.achievement_complete_world_3 to R.string.achievement_complete_world_3_body),
        "complete_world_4" to (R.string.achievement_complete_world_4 to R.string.achievement_complete_world_4_body),
        "complete_world_5" to (R.string.achievement_complete_world_5 to R.string.achievement_complete_world_5_body),
        "no_undo" to (R.string.achievement_no_undo to R.string.achievement_no_undo_body),
        "purist" to (R.string.achievement_purist to R.string.achievement_purist_body),
        "first_daily" to (R.string.achievement_first_daily to R.string.achievement_first_daily_body),
        "streak_7" to (R.string.achievement_streak_7 to R.string.achievement_streak_7_body),
        "streak_30" to (R.string.achievement_streak_30 to R.string.achievement_streak_30_body),
        "big_splash" to (R.string.achievement_big_splash to R.string.achievement_big_splash_body),
        "pit_filler" to (R.string.achievement_pit_filler to R.string.achievement_pit_filler_body),
        "pool_hopper" to (R.string.achievement_pool_hopper to R.string.achievement_pool_hopper_body),
        "last_second" to (R.string.achievement_last_second to R.string.achievement_last_second_body),
        "collector" to (R.string.achievement_collector to R.string.achievement_collector_body),
    )

    /** Title and description of an achievement by id (see core/data Achievement). */
    fun achievement(id: String): Pair<Int, Int> = achievements.getValue(id)
}
