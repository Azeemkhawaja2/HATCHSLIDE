package com.hatchslide.tools.levelgen

import com.hatchslide.core.game.CrabSpec
import com.hatchslide.core.game.EggSpec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.game.Mechanic
import com.hatchslide.core.game.PoolSpec
import com.hatchslide.core.game.ShadowSpec

/** A mutable board under construction. Row 0 is the shoreline. */
class Draft(val width: Int, val height: Int) {
    val grid: Array<CharArray> = Array(height) { CharArray(width) { '.' } }
    val crabs: MutableList<List<Pair<Int, Int>>> = mutableListOf()

    /** (x, y, timer) */
    val eggs: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    val shadows: MutableList<List<Pair<Int, Int>>> = mutableListOf()

    operator fun get(x: Int, y: Int): Char = grid[y][x]

    operator fun set(x: Int, y: Int, ch: Char) {
        grid[y][x] = ch
    }

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun copy(): Draft {
        val d = Draft(width, height)
        for (y in 0 until height) grid[y].copyInto(d.grid[y])
        d.crabs += crabs
        d.eggs += eggs
        d.shadows += shadows
        return d
    }

    fun cells(): List<Pair<Int, Int>> = (0 until height).flatMap { y -> (0 until width).map { x -> x to y } }

    fun count(ch: Char): Int = grid.sumOf { row -> row.count { it == ch } }

    fun cellsWith(ch: Char): List<Pair<Int, Int>> = cells().filter { (x, y) -> this[x, y] == ch }

    /** Cells covered by crab paths (crabs need their whole path free of fixed pieces). */
    fun crabCells(): Set<Pair<Int, Int>> = crabs.flatten().toSet()

    fun toLevelFile(
        id: String,
        world: Int,
        index: Int,
        name: String,
        par: Int,
        gullMoves: Int,
        seed: Long,
        tip: String? = null,
        mechanic: Mechanic? = null,
    ): LevelFile {
        val pools = (1..4).mapNotNull { n ->
            val cells = cellsWith('0' + n)
            if (cells.size == 2) PoolSpec(n, listOf(cells[0].first, cells[0].second), listOf(cells[1].first, cells[1].second)) else null
        }
        return LevelFile(
            id = id,
            world = world,
            index = index,
            name = name,
            grid = grid.map { String(it) },
            crabs = crabs.map { path -> CrabSpec(path.map { listOf(it.first, it.second) }) },
            eggs = eggs.map { EggSpec(listOf(it.first, it.second), it.third) },
            pools = pools,
            gullShadows = shadows.map { path -> ShadowSpec(path.map { listOf(it.first, it.second) }) },
            par = par,
            gullMoves = gullMoves,
            tip = tip,
            seed = seed,
            mechanic = mechanic,
        )
    }

    override fun toString(): String = grid.joinToString("\n") { String(it) }

    companion object {
        fun of(vararg rows: String): Draft {
            val d = Draft(rows[0].length, rows.size)
            rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> d[x, y] = ch } }
            return d
        }
    }
}
