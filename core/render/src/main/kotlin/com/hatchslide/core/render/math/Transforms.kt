package com.hatchslide.core.render.math

import kotlin.math.cos
import kotlin.math.sin

/** Column-major 4x4 transform helpers writing into caller-owned arrays (no per-frame allocation). */
object Transforms {
    /**
     * Translation * rotationY(yaw) * scale. A yaw of 0 keeps a model's -Z facing; positive yaw turns
     * counter-clockwise seen from above.
     */
    fun trs(out: FloatArray, tx: Float, ty: Float, tz: Float, yaw: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        val c = cos(yaw)
        val s = sin(yaw)
        out[0] = c * sx
        out[1] = 0f
        out[2] = -s * sx
        out[3] = 0f
        out[4] = 0f
        out[5] = sy
        out[6] = 0f
        out[7] = 0f
        out[8] = s * sz
        out[9] = 0f
        out[10] = c * sz
        out[11] = 0f
        out[12] = tx
        out[13] = ty
        out[14] = tz
        out[15] = 1f
        return out
    }

    /** Translation * rotationY(yaw) * rotationX(pitch) * rotationZ(roll) * uniform scale. */
    fun trsEuler(out: FloatArray, tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, roll: Float, scale: Float): FloatArray {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cr = cos(roll)
        val sr = sin(roll)
        // R = Ry * Rx * Rz, written column by column.
        out[0] = (cy * cr + sy * sp * sr) * scale
        out[1] = (cp * sr) * scale
        out[2] = (-sy * cr + cy * sp * sr) * scale
        out[3] = 0f
        out[4] = (-cy * sr + sy * sp * cr) * scale
        out[5] = (cp * cr) * scale
        out[6] = (sy * sr + cy * sp * cr) * scale
        out[7] = 0f
        out[8] = (sy * cp) * scale
        out[9] = (-sp) * scale
        out[10] = (cy * cp) * scale
        out[11] = 0f
        out[12] = tx
        out[13] = ty
        out[14] = tz
        out[15] = 1f
        return out
    }

    /** Translation * rotationY(yaw) * rotationX(pitch) * scale(sx, sy, sz): a leaning, squashable body. */
    fun trsLean(out: FloatArray, tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        val cy = cos(yaw)
        val sy0 = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        out[0] = cy * sx
        out[1] = 0f
        out[2] = -sy0 * sx
        out[3] = 0f
        out[4] = sy0 * sp * sy
        out[5] = cp * sy
        out[6] = cy * sp * sy
        out[7] = 0f
        out[8] = sy0 * cp * sz
        out[9] = -sp * sz
        out[10] = cy * cp * sz
        out[11] = 0f
        out[12] = tx
        out[13] = ty
        out[14] = tz
        out[15] = 1f
        return out
    }

    fun identity(out: FloatArray): FloatArray = trs(out, 0f, 0f, 0f, 0f, 1f, 1f, 1f)
}
