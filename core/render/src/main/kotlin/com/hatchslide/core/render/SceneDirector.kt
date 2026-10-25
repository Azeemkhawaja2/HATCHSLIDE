package com.hatchslide.core.render

import android.content.Context
import com.hatchslide.core.render.board.BoardStage

/**
 * Activity-scoped facade over the renderer. Screens ask the director to show a stage; the director
 * owns every stage so they are destroyed together with the engine.
 */
class SceneDirector(context: Context) {
    val engine = RenderEngine(context)
    val board = BoardStage(engine)
    private val stages = mutableListOf<Stage>(board)

    /** Registers an additional stage (title, map, journal) so it is destroyed with the engine. */
    fun <T : Stage> register(stage: T): T {
        stages += stage
        return stage
    }

    fun show(stage: Stage) = engine.show(stage)

    fun showBoard() = engine.show(board)

    fun resume() = engine.resume()

    fun pause() = engine.pause()

    fun destroy() = engine.destroy(stages)
}
