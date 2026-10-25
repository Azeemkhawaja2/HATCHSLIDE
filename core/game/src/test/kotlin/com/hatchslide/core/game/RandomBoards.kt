package com.hatchslide.core.game

import kotlin.random.Random

/** Random but valid boards that mix every cell type, for property tests and solver cross-checks. */
internal object RandomBoards {
    fun level(random: Random, maxWidth: Int = 9, maxHeight: Int = 12, gullMoves: Int = 30): Level {
        while (true) {
            val file = file(random, maxWidth, maxHeight, gullMoves) ?: continue
            return try {
                LevelCodec.parse(file)
            } catch (_: LevelFormatException) {
                continue
            }
        }
    }

    private fun file(random: Random, maxWidth: Int, maxHeight: Int, gullMoves: Int): LevelFile? {
        val width = random.nextInt(4, maxWidth + 1)
        val height = random.nextInt(4, maxHeight + 1)
        val grid = Array(height) { CharArray(width) { '.' } }
        for (x in 0 until width) if (random.nextDouble() < 0.8) grid[0][x] = '~'
        if (random.nextBoolean()) grid[random.nextInt(1, height)][if (random.nextBoolean()) 0 else width - 1] = '~'
        val weighted = "##SSwwCC><^vOO.."
        for (y in 1 until height) {
            for (x in 0 until width) {
                if (random.nextDouble() < 0.22) grid[y][x] = weighted[random.nextInt(weighted.length)]
            }
        }
        val free = { (1 until height).flatMap { y -> (0 until width).map { x -> x to y } }.filter { (x, y) -> grid[y][x] == '.' } }

        fun place(ch: Char, count: Int): List<Pair<Int, Int>> {
            val cells = free().shuffled(random).take(count)
            cells.forEach { (x, y) -> grid[y][x] = ch }
            return cells
        }
        place('T', random.nextInt(1, 6))
        place('o', random.nextInt(0, 3))
        val eggs = place('e', random.nextInt(0, 3)).map { EggSpec(listOf(it.first, it.second), random.nextInt(1, 6)) }
        val pools = mutableListOf<PoolSpec>()
        repeat(random.nextInt(0, 3)) { k ->
            val pair = free().shuffled(random).take(2)
            if (pair.size == 2) {
                val (a, b) = pair
                if (kotlin.math.abs(a.first - b.first) + kotlin.math.abs(a.second - b.second) > 1) {
                    grid[a.second][a.first] = '1' + k
                    grid[b.second][b.first] = '1' + k
                    pools += PoolSpec(k + 1, listOf(a.first, a.second), listOf(b.first, b.second))
                }
            }
        }
        // Renumber pools if one was skipped.
        if (pools.size == 1 && pools[0].pair == 2) return null
        val crabs = mutableListOf<CrabSpec>()
        repeat(random.nextInt(0, 3)) {
            val start = free().randomOrNull(random) ?: return@repeat
            val path = mutableListOf(start)
            repeat(random.nextInt(0, 4)) {
                val (x, y) = path.last()
                val options = listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1).filter { (nx, ny) ->
                    nx in 0 until width && ny in 1 until height && grid[ny][nx] in ".w" && (nx to ny) !in path
                }
                if (options.isNotEmpty()) path += options.random(random)
            }
            grid[start.second][start.first] = 'c'
            crabs += CrabSpec(path.map { listOf(it.first, it.second) })
        }
        val shadows = mutableListOf<ShadowSpec>()
        if (random.nextDouble() < 0.4 && width >= 3 && height >= 3) {
            val x0 = random.nextInt(0, width - 1)
            val y0 = random.nextInt(0, height - 1)
            shadows += ShadowSpec(listOf(listOf(x0, y0), listOf(x0 + 1, y0), listOf(x0 + 1, y0 + 1), listOf(x0, y0 + 1)))
        }
        return LevelFile(
            id = "random",
            world = 1,
            index = 1,
            name = "Random",
            grid = grid.map { String(it) },
            crabs = crabs,
            eggs = eggs,
            pools = pools,
            gullShadows = shadows,
            par = 1,
            gullMoves = gullMoves,
            seed = random.nextLong(),
        )
    }
}
