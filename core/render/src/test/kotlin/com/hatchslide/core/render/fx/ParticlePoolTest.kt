package com.hatchslide.core.render.fx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticlePoolTest {
    private val left = floatArrayOf(-1f, 0f, 0f)
    private val up = floatArrayOf(0f, 1f, 0f)

    private fun buffer(pool: ParticlePool) =
        ByteBuffer.allocateDirect(pool.capacity * 4 * ParticlePool.STRIDE).order(ByteOrder.nativeOrder())

    private fun alphaOf(out: ByteBuffer, quad: Int) = out.get(quad * 4 * ParticlePool.STRIDE + 23).toInt() and 255

    @Test
    fun poolNeverGrowsPastCapacity() {
        val pool = ParticlePool(8)
        repeat(8) { assertTrue(pool.spawn(0, 0f, 0f, 0f) >= 0) }
        assertEquals(-1, pool.spawn(0, 0f, 0f, 0f))
        assertEquals(8, pool.count)
    }

    @Test
    fun particlesDieAtTheEndOfTheirLifeAndGroupCountsFollow() {
        val pool = ParticlePool(16)
        repeat(4) { pool.spawn(0, 0f, 0f, 0f, lifetime = 0.5f) }
        repeat(3) { pool.spawn(0, 0f, 0f, 0f, lifetime = 2f, tag = ParticlePool.GROUP_FIREFLY) }
        repeat(2) { pool.spawn(0, 0f, 0f, 0f, lifetime = 0.5f, tag = ParticlePool.GROUP_ASH) }
        pool.update(1f)
        assertEquals(3, pool.count)
        assertEquals(3, pool.countOf(ParticlePool.GROUP_FIREFLY))
        assertEquals(0, pool.countOf(ParticlePool.GROUP_ASH))
        assertEquals(0, pool.countOf(ParticlePool.GROUP_BURST))
        pool.update(1.5f)
        assertEquals(0, pool.count)
        assertEquals(0, pool.countOf(ParticlePool.GROUP_FIREFLY))
    }

    @Test
    fun gravityAndDragMoveParticles() {
        val pool = ParticlePool(4)
        pool.spawn(0, 0f, 1f, 0f, velocityX = 1f, lifetime = 5f, fall = 9.8f, damping = 1f, size = 0.2f)
        pool.update(0.1f)
        val out = buffer(pool)
        pool.write(out, left, up)
        // The quad centre is the mean of its four corners.
        var cx = 0f
        var cy = 0f
        for (v in 0 until 4) {
            cx += out.getFloat(v * ParticlePool.STRIDE) / 4
            cy += out.getFloat(v * ParticlePool.STRIDE + 4) / 4
        }
        assertTrue("moved along +x", cx > 0.05f && cx < 0.1f)
        assertTrue("fell", cy < 1f)
    }

    @Test
    fun groundDecalsAreWrittenFirstAndLieFlat() {
        val pool = ParticlePool(4)
        pool.spawn(0, 0f, 2f, 0f, lifetime = 5f)
        pool.spawn(0, 1f, 0.01f, 1f, lifetime = 5f, size = 0.4f, flag = ParticlePool.GROUND)
        val out = buffer(pool)
        assertEquals(2, pool.write(out, left, up))
        for (v in 0 until 4) assertEquals(0.01f, out.getFloat(v * ParticlePool.STRIDE + 4), 1e-5f)
        // Rotation 0 points the image top (first vertex row) toward -z.
        assertEquals(0.8f, out.getFloat(8), 1e-4f)
        assertEquals(1.2f, out.getFloat(2 * ParticlePool.STRIDE + 8), 1e-4f)
    }

    @Test
    fun billboardsFaceTheCamera() {
        val pool = ParticlePool(2)
        pool.spawn(0, 0f, 0f, 0f, lifetime = 5f, size = 1f)
        val out = buffer(pool)
        pool.write(out, left, up)
        // Facing a camera looking down -z: corners span x and y at constant z, top-left first.
        assertEquals(-0.5f, out.getFloat(0), 1e-5f)
        assertEquals(0.5f, out.getFloat(4), 1e-5f)
        for (v in 0 until 4) assertEquals(0f, out.getFloat(v * ParticlePool.STRIDE + 8), 1e-5f)
        assertEquals(0.5f, out.getFloat(ParticlePool.STRIDE), 1e-5f)
    }

    @Test
    fun footprintsFadeLinearlyOverTheirLife() {
        val pool = ParticlePool(2)
        pool.spawn(
            0, 0f, 0f, 0f,
            lifetime = 4f, r = 0f, g = 0f, b = 0f, opacity = 0.5f, flag = ParticlePool.GROUND, grow = 0.001f, shrink = 1f,
        )
        val out = buffer(pool)
        pool.update(0.1f)
        pool.write(out, left, up)
        val early = alphaOf(out, 0)
        pool.update(1.9f)
        pool.write(out, left, up)
        val middle = alphaOf(out, 0)
        assertEquals(124, early, 2)
        assertEquals(64, middle, 2)
        // Black: footprints only darken the sand.
        assertEquals(0, out.get(20).toInt())
    }

    @Test
    fun additiveParticlesCarryZeroAlpha() {
        val pool = ParticlePool(2)
        pool.spawn(0, 0f, 0f, 0f, lifetime = 1f, r = 1f, g = 0.5f, b = 0.25f, opacity = 0f, grow = 0.001f, shrink = 0.001f)
        pool.update(0.5f)
        val out = buffer(pool)
        pool.write(out, left, up)
        assertEquals(0, alphaOf(out, 0))
        assertEquals(255, out.get(20).toInt() and 255)
        assertEquals(128, out.get(21).toInt() and 255, 1)
    }

    @Test
    fun densityScalesBurstsOnAverage() {
        val pool = ParticlePool(1)
        assertEquals(10, pool.scaled(10))
        pool.density = 0.2f
        var total = 0
        repeat(10_000) { total += pool.scaled(10) }
        assertTrue("about 2 per burst of 10: $total", abs(total / 10_000f - 2f) < 0.05f)
        var single = 0
        repeat(10_000) { single += pool.scaled(1) }
        assertTrue("single particles still appear: $single", single in 1700..2300)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected +/- $tolerance, was $actual", abs(expected - actual) <= tolerance)
    }
}
