package com.hatchslide.app

import kotlinx.serialization.Serializable

@Serializable
object TitleRoute

@Serializable
object MenuRoute

/** World map, centred on [world] (0 = the furthest unlocked island). */
@Serializable
data class MapRoute(val world: Int = 0)

@Serializable
data class LevelsRoute(val world: Int)

/** Gameplay; [dailyDay] is the epoch day of a Daily Tide puzzle, or [NO_DAY]. */
@Serializable
data class GameRoute(val levelId: String, val dailyDay: Long = NO_DAY)

@Serializable
object DailyRoute

@Serializable
object JournalRoute

@Serializable
object AchievementsRoute

@Serializable
object SettingsRoute

@Serializable
object CreditsRoute

@Serializable
object PrivacyRoute

const val NO_DAY = Long.MIN_VALUE
