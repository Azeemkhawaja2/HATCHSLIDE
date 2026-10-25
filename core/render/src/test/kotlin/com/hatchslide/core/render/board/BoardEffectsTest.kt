package com.hatchslide.core.render.board

import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.fx.ParticlePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardEffectsTest {
    private val rows = listOf(
        "~~~~",
        "T...",
        "#ww>",
        "....",
    )
    private val board = LevelCodec.parse(LevelFile("t", 1, 1, "T", rows, par = 1, gullMoves = 20, seed = 7)).board
    private val heights = FloatArray(board.cellCount)

    private fun effects(theme: WorldTheme, pool: ParticlePool = ParticlePool(1024)) =
        BoardEffects(pool).also { it.begin(theme, board, heights, slots = 2) } to pool

    /** Slides piece 0 along row [row] from x 0 to [toX] in small frames. */
    private fun slide(effects: BoardEffects, row: Int, toX: Float, hatchling: Boolean = true) {
        var x = 0f
        while (x <= toX) {
            effects.piece(0, x, row.toFloat(), x - 1.5f, row - 1.5f, sliding = true, hatchling = hatchling)
            x += 0.05f
        }
        effects.piece(0, toX, row.toFloat(), 0f, 0f, sliding = false, hatchling = hatchling)
    }

    private fun footprints(pool: ParticlePool): Int {
        // Ground quads are written first; count quads whose four corners share one height.
        val out = java.nio.ByteBuffer.allocateDirect(pool.capacity * 4 * ParticlePool.STRIDE).order(java.nio.ByteOrder.nativeOrder())
        val quads = pool.write(out, floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        var flat = 0
        for (q in 0 until quads) {
            val base = q * 4 * ParticlePool.STRIDE
            val y0 = out.getFloat(base + 4)
            if ((1 until 4).all { out.getFloat(base + it * ParticlePool.STRIDE + 4) == y0 } &&
                out.getFloat(base + 8) != out.getFloat(base + 2 * ParticlePool.STRIDE + 8)
            ) {
                flat++
            }
        }
        return flat
    }

    @Test
    fun hatchlingsLeaveFootprintsAndSandOnSand() {
        val (effects, pool) = effects(WorldTheme.SUNRISE_COVE)
        slide(effects, row = 1, toX = 3f)
        val prints = footprints(pool)
        assertTrue("about one print per 0.42 cells: $prints", prints in 6..8)
        assertTrue("sand trail grains as well", pool.count > prints + 20)
    }

    @Test
    fun coconutsTrailSandButLeaveNoFootprints() {
        val (effects, pool) = effects(WorldTheme.SUNRISE_COVE)
        slide(effects, row = 3, toX = 3f, hatchling = false)
        assertEquals(0, footprints(pool))
        assertTrue(pool.count > 20)
    }

    @Test
    fun footprintsFadeAwayAfterFourSeconds() {
        val (effects, pool) = effects(WorldTheme.SUNRISE_COVE)
        slide(effects, row = 1, toX = 3f)
        pool.update(BoardEffects.PRINT_SECONDS + 0.01f)
        assertEquals(0, pool.count)
    }

    @Test
    fun reducedMotionCutsBurstsByAboutEightyPercent() {
        val full = effects(WorldTheme.SUNRISE_COVE, ParticlePool(4096)).let { (fx, pool) ->
            repeat(200) { fx.stop(0f, 0f, cell = 4 * 3 + 1) }
            pool.count
        }
        val reduced = effects(WorldTheme.SUNRISE_COVE, ParticlePool(4096).apply { density = 0.2f }).let { (fx, pool) ->
            repeat(200) { fx.stop(0f, 0f, cell = 4 * 3 + 1) }
            pool.count
        }
        assertEquals(2000, full)
        assertTrue("about 400: $reduced", reduced in 340..460)
    }

    @Test
    fun stopsOnRockRaiseNoDust() {
        val (effects, pool) = effects(WorldTheme.SUNRISE_COVE)
        effects.stop(0f, 0f, cell = 4 * 2)
        assertEquals(0, pool.count)
    }

    @Test
    fun firefliesOnlyAtNightAndAshOnlyOnTheVolcano() {
        effects(WorldTheme.SUNRISE_COVE).let { (fx, pool) ->
            fx.update()
            assertEquals(0, pool.count)
        }
        effects(WorldTheme.MOONLIT_BAY).let { (fx, pool) ->
            fx.update()
            assertEquals(22, pool.countOf(ParticlePool.GROUP_FIREFLY))
            assertEquals(0, pool.countOf(ParticlePool.GROUP_ASH))
        }
        effects(WorldTheme.VOLCANIC_ISLE).let { (fx, pool) ->
            fx.update()
            assertEquals(48, pool.countOf(ParticlePool.GROUP_ASH))
            // Ambient emitters top up as particles die.
            pool.update(12f)
            fx.update()
            assertEquals(48, pool.countOf(ParticlePool.GROUP_ASH))
        }
    }

    @Test
    fun confettiBurstsOverTheBoard() {
        val (effects, pool) = effects(WorldTheme.CORAL_COAST)
        effects.confetti()
        assertEquals(48, pool.count)
    }
}
