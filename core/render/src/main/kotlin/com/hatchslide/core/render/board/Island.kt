package com.hatchslide.core.render.board

import com.hatchslide.core.game.Board
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.MeshBuilder
import com.hatchslide.core.render.MeshData
import com.hatchslide.core.render.Surfaces
import com.hatchslide.core.render.math.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** A free spot on the island margin for decoration, in world units, with its distance from the board. */
class DecorSpot(val x: Float, val z: Float, val ring: Float, val side: Side) {
    enum class Side { LEFT, RIGHT, FAR, NEAR }
}

/** An RGBA8 texture laid over the world's xz plane: pixels plus (originX, originZ, 1/sizeX, 1/sizeZ). */
class GroundTexture(val width: Int, val height: Int, val pixels: ByteArray, val transform: FloatArray)

/** Static scenery of one level (spec section 6, board and island). */
class IslandGeometry(
    val tiles: MeshData,
    val chunk: MeshData,
    /** Terrain mask: r = wet sand, g = footprints (written at run time). */
    val mask: GroundTexture,
    /** Water shoreline distance: r = 0 at the waterline to 1 three cells out. */
    val shore: GroundTexture,
    val decor: List<DecorSpot>,
    /** Per-cell tile top height (tiny noise), for placing pieces. */
    val tileHeights: FloatArray,
)

/**
 * Builds the diorama for a board: softly bevelled tiles with height noise on an island chunk whose
 * sculpted cliffs drop into the sea, plus the textures that make sand wet near the water and draw
 * the foam band along the shore. Pure Kotlin so it is unit-testable; the stage uploads the result.
 *
 * World layout: cell (x, y) is centred at (x - (w - 1) / 2, 0, y - (h - 1) / 2); tiles top out at
 * y = 0, the island chunk at [Surfaces.ISLAND_TOP] and the sea at [Surfaces.WATER_LEVEL].
 */
object IslandBuilder {
    /** Island margin around the board, in cells. */
    const val MARGIN = 2

    /** Extra cells of distance field beyond the margin, so the shoreline texture fades out. */
    private const val SHORE_EXTRA = 4
    private const val SDF_SAMPLES = 8
    private const val CHUNK_SAMPLES = 8
    private const val MASK_SAMPLES = 8
    private const val SHORE_SAMPLES = 4

    /** How far the island top reaches past land cells, so no tile hangs over water. */
    private const val EDGE = 0.12f

    /** Horizontal distance from the cliff top to the waterline (see [height]). */
    private const val WATERLINE = 0.24f

    const val TILE_HALF = 0.48f
    private const val BEVEL = 0.05f
    private const val OUTLINE = 40
    private const val SUPERELLIPSE = 8.0
    const val PIT_HOLE = 0.28f
    const val POOL_HOLE = 0.43f
    private const val SEA_FLOOR = -2.9f

    fun build(board: Board, seed: Long): IslandGeometry {
        val field = DistanceField(board, seed)
        val heights = FloatArray(board.cellCount) { cell ->
            if (board.tile(cell) == TileType.OCEAN) 0f else (board.variant(cell, seed xor 0x5A5AL, 7) - 3) * 0.004f
        }
        return IslandGeometry(
            tiles = tiles(board, heights),
            chunk = chunk(board, field),
            mask = mask(board, field),
            shore = shore(field),
            decor = decor(board, field, seed),
            tileHeights = heights,
        )
    }

    // ----- Distance field -----

    /** Signed distance (in cells, negative on land) to the island outline, sampled over the board plus margins. */
    private class DistanceField(val board: Board, seed: Long) {
        val pad = MARGIN + SHORE_EXTRA
        val cellsX = board.width + 2 * pad
        val cellsZ = board.height + 2 * pad
        val nx = cellsX * SDF_SAMPLES
        val nz = cellsZ * SDF_SAMPLES
        val originX = -board.width / 2f - pad
        val originZ = -board.height / 2f - pad
        val noise = ValueNoise(seed)

        /** Signed distance to the coast after noise, in cells. */
        val coast = FloatArray(nx * nz)

        /** Distance from the nearest board land cell, in cells (0 on the board). */
        val fromBoard = FloatArray(nx * nz)

        init {
            val land = FloatArray(nx * nz)
            val boardLand = BooleanArray(nx * nz)
            for (j in 0 until nz) {
                for (i in 0 until nx) {
                    val cx = floor((i + 0.5f) / SDF_SAMPLES).toInt() - pad
                    val cz = floor((j + 0.5f) / SDF_SAMPLES).toInt() - pad
                    val k = j * nx + i
                    if (board.contains(cx, cz)) {
                        val isLand = board.tile(cx, cz) != TileType.OCEAN
                        boardLand[k] = isLand
                        land[k] = if (isLand) 1f else 0f
                    } else if (max(max(-cx, cx - board.width + 1), max(-cz, cz - board.height + 1)) <= MARGIN) {
                        val ex = cx.coerceIn(0, board.width - 1)
                        val ez = cz.coerceIn(0, board.height - 1)
                        land[k] = if (board.tile(ex, ez) == TileType.OCEAN) 0f else 1f
                    }
                }
            }
            // Round the outline's corners, but never shave a board land cell.
            val rounded = blur(land, nx, nz, (SDF_SAMPLES * 0.3f).toInt())
            val inside = BooleanArray(nx * nz) { rounded[it] >= 0.5f || boardLand[it] }
            val toWater = distanceTransform(BooleanArray(nx * nz) { !inside[it] }, nx, nz)
            val toLand = distanceTransform(inside, nx, nz)
            val toBoard = distanceTransform(boardLand, nx, nz)
            for (j in 0 until nz) {
                for (i in 0 until nx) {
                    val k = j * nx + i
                    val signed = (sqrt(toLand[k]) - sqrt(toWater[k])) / SDF_SAMPLES
                    fromBoard[k] = sqrt(toBoard[k]) / SDF_SAMPLES
                    val far = smoothstep((fromBoard[k] - 0.7f) / 1.0f)
                    val x = originX + (i + 0.5f) / SDF_SAMPLES
                    val z = originZ + (j + 0.5f) / SDF_SAMPLES
                    coast[k] = signed + noise.fbm(x * 0.8f, z * 0.8f) * 0.4f * far
                }
            }
        }

        private fun sample(values: FloatArray, x: Float, z: Float, outside: Float): Float {
            val fx = (x - originX) * SDF_SAMPLES - 0.5f
            val fz = (z - originZ) * SDF_SAMPLES - 0.5f
            if (fx < 0f || fz < 0f || fx > nx - 1f || fz > nz - 1f) return outside
            val i = min(fx.toInt(), nx - 2)
            val j = min(fz.toInt(), nz - 2)
            val tx = fx - i
            val tz = fz - j
            val a = values[j * nx + i] + (values[j * nx + i + 1] - values[j * nx + i]) * tx
            val b = values[(j + 1) * nx + i] + (values[(j + 1) * nx + i + 1] - values[(j + 1) * nx + i]) * tx
            return a + (b - a) * tz
        }

        fun coastAt(x: Float, z: Float) = sample(coast, x, z, 99f)

        fun boardDistanceAt(x: Float, z: Float) = sample(fromBoard, x, z, 99f)
    }

    /** Terrain height at signed coast distance [s]: flat island top, a steep cliff, then the sea floor. */
    private fun height(s: Float, x: Float, z: Float, noise: ValueNoise, dunes: Float): Float {
        if (s <= EDGE) return Surfaces.ISLAND_TOP + dunes * 0.03f * noise.fbm(x * 0.9f + 11f, z * 0.9f - 7f)
        val u = s - EDGE
        val cliff = smoothstep(u / 0.45f)
        val base = Surfaces.ISLAND_TOP - (Surfaces.ISLAND_TOP - (Surfaces.WATER_LEVEL - 0.35f)) * cliff
        val ledges = noise.fbm(x * 2.3f, z * 2.3f + 5f) * 0.07f * cliff
        return max(SEA_FLOOR, base - 1.8f * smoothstep((u - 0.3f) / 2.2f) + ledges)
    }

    // ----- Chunk -----

    private fun chunk(board: Board, field: DistanceField): MeshData {
        val reach = MARGIN + 1.5f
        val cellsX = board.width + 2 * reach
        val cellsZ = board.height + 2 * reach
        val nx = (cellsX * CHUNK_SAMPLES).toInt()
        val nz = (cellsZ * CHUNK_SAMPLES).toInt()
        val x0 = -board.width / 2f - reach
        val z0 = -board.height / 2f - reach
        val step = 1f / CHUNK_SAMPLES
        val h = FloatArray((nx + 1) * (nz + 1))
        for (j in 0..nz) {
            for (i in 0..nx) {
                val x = x0 + i * step
                val z = z0 + j * step
                val dunes = smoothstep((field.boardDistanceAt(x, z) - 0.5f) / 1f)
                h[j * (nx + 1) + i] = height(field.coastAt(x, z), x, z, field.noise, dunes)
            }
        }
        val b = MeshBuilder(withUvs = false)
        val index = IntArray((nx + 1) * (nz + 1)) { -1 }
        fun vertex(i: Int, j: Int): Int {
            val k = j * (nx + 1) + i
            if (index[k] >= 0) return index[k]
            val l = h[j * (nx + 1) + max(i - 1, 0)]
            val r = h[j * (nx + 1) + min(i + 1, nx)]
            val d = h[max(j - 1, 0) * (nx + 1) + i]
            val u = h[min(j + 1, nz) * (nx + 1) + i]
            val gx = (r - l) / (2 * step)
            val gz = (u - d) / (2 * step)
            val len = sqrt(gx * gx + 1f + gz * gz)
            val y = h[k]
            val ao = 1f - 0.45f * ((Surfaces.ISLAND_TOP - y) / 1.2f).coerceIn(0f, 1f)
            index[k] = b.vertex(x0 + i * step, y, z0 + j * step, -gx / len, 1f / len, -gz / len, ao = ao)
            return index[k]
        }
        for (j in 0 until nz) {
            for (i in 0 until nx) {
                val corners =
                    floatArrayOf(
                        h[j * (nx + 1) + i],
                        h[j * (nx + 1) + i + 1],
                        h[(j + 1) * (nx + 1) + i],
                        h[
                            (j + 1) * (nx + 1) + i +
                                1,
                        ],
                    )
                if (corners.max() < -2.2f) continue
                val a = vertex(i, j)
                val c = vertex(i + 1, j)
                val d = vertex(i + 1, j + 1)
                val e = vertex(i, j + 1)
                // Counter-clockwise seen from above (+y): x right, z toward the viewer.
                b.quad(a, e, d, c)
            }
        }
        return b.build()
    }

    // ----- Tiles -----

    private fun tiles(board: Board, heights: FloatArray): MeshData {
        val outline = Array(OUTLINE) { k ->
            val t = 2 * PI * k / OUTLINE
            val c = cos(t)
            val s = sin(t)
            val ux = sign(c) * abs(c).pow(2.0 / SUPERELLIPSE)
            val uz = sign(s) * abs(s).pow(2.0 / SUPERELLIPSE)
            val gx = sign(ux) * abs(ux).pow(SUPERELLIPSE - 1)
            val gz = sign(uz) * abs(uz).pow(SUPERELLIPSE - 1)
            val gl = sqrt(gx * gx + gz * gz)
            floatArrayOf(
                (ux * TILE_HALF).toFloat(),
                (uz * TILE_HALF).toFloat(),
                (gx / gl).toFloat(),
                (gz / gl).toFloat(),
                c.toFloat(),
                s.toFloat(),
            )
        }
        val b = MeshBuilder(withUvs = false)
        val bottom = Surfaces.ISLAND_TOP - 0.03f
        for (cell in 0 until board.cellCount) {
            val tile = board.tile(cell)
            if (tile == TileType.OCEAN) continue
            val cx = cell % board.width - (board.width - 1) / 2f
            val cz = cell / board.width - (board.height - 1) / 2f
            val top = heights[cell]
            val hole = when (tile) {
                TileType.PIT -> PIT_HOLE
                TileType.TIDE_POOL -> POOL_HOLE
                else -> 0f
            }
            // Rings from the top face edge, around the bevel, down the side.
            val rings = ArrayList<IntArray>()
            val bevelSteps = 3
            for (r in 0..bevelSteps) {
                val phi = (PI / 2 * r / bevelSteps).toFloat()
                val out = BEVEL * sin(phi) - BEVEL
                val y = top - BEVEL + BEVEL * cos(phi)
                rings += IntArray(OUTLINE) { k ->
                    val o = outline[k]
                    b.vertex(cx + o[0] + o[2] * out, y, cz + o[1] + o[3] * out, o[2] * sin(phi), cos(phi), o[3] * sin(phi))
                }
            }
            for (y in floatArrayOf(top - BEVEL, bottom)) {
                val ao = if (y == bottom) 0.45f else 1f
                rings += IntArray(OUTLINE) { k ->
                    val o = outline[k]
                    b.vertex(cx + o[0], y, cz + o[1], o[2], 0f, o[3], ao = ao)
                }
            }
            for (r in 0 until rings.size - 1) {
                if (r == bevelSteps) continue
                for (k in 0 until OUTLINE) {
                    val n = (k + 1) % OUTLINE
                    b.quad(rings[r][k], rings[r][n], rings[r + 1][n], rings[r + 1][k])
                }
            }
            val edge = rings[0]
            if (hole > 0f) {
                val inner = IntArray(OUTLINE) { k ->
                    val o = outline[k]
                    b.vertex(cx + o[4] * hole, top, cz + o[5] * hole, 0f, 1f, 0f)
                }
                for (k in 0 until OUTLINE) {
                    val n = (k + 1) % OUTLINE
                    b.quad(inner[k], inner[n], edge[n], edge[k])
                }
            } else {
                val centre = b.vertex(cx, top, cz, 0f, 1f, 0f)
                for (k in 0 until OUTLINE) b.triangle(centre, edge[(k + 1) % OUTLINE], edge[k])
            }
        }
        return b.build()
    }

    // ----- Textures -----

    private fun mask(board: Board, field: DistanceField): GroundTexture {
        val pad = MARGIN + 1
        val w = (board.width + 2 * pad) * MASK_SAMPLES
        val h = (board.height + 2 * pad) * MASK_SAMPLES
        val x0 = -board.width / 2f - pad
        val z0 = -board.height / 2f - pad
        val oceanDistance = distanceTransform(
            BooleanArray(w * h) { k ->
                val cx = floor((k % w + 0.5f) / MASK_SAMPLES).toInt() - pad
                val cz = floor((k / w + 0.5f) / MASK_SAMPLES).toInt() - pad
                board.contains(cx, cz) && board.tile(cx, cz) == TileType.OCEAN
            },
            w,
            h,
        )
        val wet = FloatArray(w * h)
        for (k in 0 until w * h) {
            val cx = floor((k % w + 0.5f) / MASK_SAMPLES).toInt() - pad
            val cz = floor((k / w + 0.5f) / MASK_SAMPLES).toInt() - pad
            val tileWet = if (board.contains(cx, cz) && board.tile(cx, cz) == TileType.WET_SAND) 1f else 0f
            val nearSea = 0.55f * (1f - smoothstep(sqrt(oceanDistance[k]) / MASK_SAMPLES / 1.3f))
            wet[k] = max(tileWet, nearSea)
        }
        val soft = blur(wet, w, h, 2)
        val pixels = ByteArray(w * h * 4)
        for (k in 0 until w * h) pixels[k * 4] = (soft[k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        val sizeX = w.toFloat() / MASK_SAMPLES
        val sizeZ = h.toFloat() / MASK_SAMPLES
        return GroundTexture(w, h, pixels, floatArrayOf(x0, z0, 1f / sizeX, 1f / sizeZ))
    }

    private fun shore(field: DistanceField): GroundTexture {
        val w = field.cellsX * SHORE_SAMPLES
        val h = field.cellsZ * SHORE_SAMPLES
        val pixels = ByteArray(w * h * 4)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val x = field.originX + (i + 0.5f) / SHORE_SAMPLES
                val z = field.originZ + (j + 0.5f) / SHORE_SAMPLES
                val distance = ((field.coastAt(x, z) - EDGE - WATERLINE) / 3f).coerceIn(0f, 1f)
                val v = (distance * 255f + 0.5f).toInt().toByte()
                val k = (j * w + i) * 4
                pixels[k] = v
                pixels[k + 1] = v
                pixels[k + 2] = v
                pixels[k + 3] = -1
            }
        }
        // Texels on the border must read as open sea, since sampling clamps to the edge.
        for (i in 0 until w) {
            pixels[i * 4] = -1
            pixels[((h - 1) * w + i) * 4] = -1
        }
        for (j in 0 until h) {
            pixels[j * w * 4] = -1
            pixels[(j * w + w - 1) * 4] = -1
        }
        return GroundTexture(w, h, pixels, floatArrayOf(field.originX, field.originZ, 1f / field.cellsX, 1f / field.cellsZ))
    }

    // ----- Decor -----

    private fun decor(board: Board, field: DistanceField, seed: Long): List<DecorSpot> {
        val spots = ArrayList<DecorSpot>()
        val noise = ValueNoise(seed xor 0x77L)
        for (cz in -MARGIN until board.height + MARGIN) {
            for (cx in -MARGIN until board.width + MARGIN) {
                if (board.contains(cx, cz)) continue
                val jx = (noise.hash(cx, cz, 1) - 0.5f) * 0.5f
                val jz = (noise.hash(cx, cz, 2) - 0.5f) * 0.5f
                val x = cx - (board.width - 1) / 2f + jx
                val z = cz - (board.height - 1) / 2f + jz
                // Keep clear of the cliff edge.
                if (field.coastAt(x, z) > -0.45f) continue
                val side = when {
                    cz < 0 -> DecorSpot.Side.FAR
                    cz >= board.height -> DecorSpot.Side.NEAR
                    cx < 0 -> DecorSpot.Side.LEFT
                    else -> DecorSpot.Side.RIGHT
                }
                spots += DecorSpot(x, z, field.boardDistanceAt(x, z), side)
            }
        }
        return spots
    }

    // ----- Helpers -----

    /** Squared Euclidean distance (in samples) from every cell to the nearest true cell (Felzenszwalb). */
    internal fun distanceTransform(target: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e12f
        val grid = FloatArray(w * h) { if (target[it]) 0f else inf }
        val column = FloatArray(max(w, h))
        val out = FloatArray(max(w, h))
        val v = IntArray(max(w, h))
        val z = FloatArray(max(w, h) + 1)
        fun pass(n: Int) {
            var k = 0
            v[0] = 0
            z[0] = -inf
            z[1] = inf
            for (q in 1 until n) {
                var s: Float
                while (true) {
                    val p = v[k]
                    s = ((column[q] + q * q) - (column[p] + p * p)) / (2f * q - 2f * p)
                    if (s <= z[k] && k > 0) k-- else break
                }
                if (s <= z[k]) {
                    v[0] = q
                    z[0] = -inf
                    z[1] = inf
                    k = 0
                } else {
                    k++
                    v[k] = q
                    z[k] = s
                    z[k + 1] = inf
                }
            }
            k = 0
            for (q in 0 until n) {
                while (z[k + 1] < q) k++
                val d = (q - v[k]).toFloat()
                out[q] = d * d + column[v[k]]
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) column[y] = grid[y * w + x]
            pass(h)
            for (y in 0 until h) grid[y * w + x] = out[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) column[x] = grid[y * w + x]
            pass(w)
            for (x in 0 until w) grid[y * w + x] = out[x]
        }
        return grid
    }

    /** Separable box blur with clamped edges. */
    private fun blur(values: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return values.copyOf()
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val span = 2 * radius + 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (d in -radius..radius) sum += values[y * w + (x + d).coerceIn(0, w - 1)]
                tmp[y * w + x] = sum / span
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (d in -radius..radius) sum += tmp[(y + d).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum / span
            }
        }
        return out
    }
}

/** Deterministic smooth value noise in -1..1 (fBm of three octaves), seeded per level. */
internal class ValueNoise(private val seed: Long) {
    fun hash(x: Int, y: Int, salt: Int): Float {
        var h = seed xor (x * 374761393L) xor (y * 668265263L) xor (salt * 1274126177L)
        h = (h xor (h ushr 13)) * 1274126177L
        h = h xor (h ushr 16)
        return ((h and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat())
    }

    private fun value(x: Float, y: Float, salt: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(ix, iy, salt)
        val b = hash(ix + 1, iy, salt)
        val c = hash(ix, iy + 1, salt)
        val d = hash(ix + 1, iy + 1, salt)
        return (a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy) * 2f - 1f
    }

    fun fbm(x: Float, y: Float): Float =
        value(x, y, 0) * 0.57f + value(x * 2.03f, y * 2.03f, 1) * 0.29f + value(x * 4.1f, y * 4.1f, 2) * 0.14f
}
