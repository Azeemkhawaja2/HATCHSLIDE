package com.hatchslide.core.game

import kotlinx.serialization.Serializable

@Serializable
data class Piece(val id: Int, val kind: PieceKind, val cell: Int)

@Serializable
enum class GameStatus {
    PLAYING,
    WON,
    LOST_OUT_OF_MOVES,
    LOST_PIT,
    LOST_GULL_SHADOW,
    ;

    val isLost: Boolean get() = this == LOST_OUT_OF_MOVES || this == LOST_PIT || this == LOST_GULL_SHADOW
}

/**
 * Complete, immutable game state for one level. Restoring an earlier [GameState] (undo) restores
 * everything: pieces, broken shells, filled pits, hazard phases, egg timers and the move count.
 */
@Serializable
data class GameState(
    /** Pieces on the board (hatchlings and coconuts), ordered by id. */
    val pieces: List<Piece>,
    /** Bit i set: the i-th cracked shell (row-major order) has broken into sand. */
    val brokenShells: Long,
    /** Bit i set: the i-th sand pit (row-major order) has been filled by a coconut. */
    val filledPits: Long,
    val crabPhases: List<Int>,
    /** Moves until each late egg hatches; 0 once hatched. */
    val eggTimers: List<Int>,
    val shadowPhases: List<Int>,
    val movesUsed: Int,
    val savedCount: Int,
    val status: GameStatus,
    val nextPieceId: Int,
) {
    val hatchlings: List<Piece> get() = pieces.filter { it.kind == PieceKind.HATCHLING }
    val coconuts: List<Piece> get() = pieces.filter { it.kind == PieceKind.COCONUT }

    fun movesLeft(level: Level): Int = level.gullMoves - movesUsed

    fun isShellBroken(board: Board, cell: Int): Boolean {
        val bit = board.crackedIndex[cell]
        return bit >= 0 && (brokenShells ushr bit) and 1L == 1L
    }

    fun isPitFilled(board: Board, cell: Int): Boolean {
        val bit = board.pitIndex[cell]
        return bit >= 0 && (filledPits ushr bit) and 1L == 1L
    }

    /** The tile as it currently plays: broken shells and filled pits read as sand. */
    fun effectiveTile(board: Board, cell: Int): TileType {
        val tile = board.tile(cell)
        return when {
            tile == TileType.CRACKED_SHELL && isShellBroken(board, cell) -> TileType.SAND
            tile == TileType.PIT && isPitFilled(board, cell) -> TileType.SAND
            else -> tile
        }
    }

    fun crabCells(board: Board): List<Int> = crabPhases.mapIndexed { i, phase -> board.crabCell(i, phase) }

    fun shadowCells(board: Board): List<Int> = shadowPhases.mapIndexed { i, phase -> board.shadowCell(i, phase) }

    /** Cells of late eggs that have not hatched yet. */
    fun eggCells(board: Board): List<Int> = eggTimers.indices.filter { eggTimers[it] > 0 }.map { board.eggCells[it] }

    companion object {
        fun initial(level: Level): GameState {
            val board = level.board
            var id = 0
            val pieces = buildList {
                board.hatchlingStarts.sorted().forEach { add(Piece(id++, PieceKind.HATCHLING, it)) }
                board.coconutStarts.sorted().forEach { add(Piece(id++, PieceKind.COCONUT, it)) }
            }
            return GameState(
                pieces = pieces,
                brokenShells = 0L,
                filledPits = 0L,
                crabPhases = List(board.crabPaths.size) { 0 },
                eggTimers = board.eggTimers.toList(),
                shadowPhases = List(board.shadowPaths.size) { 0 },
                movesUsed = 0,
                savedCount = 0,
                status = GameStatus.PLAYING,
                nextPieceId = id,
            )
        }
    }
}

/** Why a sliding piece stopped. */
@Serializable
enum class StopCause { EDGE, BLOCKER, PIECE, CRAB, EGG, WET_SAND, CONFLICT, LOOP, TICK_CAP }

/** Typed events produced by one move; the renderer and audio react to these and never change state. */
sealed interface MoveEvent {
    /** Tick within the slide (0-based); hazard events use the tick after the slide ends. */
    val tick: Int

    data class Stopped(override val tick: Int, val pieceId: Int, val cell: Int, val cause: StopCause, val direction: Direction) : MoveEvent

    /** [order] counts saves within this move from 0, so the save chime can rise in pitch. */
    data class Saved(override val tick: Int, val pieceId: Int, val cell: Int, val order: Int) : MoveEvent

    data class CoconutSank(override val tick: Int, val pieceId: Int, val cell: Int) : MoveEvent

    data class Redirected(override val tick: Int, val pieceId: Int, val cell: Int, val from: Direction, val to: Direction) : MoveEvent

    data class Teleported(override val tick: Int, val pieceId: Int, val fromCell: Int, val toCell: Int) : MoveEvent

    data class FellInPit(override val tick: Int, val pieceId: Int, val cell: Int) : MoveEvent

    data class PitFilled(override val tick: Int, val pieceId: Int, val cell: Int) : MoveEvent

    data class ShellCracked(override val tick: Int, val cell: Int, val byPieceId: Int) : MoveEvent

    data class Hatched(override val tick: Int, val pieceId: Int, val cell: Int, val egg: Int) : MoveEvent

    data class EggWobble(override val tick: Int, val cell: Int, val egg: Int) : MoveEvent

    data class CrabStepped(override val tick: Int, val crab: Int, val from: Int, val to: Int) : MoveEvent

    data class CrabWaited(override val tick: Int, val crab: Int, val cell: Int) : MoveEvent

    data class GullShadowMoved(override val tick: Int, val shadow: Int, val from: Int, val to: Int) : MoveEvent
}

/**
 * Cells a piece occupied during a slide: `cells[0]` is the start cell and `cells[t + 1]` the cell
 * after tick t. A track ends early when the piece leaves the board (saved, sank or fell).
 */
data class PieceTrack(val pieceId: Int, val kind: PieceKind, val cells: List<Int>) {
    val moved: Boolean get() = cells.any { it != cells[0] }
}

data class MoveResult(
    val direction: Direction,
    /** True when nothing moved and nothing changed: the move does not count. */
    val noOp: Boolean,
    val ticks: Int,
    val tracks: List<PieceTrack>,
    val events: List<MoveEvent>,
    val statusAfter: GameStatus,
) {
    val savedThisMove: Int get() = events.count { it is MoveEvent.Saved }

    inline fun <reified T : MoveEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
}

data class MoveOutcome(val state: GameState, val result: MoveResult)
