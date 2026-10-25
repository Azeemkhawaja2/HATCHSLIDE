package com.hatchslide.core.render.fx

import com.hatchslide.core.render.assets.AssetCatalog
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Fixed-size particle state (spec section 6: pooled, never allocating per frame). Particles live
 * packed in `[0, count)` as structure-of-arrays; a dead one is replaced by the last. [write] turns
 * the live particles into quads: ground decals first, so footprints and ripples sit under
 * everything that flies.
 */
class ParticlePool(val capacity: Int) {
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val vz = FloatArray(capacity)
    private val age = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val size0 = FloatArray(capacity)
    private val size1 = FloatArray(capacity)
    private val rotation = FloatArray(capacity)
    private val spin = FloatArray(capacity)
    private val gravity = FloatArray(capacity)
    private val drag = FloatArray(capacity)
    private val red = FloatArray(capacity)
    private val green = FloatArray(capacity)
    private val blue = FloatArray(capacity)
    private val alpha = FloatArray(capacity)
    private val fadeIn = FloatArray(capacity)
    private val fadeOut = FloatArray(capacity)
    private val wobble = FloatArray(capacity)
    private val phase = FloatArray(capacity)
    private val cell = IntArray(capacity)
    private val flags = IntArray(capacity)
    private val group = IntArray(capacity)
    private val groupCounts = IntArray(GROUPS)

    var count = 0
        private set

    /** Fraction of every burst and ambient target that is emitted (0.2 with reduced motion). */
    var density = 1f

    private var seed = 0x2545F491

    /** Uniform random in 0..1 from a xorshift generator (no allocation, deterministic per pool). */
    fun random(): Float {
        var s = seed
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        seed = s
        return (s ushr 8) / 16_777_216f
    }

    fun range(from: Float, to: Float): Float = from + (to - from) * random()

    /** [n] scaled by [density], rounded randomly so small bursts still appear sometimes. */
    fun scaled(n: Int): Int {
        val exact = n * density
        val whole = floor(exact).toInt()
        return whole + if (random() < exact - whole) 1 else 0
    }

    /** Live particles of [group] (ambient emitters top themselves up to a target). */
    fun countOf(group: Int): Int = groupCounts[group]

    /**
     * Adds a particle and returns its index, or -1 when the pool is full. [cell] is an effects atlas
     * cell; colours are linear 0..1; [opacity] 0 makes it additive. Sizes are full quad widths.
     */
    fun spawn(
        cell: Int,
        px: Float,
        py: Float,
        pz: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
        velocityZ: Float = 0f,
        lifetime: Float = 1f,
        size: Float = 0.2f,
        endSize: Float = size,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        opacity: Float = 1f,
        flag: Int = 0,
        fall: Float = 0f,
        damping: Float = 0f,
        angle: Float = 0f,
        turn: Float = 0f,
        grow: Float = 0.05f,
        shrink: Float = 0.3f,
        sway: Float = 0f,
        tag: Int = GROUP_BURST,
    ): Int {
        if (count >= capacity) return -1
        val i = count++
        x[i] = px
        y[i] = py
        z[i] = pz
        vx[i] = velocityX
        vy[i] = velocityY
        vz[i] = velocityZ
        age[i] = 0f
        life[i] = lifetime.coerceAtLeast(0.01f)
        size0[i] = size
        size1[i] = endSize
        rotation[i] = angle
        spin[i] = turn
        gravity[i] = fall
        drag[i] = damping
        red[i] = r
        green[i] = g
        blue[i] = b
        alpha[i] = opacity
        fadeIn[i] = grow.coerceIn(0.001f, 1f)
        fadeOut[i] = shrink.coerceIn(0.001f, 1f)
        wobble[i] = sway
        phase[i] = random() * 6.2831855f
        this.cell[i] = cell
        flags[i] = flag
        group[i] = tag
        groupCounts[tag]++
        return i
    }

    fun clear() {
        count = 0
        groupCounts.fill(0)
    }

    /** Ages, integrates and removes particles. */
    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                kill(i)
                continue
            }
            if (flags[i] and WANDER != 0) {
                vx[i] += (random() - 0.5f) * wobble[i] * dt
                vy[i] += (random() - 0.5f) * wobble[i] * 0.5f * dt
                vz[i] += (random() - 0.5f) * wobble[i] * dt
            }
            vy[i] -= gravity[i] * dt
            if (drag[i] > 0f) {
                val damp = exp(-drag[i] * dt)
                vx[i] *= damp
                vy[i] *= damp
                vz[i] *= damp
            }
            var dx = vx[i]
            var dz = vz[i]
            if (flags[i] and FLUTTER != 0) {
                val s = sin(age[i] * 5f + phase[i]) * wobble[i]
                dx += s
                dz += s * 0.5f
            }
            x[i] += dx * dt
            y[i] += vy[i] * dt
            z[i] += dz * dt
            rotation[i] += spin[i] * dt
            i++
        }
    }

    private fun kill(i: Int) {
        groupCounts[group[i]]--
        val last = --count
        if (i == last) return
        x[i] = x[last]
        y[i] = y[last]
        z[i] = z[last]
        vx[i] = vx[last]
        vy[i] = vy[last]
        vz[i] = vz[last]
        age[i] = age[last]
        life[i] = life[last]
        size0[i] = size0[last]
        size1[i] = size1[last]
        rotation[i] = rotation[last]
        spin[i] = spin[last]
        gravity[i] = gravity[last]
        drag[i] = drag[last]
        red[i] = red[last]
        green[i] = green[last]
        blue[i] = blue[last]
        alpha[i] = alpha[last]
        fadeIn[i] = fadeIn[last]
        fadeOut[i] = fadeOut[last]
        wobble[i] = wobble[last]
        phase[i] = phase[last]
        cell[i] = cell[last]
        flags[i] = flags[last]
        group[i] = group[last]
    }

    /**
     * Writes live particles as quads (4 vertices of [STRIDE] bytes: position, atlas UV, RGBA8) into
     * [out] from byte 0, ground decals first. Billboards face the camera through [left] and [up]
     * (world-space unit vectors). Returns the number of quads written.
     */
    fun write(out: ByteBuffer, left: FloatArray, up: FloatArray): Int {
        var quads = 0
        for (pass in 0..1) {
            for (i in 0 until count) {
                val ground = flags[i] and GROUND != 0
                if (ground != (pass == 0)) continue
                writeQuad(out, quads++, i, ground, left, up)
            }
        }
        return quads
    }

    private fun writeQuad(out: ByteBuffer, quad: Int, i: Int, ground: Boolean, left: FloatArray, up: FloatArray) {
        val t = age[i] / life[i]
        var fade = min(t / fadeIn[i], 1f) * min((1f - t) / fadeOut[i], 1f)
        if (flags[i] and BLINK != 0) fade *= 0.35f + 0.65f * (0.5f + 0.5f * sin(age[i] * 3.1f + phase[i]))
        val half = (size0[i] + (size1[i] - size0[i]) * t) * 0.5f
        val additive = alpha[i] <= 0f
        val a = if (additive) 0f else fade * alpha[i]
        val k = if (additive) fade else a
        val r = toByte(red[i] * k)
        val g = toByte(green[i] * k)
        val b = toByte(blue[i] * k)
        val ab = toByte(a)
        val c = cos(rotation[i])
        val s = sin(rotation[i])
        // Axis along image x (u) and along image up (v = 0 at the top).
        val ux: Float
        val uy: Float
        val uz: Float
        val vx: Float
        val vy: Float
        val vz: Float
        if (ground) {
            // Flat on the ground; rotation 0 points the image top toward -z (board-up).
            ux = c
            uy = 0f
            uz = s
            vx = s
            vy = 0f
            vz = -c
        } else {
            // Camera-facing, rolled by the rotation.
            val rx = -left[0]
            val ry = -left[1]
            val rz = -left[2]
            ux = rx * c + up[0] * s
            uy = ry * c + up[1] * s
            uz = rz * c + up[2] * s
            vx = up[0] * c - rx * s
            vy = up[1] * c - ry * s
            vz = up[2] * c - rz * s
        }
        val index = cell[i]
        val u0 = (index % 4) * 0.25f + INSET
        val v0 = (index / 4) * 0.25f + INSET
        val span = 0.25f - 2 * INSET
        val base = quad * 4 * STRIDE
        // Corners top-left, top-right, bottom-right, bottom-left.
        for (k in 0 until 4) {
            val su = CORNER_U[k] * half
            val sv = CORNER_V[k] * half
            val u = u0 + span * (CORNER_U[k] + 1f) * 0.5f
            val v = v0 + span * (1f - CORNER_V[k]) * 0.5f
            vertex(out, base + k * STRIDE, x[i] + su * ux + sv * vx, y[i] + su * uy + sv * vy, z[i] + su * uz + sv * vz, u, v, r, g, b, ab)
        }
    }

    private fun vertex(out: ByteBuffer, at: Int, px: Float, py: Float, pz: Float, u: Float, v: Float, r: Byte, g: Byte, b: Byte, a: Byte) {
        out.putFloat(at, px)
        out.putFloat(at + 4, py)
        out.putFloat(at + 8, pz)
        out.putFloat(at + 12, u)
        out.putFloat(at + 16, v)
        out.put(at + 20, r)
        out.put(at + 21, g)
        out.put(at + 22, b)
        out.put(at + 23, a)
    }

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    companion object {
        /** Bytes per vertex: position (3 floats), UV (2 floats), colour (4 bytes). */
        const val STRIDE = 24
        private const val INSET = 0.004f
        private val CORNER_U = floatArrayOf(-1f, 1f, 1f, -1f)
        private val CORNER_V = floatArrayOf(1f, 1f, -1f, -1f)

        /** Lies flat on the ground instead of facing the camera. */
        const val GROUND = 1

        /** Brightness pulses (fireflies). */
        const val BLINK = 2

        /** Sways side to side as it falls (confetti, ash). */
        const val FLUTTER = 4

        /** Drifts with random acceleration (fireflies). */
        const val WANDER = 8

        const val GROUP_BURST = 0
        const val GROUP_FIREFLY = 1
        const val GROUP_ASH = 2
        private const val GROUPS = 3

        /** Atlas cell index of an effects atlas cell name. */
        fun cellOf(name: String): Int = AssetCatalog.Textures.FX_CELLS.indexOf(name).also { require(it >= 0) { "no fx cell $name" } }
    }
}
