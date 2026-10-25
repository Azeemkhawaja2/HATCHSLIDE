package com.hatchslide.core.data

import com.hatchslide.core.data.ProgressRules.levelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRulesTest {
    private fun win(
        world: Int,
        index: Int,
        stars: Int = 3,
        moves: Int = 5,
        par: Int = 5,
        left: Int = 2,
        undo: Boolean = false,
        hint: Boolean = false,
    ) = WinReport(levelId(world, index), world, par, moves, left, stars, undo, hint)

    private fun completeWorld(p: Progress, world: Int, stars: Int = 3): Progress =
        (1..30).fold(p) { acc, i -> ProgressRules.recordWin(acc, win(world, i, stars)) }

    @Test
    fun levelsUnlockInOrderAndTheLastFiveNeed45Stars() {
        var p = Progress()
        assertTrue(ProgressRules.isLevelUnlocked(p, 1, 1))
        assertEquals(ProgressRules.Lock.PREVIOUS, ProgressRules.lockOf(p, 1, 2))
        for (i in 1..25) p = ProgressRules.recordWin(p, win(1, i, stars = 1))
        assertEquals(25, ProgressRules.worldStars(p, 1))
        assertEquals(ProgressRules.Lock.STARS, ProgressRules.lockOf(p, 1, 26))
        // Replaying for three stars opens the gate without a hard wall.
        for (i in 1..10) p = ProgressRules.recordWin(p, win(1, i, stars = 3))
        assertEquals(45, ProgressRules.worldStars(p, 1))
        assertNull(ProgressRules.lockOf(p, 1, 26))
    }

    @Test
    fun nextWorldOpensWhenTheLastLevelIsBeaten() {
        var p = Progress()
        assertEquals(ProgressRules.Lock.WORLD, ProgressRules.lockOf(p, 2, 1))
        p = completeWorld(p, 1, stars = 2)
        assertTrue(ProgressRules.isWorldComplete(p, 1))
        assertTrue(ProgressRules.isLevelUnlocked(p, 2, 1))
        assertFalse(ProgressRules.isWorldUnlocked(p, 3))
    }

    @Test
    fun bestResultIsKept() {
        var p = ProgressRules.recordWin(Progress(), win(1, 1, stars = 3, moves = 4))
        p = ProgressRules.recordWin(p, win(1, 1, stars = 1, moves = 9))
        val record = p.levels.getValue(levelId(1, 1))
        assertEquals(3, record.stars)
        assertEquals(4, record.bestMoves)
        assertEquals(2, record.wins)
    }

    @Test
    fun dailyStreakCountsConsecutiveDaysAndResets() {
        var p = Progress()
        p = ProgressRules.recordWin(p, win(0, 1), today = 100)
        p = ProgressRules.recordWin(p, win(0, 2), today = 101)
        p = ProgressRules.recordWin(p, win(0, 2), today = 101) // same day twice counts once
        assertEquals(2, p.daily.streak)
        assertEquals(2, p.stats.dailiesCompleted)
        assertEquals(2, ProgressRules.currentStreak(p, 102))
        assertEquals(0, ProgressRules.currentStreak(p, 103))
        p = ProgressRules.recordWin(p, win(0, 5), today = 105)
        assertEquals(1, p.daily.streak)
        assertEquals(2, p.daily.bestStreak)
    }

    @Test
    fun speciesUnlockWithWorldsAndDailies() {
        var p = Progress()
        assertEquals(listOf(0), ProgressRules.unlockedSpecies(p))
        p = completeWorld(p, 1)
        assertTrue(ProgressRules.isSpeciesUnlocked(p, 1))
        for (day in 0L until 7L) p = ProgressRules.recordWin(p, win(0, day.toInt() + 1), today = 1000 + day * 3)
        assertTrue(ProgressRules.isSpeciesUnlocked(p, 6))
        val (announced, fresh) = ProgressRules.announceSpecies(p)
        assertEquals(listOf(1, 6), fresh)
        assertTrue(ProgressRules.announceSpecies(announced).second.isEmpty())
    }

    @Test
    fun achievementsAreAwardedOnce() {
        var p = ProgressRules.recordMove(Progress(), MoveStats(saved = 4, pitsFilled = 0, teleports = 0))
        p = ProgressRules.recordWin(p, win(1, 1, stars = 3, left = 0, par = 6))
        val (awarded, fresh) = ProgressRules.awardAchievements(p, now = 42)
        assertTrue(Achievement.FIRST_SAVE in fresh)
        assertTrue(Achievement.BIG_SPLASH in fresh)
        assertTrue(Achievement.FIRST_THREE_STARS in fresh)
        assertTrue(Achievement.LAST_SECOND in fresh)
        assertTrue(Achievement.NO_UNDO in fresh)
        assertFalse(Achievement.SAVES_100 in fresh)
        assertEquals(42L, awarded.achievements[Achievement.FIRST_SAVE.id])
        assertTrue(ProgressRules.awardAchievements(awarded, now = 43).second.isEmpty())
    }

    @Test
    fun everyAchievementIsReachable() {
        var p = Progress()
        for (w in 1..5) p = completeWorld(p, w)
        repeat(1000) { p = ProgressRules.recordMove(p, MoveStats(saved = 1, pitsFilled = 1, teleports = 1)) }
        p = ProgressRules.recordMove(p, MoveStats(saved = 5, pitsFilled = 0, teleports = 0))
        for (day in 0L until 30L) p = ProgressRules.recordWin(p, win(0, 1, left = 0, par = 6), today = day)
        val earned = Achievement.entries.filter { ProgressRules.isEarned(p, it) }
        assertEquals(Achievement.entries.toList(), earned)
        assertEquals(24, Achievement.entries.size)
    }
}
