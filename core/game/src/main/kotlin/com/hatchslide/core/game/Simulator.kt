package com.hatchslide.core.game

/**
 * Applies swipes to immutable [GameState]s and reports what happened as a [MoveResult].
 * Not thread-safe: use one instance per thread (the solver creates its own engine).
 */
class Simulator(val level: Level) {
    private val engine = Engine(level.board)
    private val sim = SimState.forBoard(level.board)

    fun initialState(): GameState = GameState.initial(level)

    fun apply(state: GameState, direction: Direction): MoveOutcome {
        val recorder = Recorder()
        if (state.status != GameStatus.PLAYING) {
            sim.load(state)
            recorder.begin(sim)
            return MoveOutcome(state, recorder.result(direction, noOp = true, status = state.status))
        }
        sim.load(state)
        val changed = engine.slide(sim, direction.ordinal, recorder)
        if (!changed) {
            return MoveOutcome(state, recorder.result(direction, noOp = true, status = state.status))
        }
        engine.advanceHazards(sim, recorder)
        val status = Status.toGameStatus(engine.evaluate(sim, level.gullMoves))
        return MoveOutcome(sim.toGameState(status), recorder.result(direction, noOp = false, status = status))
    }

    /** Plays a sequence of swipes from the initial state, stopping early when the level ends. */
    fun play(directions: List<Direction>, from: GameState = initialState()): List<MoveOutcome> {
        val outcomes = ArrayList<MoveOutcome>(directions.size)
        var state = from
        for (d in directions) {
            if (state.status != GameStatus.PLAYING) break
            val outcome = apply(state, d)
            outcomes += outcome
            state = outcome.state
        }
        return outcomes
    }
}

/**
 * One play-through of a level with unlimited undo. Undo restores the complete previous state,
 * including the gull counter, hazard phases and egg timers. No-op swipes never enter the history.
 */
class GameSession(
    val level: Level,
    initial: GameState = GameState.initial(level),
    history: List<GameState> = emptyList(),
) {
    private val simulator = Simulator(level)
    private val undoStack = ArrayDeque(history)

    var state: GameState = initial
        private set

    val history: List<GameState> get() = undoStack.toList()
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun swipe(direction: Direction): MoveOutcome {
        val outcome = simulator.apply(state, direction)
        if (!outcome.result.noOp) {
            undoStack.addLast(state)
            state = outcome.state
        }
        return outcome
    }

    /** Restores the state before the last counted move; returns it, or null if there is nothing to undo. */
    fun undo(): GameState? {
        val previous = undoStack.removeLastOrNull() ?: return null
        state = previous
        return previous
    }

    fun restart() {
        undoStack.clear()
        state = GameState.initial(level)
    }
}

/** Detects which mechanics a sequence of moves actually exercises (used by level validation). */
object MechanicUsage {
    fun used(level: Level, path: List<Direction>): Set<Mechanic> {
        val board = level.board
        val simulator = Simulator(level)
        val usedSet = mutableSetOf(Mechanic.BASICS)
        var state = simulator.initialState()
        for (d in path) {
            if (state.status != GameStatus.PLAYING) break
            // A hazard "matters" if some alternative move at this point would lose to it.
            for (alt in Direction.entries) {
                if (alt == d) continue
                when (simulator.apply(state, alt).state.status) {
                    GameStatus.LOST_PIT -> usedSet += Mechanic.PIT
                    GameStatus.LOST_GULL_SHADOW -> usedSet += Mechanic.GULL_SHADOW
                    else -> Unit
                }
            }
            val coconutIds = state.coconuts.map { it.id }.toSet()
            val outcome = simulator.apply(state, d)
            for (event in outcome.result.events) {
                when (event) {
                    is MoveEvent.Stopped -> {
                        val ahead = board.step(event.cell, event.direction)
                        when (event.cause) {
                            StopCause.BLOCKER -> when (if (ahead >= 0) board.tile(ahead) else null) {
                                TileType.ROCK -> usedSet += Mechanic.ROCK
                                TileType.SHELL -> usedSet += Mechanic.SHELL
                                else -> Unit
                            }

                            StopCause.WET_SAND -> usedSet += Mechanic.WET_SAND

                            StopCause.CRAB -> usedSet += Mechanic.CRAB

                            StopCause.PIECE -> {
                                // The blocker is whichever piece sat on the cell ahead at that tick.
                                val blocker = outcome.result.tracks.firstOrNull { track ->
                                    track.cells.getOrNull(minOf(event.tick, track.cells.size - 1)) == ahead
                                }
                                if (blocker != null && blocker.pieceId in coconutIds && event.pieceId !in coconutIds) {
                                    usedSet += Mechanic.COCONUT
                                }
                            }

                            else -> Unit
                        }
                    }

                    is MoveEvent.ShellCracked -> usedSet += Mechanic.CRACKED_SHELL

                    is MoveEvent.Redirected -> usedSet += Mechanic.CURRENT

                    is MoveEvent.PitFilled -> usedSet += setOf(Mechanic.PIT, Mechanic.COCONUT)

                    is MoveEvent.Teleported -> usedSet += Mechanic.TIDE_POOL

                    is MoveEvent.Hatched -> usedSet += Mechanic.LATE_EGG

                    is MoveEvent.CrabWaited -> usedSet += Mechanic.CRAB

                    else -> Unit
                }
            }
            state = outcome.state
        }
        return usedSet
    }
}
