package com.hatchslide.app

import android.content.Context
import com.hatchslide.core.data.FrameRateSetting
import com.hatchslide.core.data.GraphicsQuality
import com.hatchslide.core.data.LevelRepository
import com.hatchslide.core.data.MoveStats
import com.hatchslide.core.data.PlayerRepository
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.data.WinOutcome
import com.hatchslide.core.data.WinReport
import com.hatchslide.core.designsystem.GameText
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.game.MoveResult
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.FrameRateMode
import com.hatchslide.core.render.QualityTier
import com.hatchslide.feature.game.GameProgressSink
import com.hatchslide.feature.game.UnlockCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Reports gameplay to the player's saved data and turns wins into unlock cards. */
class ProgressSink(
    private val context: Context,
    private val player: PlayerRepository,
    private val scope: CoroutineScope,
    private val dailyDay: Long?,
    private val onUnlocks: (List<UnlockCard>) -> Unit,
) : GameProgressSink {
    override fun onLevelWon(level: Level, movesUsed: Int, movesLeft: Int, stars: Int, usedUndo: Boolean, usedHint: Boolean) {
        val report = WinReport(level.id, level.world, level.par, movesUsed, movesLeft, stars, usedUndo, usedHint)
        scope.launch {
            val outcome = player.recordWin(report, dailyDay, System.currentTimeMillis())
            onUnlocks(cards(outcome))
        }
    }

    override fun onLevelLost(level: Level) = player.clearRun(level.id)

    override fun onMove(level: Level, result: MoveResult) {
        player.recordMove(
            MoveStats(
                saved = result.savedThisMove,
                pitsFilled = result.events.count { it is MoveEvent.PitFilled },
                teleports = result.events.count { it is MoveEvent.Teleported },
            ),
        )
        player.awardAchievements(System.currentTimeMillis())
    }

    override fun onHint(level: Level) = player.recordHint()

    override fun onRunSaved(level: Level, snapshot: String) = player.saveRun(level.id, snapshot)

    private fun cards(outcome: WinOutcome): List<UnlockCard> = buildList {
        for (world in listOfNotNull(outcome.worldUnlocked)) {
            add(
                UnlockCard(
                    context.getString(R.string.unlock_world_title, context.getString(GameText.worldName(world))),
                    context.getString(R.string.unlock_world_body),
                    HatchIcons.Map,
                    UnlockCard.Kind.WORLD,
                ),
            )
        }
        for (species in outcome.species) {
            add(
                UnlockCard(
                    context.getString(R.string.unlock_species_title, context.getString(GameText.speciesName(species))),
                    context.getString(R.string.unlock_species_body),
                    HatchIcons.Journal,
                    UnlockCard.Kind.SPECIES,
                ),
            )
        }
        for (achievement in outcome.achievements) {
            val (title, body) = GameText.achievement(achievement.id)
            add(
                UnlockCard(
                    context.getString(R.string.unlock_achievement_title, context.getString(title)),
                    context.getString(body),
                    HatchIcons.Trophy,
                    UnlockCard.Kind.ACHIEVEMENT,
                ),
            )
        }
    }
}

/** World whose look a level uses: its own world, or for a Daily Tide the world of its newest mechanic. */
fun themeWorld(level: Level): Int {
    if (level.world in 1..ProgressRules.WORLDS) return level.world
    val board = level.board
    val tiles = (0 until board.cellCount).map { board.tile(it) }.toSet()
    return when {
        board.shadowPaths.isNotEmpty() || TileType.TIDE_POOL in tiles -> 5
        board.crabPaths.isNotEmpty() || board.eggCells.isNotEmpty() -> 4
        board.coconutStarts.isNotEmpty() || TileType.PIT in tiles || tiles.any { it.currentDirection != null } -> 3
        TileType.WET_SAND in tiles || TileType.CRACKED_SHELL in tiles -> 2
        else -> 1
    }
}

/** The next playable level after [level] in its world, or null. */
fun nextLevelId(level: Level, progress: com.hatchslide.core.data.Progress): String? {
    if (level.world !in 1..ProgressRules.WORLDS || level.index >= ProgressRules.LEVELS_PER_WORLD) return null
    return if (ProgressRules.isLevelUnlocked(
            progress,
            level.world,
            level.index + 1,
        )
    ) {
        ProgressRules.levelId(level.world, level.index + 1)
    } else {
        null
    }
}

fun qualityTier(quality: GraphicsQuality, auto: QualityTier): QualityTier = when (quality) {
    GraphicsQuality.AUTO -> auto
    GraphicsQuality.LOW -> QualityTier.LOW
    GraphicsQuality.MEDIUM -> QualityTier.MEDIUM
    GraphicsQuality.HIGH -> QualityTier.HIGH
}

fun frameRateMode(setting: FrameRateSetting): FrameRateMode = when (setting) {
    FrameRateSetting.BATTERY -> FrameRateMode.BATTERY
    FrameRateSetting.STANDARD -> FrameRateMode.STANDARD
    FrameRateSetting.SMOOTH -> FrameRateMode.SMOOTH
}

/** The level a [LevelRepository] id maps to for the Daily Tide of an epoch day. */
suspend fun LevelRepository.dailyForEpochDay(epochDay: Long): Level = dailyFor(java.time.LocalDate.ofEpochDay(epochDay).dayOfYear)
