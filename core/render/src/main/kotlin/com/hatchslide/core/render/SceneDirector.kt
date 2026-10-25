package com.hatchslide.core.render

import android.content.Context
import com.hatchslide.core.render.board.BoardStage
import com.hatchslide.core.render.stages.ArchipelagoStage
import com.hatchslide.core.render.stages.BeachStage

/**
 * Activity-scoped facade over the renderer. Screens ask the director to show a stage; the director
 * owns every stage so they are destroyed together with the engine.
 */
class SceneDirector(context: Context) {
    val engine = RenderEngine(context)

    /** Title, main menu and menu screens, including the Turtle Journal close-up. */
    val beach = BeachStage(engine)

    /** World map and level select. */
    val map = ArchipelagoStage(engine)
    val board = BoardStage(engine)
    private val stages = listOf<Stage>(beach, map, board)

    fun show(stage: Stage) = engine.show(stage)

    fun showBoard() = engine.show(board)

    /** Applies quality and motion preferences to every stage. */
    fun applySettings(tier: QualityTier, frameRate: FrameRateMode, reducedMotion: Boolean) {
        engine.setQuality(tier)
        engine.frameRateMode = frameRate
        board.reducedMotion = reducedMotion
        board.tier = tier
        beach.reducedMotion = reducedMotion
        map.tier = tier
    }

    fun resume() = engine.resume()

    fun pause() = engine.pause()

    fun destroy() = engine.destroy(stages)
}
