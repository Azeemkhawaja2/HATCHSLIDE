package com.hatchslide.core.data

import kotlinx.serialization.Serializable

/** Best result on one level. A level is completed once it has at least one star. */
@Serializable
data class LevelRecord(val stars: Int = 0, val bestMoves: Int = 0, val wins: Int = 0)

/** Lifetime totals shown on the Achievements screen and used by achievement checks. */
@Serializable
data class Stats(
    val saved: Long = 0,
    val moves: Long = 0,
    val playSeconds: Long = 0,
    val wins: Int = 0,
    /** Wins on levels with par 5 or more without using Undo. */
    val noUndoWins: Int = 0,
    /** Wins without Undo or Hint. */
    val pureWins: Int = 0,
    val pitsFilled: Int = 0,
    val teleports: Int = 0,
    val hintsUsed: Int = 0,
    val mostSavedInOneMove: Int = 0,
    /** Wins on the very last move before the seagull. */
    val lastSecondWins: Int = 0,
    val dailiesCompleted: Int = 0,
)

/** Daily Tide history, by local epoch day. */
@Serializable
data class DailyRecord(
    val completedDays: List<Long> = emptyList(),
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val lastDay: Long = Long.MIN_VALUE,
)

/** An in-progress level, so leaving or killing the app resumes the board (the run's JSON is opaque here). */
@Serializable
data class RunSave(val levelId: String, val snapshot: String)

/** Everything the player has earned, stored as one JSON document in DataStore. */
@Serializable
data class Progress(
    val levels: Map<String, LevelRecord> = emptyMap(),
    val tipsSeen: Set<String> = emptySet(),
    /** Successful moves made while the first-run tutorial hand was showing (it stops after 3). */
    val tutorialMoves: Int = 0,
    /** Selected hatchling skin (species index). */
    val skin: Int = 0,
    /** Unlocked achievement ids with the time they were earned (epoch milliseconds). */
    val achievements: Map<String, Long> = emptyMap(),
    val stats: Stats = Stats(),
    val daily: DailyRecord = DailyRecord(),
    val run: RunSave? = null,
    /** Species whose unlock card has been shown. */
    val announcedSpecies: Set<Int> = setOf(0),
)

enum class GraphicsQuality { AUTO, LOW, MEDIUM, HIGH }

enum class FrameRateSetting { BATTERY, STANDARD, SMOOTH }

/** Player settings (spec section 5, Settings). */
@Serializable
data class Settings(
    val musicVolume: Float = 0.7f,
    val effectsVolume: Float = 0.9f,
    val haptics: Boolean = true,
    val quality: GraphicsQuality = GraphicsQuality.AUTO,
    val frameRate: FrameRateSetting = FrameRateSetting.STANDARD,
    val colorBlindTiles: Boolean = false,
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
    val arrowPad: Boolean = false,
    val leftHanded: Boolean = false,
    /** Tier ordinal (Low, Medium, High) the Auto setting stands for; -1 until the first-launch check. */
    val detectedTier: Int = -1,
)
