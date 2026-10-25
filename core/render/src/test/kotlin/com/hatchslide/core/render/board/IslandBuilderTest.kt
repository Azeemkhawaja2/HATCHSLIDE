package com.hatchslide.core.render.board

import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.render.Surfaces
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandBuilderTest {
    private val rows = listOf(
        "~~#~~~",
        "..S...",
        ".1..O.",
        "..TT..",
        "...w..",
        "....1.",
    )
    private val level = LevelCodec.parse(LevelFile("t", 1, 1, "T", rows, par = 1, gullMoves = 20, seed = 42))
    private val island = IslandBuilder.build(level.board, level.seed)
    private val board = level.board

    private fun worldX(cx: Int) = cx - (board.width - 1) / 2f

    private fun worldZ(cy: Int) = cy - (board.height - 1) / 2f

    @Test
    fun meshesUseSixteenBitIndicesAndFaceUp() {
        for (mesh in listOf(island.tiles, island.chunk)) {
            assertTrue(mesh.vertexCount in 1..65535)
            assertEquals(0, mesh.indices.size % 3)
        }
        // Top faces of the tiles point up: every vertex at the tile top has a non-negative normal y.
        val n = island.tiles.normals
        val p = island.tiles.positions
        for (v in 0 until island.tiles.vertexCount) {
            if (abs(p[v * 3 + 1]) < 0.02f) assertTrue(n[v * 3 + 1] >= -1e-4f)
        }
    }

    @Test
    fun noTileForOceanCells() {
        val p = island.tiles.positions
        for (cell in 0 until board.cellCount) {
            if (board.tile(cell).name != "OCEAN") continue
            val cx = worldX(cell % board.width)
            val cz = worldZ(cell / board.width)
            for (v in 0 until island.tiles.vertexCount) {
                val inside = abs(p[v * 3] - cx) < 0.4f && abs(p[v * 3 + 2] - cz) < 0.4f
                assertTrue("tile vertex inside ocean cell $cell", !inside)
            }
        }
    }

    @Test
    fun islandTopSupportsEveryLandCellAndWaterFillsOceanCells() {
        val p = island.chunk.positions
        fun heightNear(x: Float, z: Float): Float {
            var best = Float.MAX_VALUE
            var h = 0f
            for (v in 0 until island.chunk.vertexCount) {
                val d = (p[v * 3] - x) * (p[v * 3] - x) + (p[v * 3 + 2] - z) * (p[v * 3 + 2] - z)
                if (d < best) {
                    best = d
                    h = p[v * 3 + 1]
                }
            }
            return h
        }
        for (cell in 0 until board.cellCount) {
            val x = worldX(cell % board.width)
            val z = worldZ(cell / board.width)
            val h = heightNear(x, z)
            if (board.tile(cell).name == "OCEAN") {
                assertTrue("ocean cell $cell should be under water, was $h", h < Surfaces.WATER_LEVEL)
            } else {
                // Corners of land cells too, so tiles never overhang the cliff.
                for (dx in floatArrayOf(-0.45f, 0.45f)) {
                    for (dz in floatArrayOf(-0.45f, 0.45f)) {
                        assertEquals(Surfaces.ISLAND_TOP, heightNear(x + dx, z + dz), 0.04f)
                    }
                }
            }
        }
    }

    @Test
    fun maskMarksWetSandAndShoreFadesToOpenSea() {
        val mask = island.mask
        fun maskAt(x: Float, z: Float): Int {
            val u = (x - mask.transform[0]) * mask.transform[2]
            val v = (z - mask.transform[1]) * mask.transform[3]
            val i = (u * mask.width).toInt()
            val j = (v * mask.height).toInt()
            return mask.pixels[(j * mask.width + i) * 4].toInt() and 255
        }
        val wet = rows.indexOfFirst { 'w' in it }.let { y -> worldX(rows[y].indexOf('w')) to worldZ(y) }
        assertTrue(maskAt(wet.first, wet.second) > 200)
        assertTrue(maskAt(worldX(0), worldZ(5)) < 40)

        val shore = island.shore
        for (i in 0 until shore.width) assertEquals(255, shore.pixels[i * 4].toInt() and 255)
        // Over the island the shoreline distance is zero.
        val u = ((0f - shore.transform[0]) * shore.transform[2] * shore.width).roundToInt()
        val v = ((0f - shore.transform[1]) * shore.transform[3] * shore.height).roundToInt()
        assertEquals(0, shore.pixels[(v * shore.width + u) * 4].toInt() and 255)
    }

    @Test
    fun decorStaysOffTheBoard() {
        assertTrue(island.decor.isNotEmpty())
        for (spot in island.decor) {
            val outsideX = abs(spot.x) > board.width / 2f - 0.25f
            val outsideZ = abs(spot.z) > board.height / 2f - 0.25f
            assertTrue("decor at ${spot.x}, ${spot.z} overlaps the board", outsideX || outsideZ)
        }
    }

    @Test
    fun distanceTransformIsExact() {
        val w = 7
        val h = 5
        val target = BooleanArray(w * h).also { it[2 * w + 3] = true }
        val d = IslandBuilder.distanceTransform(target, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) assertEquals(((x - 3) * (x - 3) + (y - 2) * (y - 2)).toFloat(), d[y * w + x], 1e-3f)
        }
    }
}
