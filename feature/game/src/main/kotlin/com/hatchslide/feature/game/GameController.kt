package com.hatchslide.feature.game

import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameSession
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.MoveOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persisted form of an in-progress level: the current state and the full undo stack. */
@Serializable
data class SavedRun(val levelId: String, val state: GameState, val history: List<GameState>, val hintsUsed: Int = 0, val undosUsed: Int = 0)

/**
 * Game rules glue without Android dependencies: unlimited undo, restart, and the one-swipe input
 * buffer (spec section 2). A swipe applies to the game state at once; while the board animates it,
 * at most one further swipe is buffered and runs when the animation ends.
 */
class GameController(val level: Level, restored: SavedRun? = null) {
    private val session = GameSession(
        level,
        restored?.state ?: GameState.initial(level),
        restored?.history ?: emptyList(),
    )
    private var animating = false
    private var buffered: Direction? = null

    var hintsUsed: Int = restored?.hintsUsed ?: 0
        private set
    var undosUsed: Int = restored?.undosUsed ?: 0
        private set

    val state: GameState get() = session.state
    val canUndo: Boolean get() = session.canUndo
    val isAnimating: Boolean get() = animating

    /**
     * Handles a swipe. Returns the outcome to animate, or null if it was buffered or ignored.
     * No-op outcomes are returned too (for the bump), but never start an animation lock.
     */
    fun swipe(direction: Direction): MoveOutcome? {
        if (session.state.status != GameStatus.PLAYING) return null
        if (animating) {
            if (buffered == null) buffered = direction
            return null
        }
        val outcome = session.swipe(direction)
        if (!outcome.result.noOp) animating = true
        return outcome
    }

    /** Called when the board finishes animating; returns the buffered swipe's outcome, if any. */
    fun animationFinished(): MoveOutcome? {
        animating = false
        val next = buffered ?: return null
        buffered = null
        return swipe(next)
    }

    fun undo(): GameState? {
        buffered = null
        animating = false
        return session.undo()?.also { undosUsed++ }
    }

    fun restart(): GameState {
        buffered = null
        animating = false
        session.restart()
        return session.state
    }

    fun noteHint() {
        hintsUsed++
    }

    fun snapshot(): SavedRun = SavedRun(level.id, session.state, session.history, hintsUsed, undosUsed)

    /** Stars for a won level (spec section 2): 3 at par or better, 2 within par + 2, otherwise 1. */
    fun stars(): Int = starsFor(session.state.movesUsed, level.par)

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun starsFor(movesUsed: Int, par: Int): Int = when {
            movesUsed <= par -> 3
            movesUsed <= par + 2 -> 2
            else -> 1
        }

        fun encode(run: SavedRun): String = json.encodeToString(SavedRun.serializer(), run)

        fun decode(text: String): SavedRun? = runCatching { json.decodeFromString(SavedRun.serializer(), text) }.getOrNull()
    }
}
