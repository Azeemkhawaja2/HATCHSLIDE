package com.hatchslide.core.game

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mechanics a level can showcase. Intro levels name one; validation checks the optimal path uses it. */
@Serializable
enum class Mechanic {
    BASICS,
    ROCK,
    SHELL,
    WET_SAND,
    CRACKED_SHELL,
    CURRENT,
    COCONUT,
    PIT,
    CRAB,
    LATE_EGG,
    TIDE_POOL,
    GULL_SHADOW,
}

/** JSON form of a level (spec section 4). Coordinates are `[x, y]` with (0,0) top-left. */
@Serializable
data class LevelFile(
    val id: String,
    val world: Int,
    val index: Int,
    val name: String,
    val grid: List<String>,
    val crabs: List<CrabSpec> = emptyList(),
    val eggs: List<EggSpec> = emptyList(),
    val pools: List<PoolSpec> = emptyList(),
    val gullShadows: List<ShadowSpec> = emptyList(),
    val par: Int,
    val gullMoves: Int,
    /** String-resource key of the one-card tip shown the first time this level is played, or null. */
    val tip: String? = null,
    val seed: Long,
    /** Mechanic this level introduces or showcases; null for regular levels. */
    val mechanic: Mechanic? = null,
)

@Serializable
data class CrabSpec(val path: List<List<Int>>)

@Serializable
data class EggSpec(val cell: List<Int>, val timer: Int)

@Serializable
data class PoolSpec(val pair: Int, val a: List<Int>, val b: List<Int>)

@Serializable
data class ShadowSpec(val path: List<List<Int>>)

/** A world file: `assets/levels/world_<n>.json`. */
@Serializable
data class WorldFile(val world: Int, val levels: List<LevelFile>)

/** The Daily Tide file: `assets/levels/daily.json`, one puzzle per day of the year (366). */
@Serializable
data class DailyFile(val puzzles: List<LevelFile>)

/** A parsed, validated level. */
class Level(
    val file: LevelFile,
    val board: Board,
) {
    val id: String get() = file.id
    val world: Int get() = file.world
    val index: Int get() = file.index
    val name: String get() = file.name
    val par: Int get() = file.par
    val gullMoves: Int get() = file.gullMoves
    val tip: String? get() = file.tip
    val seed: Long get() = file.seed
    val mechanic: Mechanic? get() = file.mechanic

    override fun toString(): String = "Level($id ${board.width}x${board.height} par=$par gull=$gullMoves)"
}

class LevelFormatException(message: String) : IllegalArgumentException(message)

object LevelCodec {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = " "
        encodeDefaults = true
        explicitNulls = true
    }

    fun parseWorld(text: String): List<Level> = json.decodeFromString(WorldFile.serializer(), text).levels.map(::parse)

    fun parseDaily(text: String): List<Level> = json.decodeFromString(DailyFile.serializer(), text).puzzles.map(::parse)

    fun parseLevel(text: String): Level = parse(json.decodeFromString(LevelFile.serializer(), text))

    fun encodeWorld(world: WorldFile): String = json.encodeToString(WorldFile.serializer(), world)

    fun encodeDaily(daily: DailyFile): String = json.encodeToString(DailyFile.serializer(), daily)

    /** Parses and validates a level, throwing [LevelFormatException] with a precise message on any problem. */
    fun parse(file: LevelFile): Level {
        fun fail(message: String): Nothing = throw LevelFormatException("${file.id}: $message")

        val height = file.grid.size
        if (height == 0) fail("empty grid")
        val width = file.grid[0].length
        if (file.grid.any { it.length != width }) fail("rows have different lengths")
        if (width !in 1..Board.MAX_SIDE || height !in 1..Board.MAX_SIDE) fail("grid ${width}x$height out of range")

        val tiles = ByteArray(width * height)
        val hatchlings = mutableListOf<Int>()
        val coconuts = mutableListOf<Int>()
        val crabStarts = mutableListOf<Int>()
        val eggCellsInGrid = mutableListOf<Int>()
        val poolCells = sortedMapOf<Int, MutableList<Int>>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = y * width + x
                val tile = when (val ch = file.grid[y][x]) {
                    '.' -> TileType.SAND
                    'T' -> TileType.SAND.also { hatchlings += c }
                    'o' -> TileType.SAND.also { coconuts += c }
                    'c' -> TileType.SAND.also { crabStarts += c }
                    'e' -> TileType.SAND.also { eggCellsInGrid += c }
                    '#' -> TileType.ROCK
                    'S' -> TileType.SHELL
                    '~' -> TileType.OCEAN
                    'w' -> TileType.WET_SAND
                    'C' -> TileType.CRACKED_SHELL
                    '^' -> TileType.CURRENT_UP
                    'v' -> TileType.CURRENT_DOWN
                    '<' -> TileType.CURRENT_LEFT
                    '>' -> TileType.CURRENT_RIGHT
                    'O' -> TileType.PIT
                    in '1'..'4' -> TileType.TIDE_POOL.also { poolCells.getOrPut(ch - '0') { mutableListOf() } += c }
                    else -> fail("unknown cell '$ch' at ($x,$y)")
                }
                tiles[c] = tile.code
            }
        }

        fun cellOf(coords: List<Int>, what: String): Int {
            if (coords.size != 2) fail("$what: coordinates must be [x, y]")
            val (x, y) = coords
            if (x !in 0 until width || y !in 0 until height) fail("$what: ($x,$y) outside the board")
            return y * width + x
        }

        fun adjacent(a: Int, b: Int): Boolean {
            val dx = kotlin.math.abs(a % width - b % width)
            val dy = kotlin.math.abs(a / width - b / width)
            return dx + dy == 1
        }

        // Tide pools: each digit appears exactly twice, and the JSON pairs (if listed) must agree.
        val poolPairs = mutableListOf<IntArray>()
        for ((number, cells) in poolCells) {
            if (cells.size != 2) fail("tide pool $number appears ${cells.size} times, expected 2")
            if (adjacent(cells[0], cells[1])) fail("tide pool $number: twins must not touch")
            poolPairs += cells.toIntArray()
        }
        if (poolCells.keys.toList() != (1..poolCells.size).toList()) fail("tide pools must be numbered 1..n without gaps")
        if (file.pools.isNotEmpty()) {
            val listed = file.pools.sortedBy {
                it.pair
            }.map { listOf(cellOf(it.a, "pool ${it.pair}"), cellOf(it.b, "pool ${it.pair}")).sorted() }
            if (listed != poolPairs.map { it.toList().sorted() }) fail("pools list does not match the grid")
        }

        // Crabs: the grid marks each start cell with 'c'; paths run over walkable sand only.
        val crabPaths = file.crabs.mapIndexed { i, spec ->
            if (spec.path.isEmpty()) fail("crab $i has an empty path")
            val path = spec.path.map { cellOf(it, "crab $i") }.toIntArray()
            path.forEachIndexed { k, c ->
                val t = TileType.fromCode(tiles[c])
                if (t != TileType.SAND && t != TileType.WET_SAND) fail("crab $i path crosses ${t.name} at step $k")
                if (k > 0 && !adjacent(path[k - 1], c)) fail("crab $i path is not contiguous at step $k")
                if (c in hatchlings || c in coconuts || c in eggCellsInGrid) fail("crab $i path starts on a piece at step $k")
            }
            if (path.toSet().size != path.size) fail("crab $i path revisits a cell")
            path
        }
        if (crabPaths.map { it[0] }.sorted() != crabStarts.sorted()) fail("crab start cells do not match 'c' marks in the grid")

        // Late eggs: 'e' marks; timers count real moves until hatching.
        val eggs = file.eggs.map { cellOf(it.cell, "egg") to it.timer }
        if (eggs.map { it.first }.sorted() != eggCellsInGrid.sorted()) fail("egg cells do not match 'e' marks in the grid")
        if (eggs.any { it.second !in 1..15 }) fail("egg timers must be 1..15")

        // Gull shadows: closed loops of adjacent cells.
        val shadowPaths = file.gullShadows.mapIndexed { i, spec ->
            if (spec.path.isEmpty()) fail("gull shadow $i has an empty path")
            val path = spec.path.map { cellOf(it, "gull shadow $i") }.toIntArray()
            for (k in path.indices) {
                val next = path[(k + 1) % path.size]
                if (path.size > 1 && !adjacent(path[k], next)) fail("gull shadow $i loop is not contiguous at step $k")
            }
            path
        }

        if (hatchlings.isEmpty() && eggs.isEmpty()) fail("no hatchlings")
        if (tiles.none { it == TileType.OCEAN.code }) fail("no ocean cells")
        if (file.par < 1) fail("par must be at least 1")
        if (file.gullMoves < file.par) fail("gullMoves ${file.gullMoves} below par ${file.par}")
        if (hatchlings.size + eggs.size + coconuts.size > MAX_PIECES) fail("too many pieces")

        val board = try {
            Board(
                width = width,
                height = height,
                tiles = tiles,
                poolPairs = poolPairs,
                crabPaths = crabPaths,
                shadowPaths = shadowPaths,
                eggCells = eggs.map { it.first }.toIntArray(),
                eggTimers = eggs.map { it.second }.toIntArray(),
                hatchlingStarts = hatchlings.toIntArray(),
                coconutStarts = coconuts.toIntArray(),
            )
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid board")
        }
        return Level(file, board)
    }

    /** Upper bound on simultaneous pieces; keeps solver keys and engine scratch arrays small. */
    const val MAX_PIECES = 16
}
