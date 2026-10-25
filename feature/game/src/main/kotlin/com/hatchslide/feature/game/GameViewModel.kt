package com.hatchslide.feature.game

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.Hint
import com.hatchslide.core.game.Hints
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.MoveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hint state shown in the HUD (spec section 2: free, unlimited, never blocks stars). */
sealed interface HintUi {
    data object None : HintUi

    data object Thinking : HintUi

    data class Arrow(val direction: Direction) : HintUi

    /** The current state cannot be won in the moves left; the UI highlights Undo. */
    data object Unsolvable : HintUi
}

data class GameUiState(
    val level: Level,
    val state: GameState,
    val canUndo: Boolean,
    val hint: HintUi,
    val stars: Int,
    /** Description of the last counted move for TalkBack ("Swiped left. 2 saved. 3 moves left."). */
    val lastMove: MoveSummary?,
    /** True once the final move has finished animating on a won or lost level. */
    val ended: Boolean = false,
    /** Direction the tutorial hand demonstrates (levels 1-3), or null. */
    val tutorialDirection: Direction? = null,
) {
    val movesLeft: Int get() = level.gullMoves - state.movesUsed
    val totalHatchlings: Int get() = level.board.totalHatchlings
    val saved: Int get() = state.savedCount
}

data class MoveSummary(val direction: Direction, val savedThisMove: Int, val savedTotal: Int, val movesLeft: Int, val noOp: Boolean)

/** Instructions for the board; the board animates and reports back through [GameViewModel.animationFinished]. */
sealed interface BoardCommand {
    data class Play(val outcome: MoveOutcome) : BoardCommand

    data class Snap(val state: GameState) : BoardCommand

    data class ShowHint(val direction: Direction?) : BoardCommand

    data object Celebrate : BoardCommand
}

/** Callbacks into progress tracking; implemented by the app's data layer. */
interface GameProgressSink {
    fun onLevelWon(level: Level, movesUsed: Int, stars: Int, usedUndo: Boolean, savedHatchlings: Int)

    fun onLevelLost(level: Level)

    fun onMove(level: Level, savedThisMove: Int)
}

class GameViewModel(
    private val level: Level,
    private val savedState: SavedStateHandle,
    private val progress: GameProgressSink?,
) : ViewModel() {
    private val controller = GameController(
        level,
        savedState.get<String>(KEY_RUN)?.let(GameController::decode)?.takeIf {
            it.levelId ==
                level.id
        },
    )
    private val _ui = MutableStateFlow(
        GameUiState(
            level,
            controller.state,
            controller.canUndo,
            HintUi.None,
            0,
            null,
            ended = controller.state.status != GameStatus.PLAYING,
        ),
    )
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()
    private val _commands = MutableSharedFlow<BoardCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<BoardCommand> = _commands.asSharedFlow()
    private var hintJob: Job? = null
    private var reported = controller.state.status != GameStatus.PLAYING

    /** The state the board should show when it (re)attaches. */
    val boardState: GameState get() = controller.state

    fun swipe(direction: Direction) {
        val outcome = controller.swipe(direction) ?: return
        play(outcome)
    }

    private fun play(outcome: MoveOutcome) {
        clearHint()
        if (!outcome.result.noOp) {
            persist()
            progress?.onMove(level, outcome.result.savedThisMove)
        }
        val summary = MoveSummary(
            outcome.result.direction,
            outcome.result.savedThisMove,
            outcome.state.savedCount,
            level.gullMoves - outcome.state.movesUsed,
            outcome.result.noOp,
        )
        publish(summary)
        _commands.tryEmit(BoardCommand.Play(outcome))
    }

    fun animationFinished() {
        val next = controller.animationFinished()
        if (next != null) {
            play(next)
            return
        }
        val state = controller.state
        publish(_ui.value.lastMove)
        if (state.status != GameStatus.PLAYING) _ui.value = _ui.value.copy(ended = true)
        if (!reported && state.status != GameStatus.PLAYING) {
            reported = true
            if (state.status == GameStatus.WON) {
                progress?.onLevelWon(level, state.movesUsed, controller.stars(), controller.undosUsed > 0, state.savedCount)
                _commands.tryEmit(BoardCommand.Celebrate)
            } else {
                progress?.onLevelLost(level)
            }
        }
    }

    fun undo() {
        val state = controller.undo() ?: return
        reported = false
        clearHint()
        persist()
        publish(null)
        _commands.tryEmit(BoardCommand.Snap(state))
    }

    fun restart() {
        val state = controller.restart()
        reported = false
        clearHint()
        persist()
        publish(null)
        _commands.tryEmit(BoardCommand.Snap(state))
    }

    fun hint() {
        if (controller.state.status != GameStatus.PLAYING || hintJob?.isActive == true) return
        controller.noteHint()
        val state = controller.state
        _ui.value = _ui.value.copy(hint = HintUi.Thinking)
        hintJob = viewModelScope.launch {
            val hint = withContext(Dispatchers.Default) { Hints.next(level, state) }
            if (controller.state != state) return@launch
            val ui = when (hint) {
                is Hint.Move -> HintUi.Arrow(hint.direction)
                Hint.Unsolvable -> HintUi.Unsolvable
                Hint.Unknown -> HintUi.None
            }
            _ui.value = _ui.value.copy(hint = ui)
            _commands.tryEmit(BoardCommand.ShowHint((ui as? HintUi.Arrow)?.direction))
        }
    }

    private fun clearHint() {
        hintJob?.cancel()
        if (_ui.value.hint != HintUi.None) {
            _ui.value = _ui.value.copy(hint = HintUi.None)
            _commands.tryEmit(BoardCommand.ShowHint(null))
        }
    }

    private fun publish(lastMove: MoveSummary?) {
        val state = controller.state
        _ui.value = _ui.value.copy(
            state = state,
            canUndo = controller.canUndo,
            stars = if (state.status == GameStatus.WON) controller.stars() else 0,
            lastMove = lastMove,
            ended = _ui.value.ended && state.status != GameStatus.PLAYING,
        )
        refreshTutorial()
    }

    /** Levels 1-3 show an animated hand until the player has made three successful moves. */
    var tutorialActive: Boolean = false
        set(value) {
            field = value
            refreshTutorial()
        }

    private var tutorialJob: Job? = null

    private fun refreshTutorial() {
        tutorialJob?.cancel()
        val state = controller.state
        if (!tutorialActive || state.status != GameStatus.PLAYING) {
            if (_ui.value.tutorialDirection != null) _ui.value = _ui.value.copy(tutorialDirection = null)
            return
        }
        tutorialJob = viewModelScope.launch {
            val hint = withContext(Dispatchers.Default) { Hints.next(level, state) }
            _ui.value = _ui.value.copy(tutorialDirection = (hint as? Hint.Move)?.direction)
        }
    }

    private fun persist() {
        savedState[KEY_RUN] = GameController.encode(controller.snapshot())
    }

    /** The serialized run, also written to DataStore by the screen so process death resumes the board. */
    fun snapshotJson(): String = GameController.encode(controller.snapshot())

    companion object {
        const val KEY_RUN = "run"
    }
}
