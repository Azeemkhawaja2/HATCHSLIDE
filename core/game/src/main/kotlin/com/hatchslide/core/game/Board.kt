package com.hatchslide.core.game

/** Swipe directions. Screen-up is always board-up (row 0 is the top row). */
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0),
    ;

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }

    companion object {
        internal val ALL = entries.toTypedArray()
    }
}

/** Fixed cell types (spec section 3). Mobile pieces, crabs, eggs and gull shadows live on top of them. */
enum class TileType(val code: Byte) {
    SAND(0),
    ROCK(1),
    SHELL(2),
    OCEAN(3),
    WET_SAND(4),
    CRACKED_SHELL(5),
    CURRENT_UP(6),
    CURRENT_DOWN(7),
    CURRENT_LEFT(8),
    CURRENT_RIGHT(9),
    PIT(10),
    TIDE_POOL(11),
    ;

    val currentDirection: Direction?
        get() = when (this) {
            CURRENT_UP -> Direction.UP
            CURRENT_DOWN -> Direction.DOWN
            CURRENT_LEFT -> Direction.LEFT
            CURRENT_RIGHT -> Direction.RIGHT
            else -> null
        }

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Byte): TileType = byCode.getValue(code)

        fun current(direction: Direction): TileType = when (direction) {
            Direction.UP -> CURRENT_UP
            Direction.DOWN -> CURRENT_DOWN
            Direction.LEFT -> CURRENT_LEFT
            Direction.RIGHT -> CURRENT_RIGHT
        }
    }
}

enum class PieceKind { HATCHLING, COCONUT }

/** A cell position. Stored as a row-major index `y * width + x` inside the engine. */
data class Cell(val x: Int, val y: Int)

/**
 * The static part of a level: tiles and the scripted hazard definitions. Immutable.
 *
 * Cells are addressed by row-major index (`y * width + x`); cell 0 is the top-left corner.
 */
class Board internal constructor(
    val width: Int,
    val height: Int,
    tiles: ByteArray,
    poolPairs: List<IntArray>,
    /** Crab patrol paths (ping-pong); element 0 is the start cell. */
    val crabPaths: List<IntArray>,
    /** Gull shadow loops; element 0 is the start cell. */
    val shadowPaths: List<IntArray>,
    val eggCells: IntArray,
    val eggTimers: IntArray,
    val hatchlingStarts: IntArray,
    val coconutStarts: IntArray,
) {
    val cellCount = width * height
    internal val tiles: ByteArray = tiles.copyOf()

    /** For a tide-pool cell, the index of its twin; -1 elsewhere. */
    internal val poolTwin = IntArray(cellCount) { -1 }

    /** Pool pair number (1..4) per cell, 0 elsewhere. */
    internal val poolNumber = ByteArray(cellCount)

    /** Bit index of each cracked shell in [GameState.brokenShells]; -1 elsewhere. */
    internal val crackedIndex = IntArray(cellCount) { -1 }

    /** Bit index of each sand pit in [GameState.filledPits]; -1 elsewhere. */
    internal val pitIndex = IntArray(cellCount) { -1 }

    val crackedCells: IntArray
    val pitCells: IntArray
    val poolPairCells: List<IntArray> = poolPairs.map { it.copyOf() }

    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "board ${width}x$height out of range" }
        require(tiles.size == cellCount) { "tile array size ${tiles.size} != $cellCount" }
        poolPairs.forEachIndexed { index, pair ->
            require(pair.size == 2) { "pool pair must have two cells" }
            poolTwin[pair[0]] = pair[1]
            poolTwin[pair[1]] = pair[0]
            poolNumber[pair[0]] = (index + 1).toByte()
            poolNumber[pair[1]] = (index + 1).toByte()
        }
        val cracked = mutableListOf<Int>()
        val pits = mutableListOf<Int>()
        for (c in 0 until cellCount) {
            when (tiles[c]) {
                TileType.CRACKED_SHELL.code -> {
                    crackedIndex[c] = cracked.size
                    cracked += c
                }

                TileType.PIT.code -> {
                    pitIndex[c] = pits.size
                    pits += c
                }
            }
        }
        require(cracked.size <= 63) { "too many cracked shells" }
        require(pits.size <= 63) { "too many pits" }
        crackedCells = cracked.toIntArray()
        pitCells = pits.toIntArray()
    }

    /** Pieces a level can ever hold at once: hatchlings, late eggs and coconuts. */
    val maxPieces: Int get() = hatchlingStarts.size + eggCells.size + coconutStarts.size

    /** Total hatchlings that must reach the ocean, including those still inside late eggs. */
    val totalHatchlings: Int get() = hatchlingStarts.size + eggCells.size

    fun tile(cell: Int): TileType = TileType.fromCode(tiles[cell])

    fun tile(x: Int, y: Int): TileType = tile(index(x, y))

    fun index(x: Int, y: Int): Int = y * width + x

    fun cellOf(index: Int): Cell = Cell(index % width, index / width)

    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Neighbouring cell index in [direction], or -1 if that would leave the board. */
    fun step(cell: Int, direction: Direction): Int {
        val x = cell % width + direction.dx
        val y = cell / width + direction.dy
        return if (x in 0 until width && y in 0 until height) y * width + x else -1
    }

    fun poolTwinOf(cell: Int): Int = poolTwin[cell]

    fun poolNumberOf(cell: Int): Int = poolNumber[cell].toInt()

    /** Crab position for a ping-pong phase. */
    fun crabCell(crab: Int, phase: Int): Int {
        val path = crabPaths[crab]
        return path[pingPongIndex(phase, path.size)]
    }

    fun shadowCell(shadow: Int, phase: Int): Int = shadowPaths[shadow][phase]

    /** Visual variant (0..[variants]-1) for decorative choice per cell, stable for a given level seed. */
    fun variant(cell: Int, seed: Long, variants: Int): Int {
        var h = seed xor (cell.toLong() * -0x61c8864680b583ebL)
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = h xor (h ushr 29)
        return ((h ushr 1) % variants).toInt()
    }

    companion object {
        const val MAX_SIDE = 16

        internal fun pingPongIndex(phase: Int, length: Int): Int = if (length < 2 || phase < length) phase else 2 * (length - 1) - phase

        internal fun pingPongPeriod(length: Int): Int = if (length < 2) 1 else 2 * (length - 1)
    }
}
