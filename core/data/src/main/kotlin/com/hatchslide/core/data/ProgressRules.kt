package com.hatchslide.core.data

/** The 24 local achievements (spec section 5). Titles and descriptions are string resources keyed by [id]. */
enum class Achievement(val id: String) {
    FIRST_SAVE("first_save"),
    SAVES_100("saves_100"),
    SAVES_1000("saves_1000"),
    FIRST_THREE_STARS("first_three_stars"),
    STARS_WORLD_1("stars_world_1"),
    STARS_WORLD_2("stars_world_2"),
    STARS_WORLD_3("stars_world_3"),
    STARS_WORLD_4("stars_world_4"),
    STARS_WORLD_5("stars_world_5"),
    COMPLETE_WORLD_1("complete_world_1"),
    COMPLETE_WORLD_2("complete_world_2"),
    COMPLETE_WORLD_3("complete_world_3"),
    COMPLETE_WORLD_4("complete_world_4"),
    COMPLETE_WORLD_5("complete_world_5"),
    NO_UNDO("no_undo"),
    PURIST("purist"),
    FIRST_DAILY("first_daily"),
    STREAK_7("streak_7"),
    STREAK_30("streak_30"),
    BIG_SPLASH("big_splash"),
    PIT_FILLER("pit_filler"),
    POOL_HOPPER("pool_hopper"),
    LAST_SECOND("last_second"),
    COLLECTOR("collector"),
}

/** What a single counted move did, for stats and achievements. */
data class MoveStats(val saved: Int, val pitsFilled: Int, val teleports: Int)

/** A finished level, as reported by the game. */
data class WinReport(
    val levelId: String,
    val world: Int,
    val par: Int,
    val movesUsed: Int,
    val movesLeft: Int,
    val stars: Int,
    val usedUndo: Boolean,
    val usedHint: Boolean,
)

/**
 * Progression rules (spec sections 4 and 5), pure functions over [Progress] so they are unit-testable:
 * level and world unlocks, star totals, species unlocks, the Daily Tide streak and achievements.
 */
object ProgressRules {
    const val WORLDS = 5
    const val LEVELS_PER_WORLD = 30
    const val STARS_PER_WORLD = LEVELS_PER_WORLD * 3

    /** The last five levels of a world need this many stars in that world. */
    const val GATE_FIRST_LEVEL = 26
    const val GATE_STARS = 45
    const val SPECIES = 7

    /** Daily Tide puzzles needed for the seventh species. */
    const val FLATBACK_DAILIES = 7

    fun levelId(world: Int, index: Int): String = "w${world}_l%02d".format(index)

    fun stars(p: Progress, id: String): Int = p.levels[id]?.stars ?: 0

    fun isCompleted(p: Progress, id: String): Boolean = stars(p, id) > 0

    fun worldStars(p: Progress, world: Int): Int = (1..LEVELS_PER_WORLD).sumOf { stars(p, levelId(world, it)) }

    fun totalStars(p: Progress): Int = (1..WORLDS).sumOf { worldStars(p, it) }

    fun completedLevels(p: Progress, world: Int): Int = (1..LEVELS_PER_WORLD).count { isCompleted(p, levelId(world, it)) }

    /** A world is complete once its last level is beaten. */
    fun isWorldComplete(p: Progress, world: Int): Boolean = isCompleted(p, levelId(world, LEVELS_PER_WORLD))

    fun isWorldUnlocked(p: Progress, world: Int): Boolean = world == 1 || (world in 2..WORLDS && isWorldComplete(p, world - 1))

    /** Why a level is locked, or null when it can be played. */
    enum class Lock { WORLD, PREVIOUS, STARS }

    fun lockOf(p: Progress, world: Int, index: Int): Lock? = when {
        !isWorldUnlocked(p, world) -> Lock.WORLD
        index > 1 && !isCompleted(p, levelId(world, index - 1)) -> Lock.PREVIOUS
        index >= GATE_FIRST_LEVEL && worldStars(p, world) < GATE_STARS -> Lock.STARS
        else -> null
    }

    fun isLevelUnlocked(p: Progress, world: Int, index: Int): Boolean = lockOf(p, world, index) == null

    /** Species unlocks: Green from the start, one per completed world, Flatback after seven Daily Tides. */
    fun isSpeciesUnlocked(p: Progress, species: Int): Boolean = when (species) {
        0 -> true
        in 1..WORLDS -> isWorldComplete(p, species)
        6 -> p.stats.dailiesCompleted >= FLATBACK_DAILIES
        else -> false
    }

    fun unlockedSpecies(p: Progress): List<Int> = (0 until SPECIES).filter { isSpeciesUnlocked(p, it) }

    /** Streak to show today: the stored streak, or 0 once a day has been missed. */
    fun currentStreak(p: Progress, today: Long): Int = if (p.daily.lastDay >= today - 1) p.daily.streak else 0

    fun recordMove(p: Progress, move: MoveStats): Progress = p.copy(
        stats = p.stats.copy(
            saved = p.stats.saved + move.saved,
            moves = p.stats.moves + 1,
            pitsFilled = p.stats.pitsFilled + move.pitsFilled,
            teleports = p.stats.teleports + move.teleports,
            mostSavedInOneMove = maxOf(p.stats.mostSavedInOneMove, move.saved),
        ),
    )

    fun recordHint(p: Progress): Progress = p.copy(stats = p.stats.copy(hintsUsed = p.stats.hintsUsed + 1))

    /** Records a win; [today] (epoch day) marks a Daily Tide completion and advances the streak. */
    fun recordWin(p: Progress, win: WinReport, today: Long? = null): Progress {
        val old = p.levels[win.levelId] ?: LevelRecord()
        val record = LevelRecord(
            stars = maxOf(old.stars, win.stars),
            bestMoves = if (old.bestMoves == 0) win.movesUsed else minOf(old.bestMoves, win.movesUsed),
            wins = old.wins + 1,
        )
        var stats = p.stats.copy(
            wins = p.stats.wins + 1,
            noUndoWins = p.stats.noUndoWins + if (!win.usedUndo && win.par >= 5) 1 else 0,
            pureWins = p.stats.pureWins + if (!win.usedUndo && !win.usedHint) 1 else 0,
            lastSecondWins = p.stats.lastSecondWins + if (win.movesLeft == 0) 1 else 0,
        )
        var daily = p.daily
        if (today != null && today !in daily.completedDays) {
            val streak = if (daily.lastDay == today - 1) daily.streak + 1 else 1
            daily = DailyRecord(
                completedDays = (daily.completedDays + today).sorted().takeLast(400),
                streak = streak,
                bestStreak = maxOf(daily.bestStreak, streak),
                lastDay = maxOf(daily.lastDay, today),
            )
            stats = stats.copy(dailiesCompleted = stats.dailiesCompleted + 1)
        }
        return p.copy(levels = p.levels + (win.levelId to record), stats = stats, daily = daily, run = null)
    }

    private fun allThreeStars(p: Progress, world: Int) = (1..LEVELS_PER_WORLD).all { stars(p, levelId(world, it)) == 3 }

    fun isEarned(p: Progress, a: Achievement): Boolean = when (a) {
        Achievement.FIRST_SAVE -> p.stats.saved >= 1
        Achievement.SAVES_100 -> p.stats.saved >= 100
        Achievement.SAVES_1000 -> p.stats.saved >= 1000
        Achievement.FIRST_THREE_STARS -> p.levels.values.any { it.stars == 3 }
        Achievement.STARS_WORLD_1 -> allThreeStars(p, 1)
        Achievement.STARS_WORLD_2 -> allThreeStars(p, 2)
        Achievement.STARS_WORLD_3 -> allThreeStars(p, 3)
        Achievement.STARS_WORLD_4 -> allThreeStars(p, 4)
        Achievement.STARS_WORLD_5 -> allThreeStars(p, 5)
        Achievement.COMPLETE_WORLD_1 -> isWorldComplete(p, 1)
        Achievement.COMPLETE_WORLD_2 -> isWorldComplete(p, 2)
        Achievement.COMPLETE_WORLD_3 -> isWorldComplete(p, 3)
        Achievement.COMPLETE_WORLD_4 -> isWorldComplete(p, 4)
        Achievement.COMPLETE_WORLD_5 -> isWorldComplete(p, 5)
        Achievement.NO_UNDO -> p.stats.noUndoWins >= 1
        Achievement.PURIST -> p.stats.pureWins >= 10
        Achievement.FIRST_DAILY -> p.stats.dailiesCompleted >= 1
        Achievement.STREAK_7 -> p.daily.bestStreak >= 7
        Achievement.STREAK_30 -> p.daily.bestStreak >= 30
        Achievement.BIG_SPLASH -> p.stats.mostSavedInOneMove >= 4
        Achievement.PIT_FILLER -> p.stats.pitsFilled >= 10
        Achievement.POOL_HOPPER -> p.stats.teleports >= 25
        Achievement.LAST_SECOND -> p.stats.lastSecondWins >= 1
        Achievement.COLLECTOR -> (0 until SPECIES).all { isSpeciesUnlocked(p, it) }
    }

    /** Stamps achievements that [p] now satisfies; returns the updated progress and the new ones. */
    fun awardAchievements(p: Progress, now: Long): Pair<Progress, List<Achievement>> {
        val fresh = Achievement.entries.filter { it.id !in p.achievements && isEarned(p, it) }
        if (fresh.isEmpty()) return p to emptyList()
        return p.copy(achievements = p.achievements + fresh.associate { it.id to now }) to fresh
    }

    /** Species unlocked but not yet announced; marks them announced. */
    fun announceSpecies(p: Progress): Pair<Progress, List<Int>> {
        val fresh = unlockedSpecies(p).filter { it !in p.announcedSpecies }
        if (fresh.isEmpty()) return p to emptyList()
        return p.copy(announcedSpecies = p.announcedSpecies + fresh) to fresh
    }
}
