package com.hatchslide.core.render.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small mutable vector used by camera and animation code; callers reuse instances to avoid per-frame allocation. */
class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(o: Vec3): Vec3 = set(o.x, o.y, o.z)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalize(): Vec3 {
        val l = length()
        if (l > 1e-6f) set(x / l, y / l, z / l)
        return this
    }

    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun lerp(out: Vec3, a: Vec3, b: Vec3, t: Float): Vec3 = out.set(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t))

fun smoothstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

fun easeInOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return if (c < 0.5f) 4f * c * c * c else 1f - (-2f * c + 2f).let { it * it * it } / 2f
}

fun easeOutBack(t: Float, overshoot: Float = 1.70158f): Float {
    val c = t.coerceIn(0f, 1f) - 1f
    return 1f + (overshoot + 1f) * c * c * c + overshoot * c * c
}

fun Float.toRadians(): Float = (this * PI / 180.0).toFloat()

/**
 * Projects world points for a perspective camera (vertical FOV, Y-up, looking from [eye] to [target]).
 * Pure math so framing can be unit-tested on the JVM.
 */
class PerspectiveProjector {
    private val forward = Vec3()
    private val right = Vec3()
    private val up = Vec3()
    private var focalY = 1f
    private var focalX = 1f
    private val eye = Vec3()

    fun configure(eye: Vec3, target: Vec3, fovYDegrees: Float, aspect: Float) {
        this.eye.set(eye)
        forward.set(target.x - eye.x, target.y - eye.y, target.z - eye.z).normalize()
        // right = forward x worldUp, up = right x forward
        right.set(-forward.z, 0f, forward.x).normalize()
        up.set(
            right.y * forward.z - right.z * forward.y,
            right.z * forward.x - right.x * forward.z,
            right.x * forward.y - right.y * forward.x,
        )
        focalY = 1f / tan(fovYDegrees.toRadians() / 2f)
        focalX = focalY / aspect
    }

    /** Writes NDC x, y of the world point into [out] (z = view depth). Returns false if behind the camera. */
    fun project(px: Float, py: Float, pz: Float, out: Vec3): Boolean {
        val dx = px - eye.x
        val dy = py - eye.y
        val dz = pz - eye.z
        val depth = dx * forward.x + dy * forward.y + dz * forward.z
        if (depth <= 1e-4f) return false
        val vx = dx * right.x + dy * right.y + dz * right.z
        val vy = dx * up.x + dy * up.y + dz * up.z
        out.set(vx * focalX / depth, vy * focalY / depth, depth)
        return true
    }
}

/** Direction from a pitch angle below the horizon (degrees), facing -Z (toward the far side of the board). */
fun pitchDirection(pitchDegrees: Float, out: Vec3): Vec3 {
    val p = pitchDegrees.toRadians()
    return out.set(0f, -sin(p), -cos(p))
}
