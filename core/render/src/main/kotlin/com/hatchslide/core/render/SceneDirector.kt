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

    /** Called after the drawn stage changes, so the UI can reveal the new one through a curtain. */
    var onStageChanged: (() -> Unit)? = null

    fun show(stage: Stage) {
        if (engine.stage === stage) return
        engine.show(stage)
        onStageChanged?.invoke()
    }

    fun showBoard() = show(board)

    /** Applies quality, motion and colour-blind preferences to every stage. */
    fun applySettings(tier: QualityTier, frameRate: FrameRateMode, reducedMotion: Boolean, colorBlind: Boolean) {
        engine.setQuality(tier)
        engine.frameRateMode = frameRate
        board.reducedMotion = reducedMotion
        board.tier = tier
        board.colorBlind = colorBlind
        // Reduced motion cuts particles by 80% (spec section 5).
        engine.particles.pool.density = if (reducedMotion) 0.2f else 1f
        beach.reducedMotion = reducedMotion
        map.tier = tier
    }

    fun resume() = engine.resume()

    fun pause() = engine.pause()

    fun destroy() = engine.destroy(stages)
}
