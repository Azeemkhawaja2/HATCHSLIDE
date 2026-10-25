package com.hatchslide.core.render.camera

import com.hatchslide.core.render.math.PerspectiveProjector
import com.hatchslide.core.render.math.Vec3
import com.hatchslide.core.render.math.easeInOutCubic
import com.hatchslide.core.render.math.lerp
import com.hatchslide.core.render.math.pitchDirection
import kotlin.math.sin

/** Screen-space region the board must fit in: the viewport minus HUD bars and insets, in pixels. */
data class FreeViewport(
    val width: Int,
    val height: Int,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** A camera placement plus the projection shift (NDC) that centres the board in the free region. */
data class CameraPose(
    val eye: Vec3,
    val target: Vec3,
    val shiftX: Float,
    val shiftY: Float,
)

/**
 * Camera for the board (spec section 6): 35 degree vertical FOV, about 55 degrees of pitch, no yaw,
 * so screen-up is always board-up. [frame] finds the closest distance at which the whole board (and
 * the pieces standing on it) fits the free viewport with an 8% margin, then shifts the projection so
 * the board is centred between the HUD bars.
 */
class CameraRig(
    val fovDegrees: Float = 35f,
    val pitchDegrees: Float = 55f,
    val margin: Float = 0.08f,
) {
    private val projector = PerspectiveProjector()
    private val ndc = Vec3()
    private val direction = Vec3()

    /**
     * [halfWidth] and [halfDepth] are the board's half extents in world units (1 unit = 1 cell),
     * centred at the origin on the y = 0 plane; [height] covers pieces and props above the tiles.
     */
    fun frame(halfWidth: Float, halfDepth: Float, height: Float, viewport: FreeViewport): CameraPose {
        val w = viewport.width.coerceAtLeast(1).toFloat()
        val h = viewport.height.coerceAtLeast(1).toFloat()
        val aspect = w / h
        val left = -1f + 2f * viewport.left / w
        val right = 1f - 2f * viewport.right / w
        val bottom = -1f + 2f * viewport.bottom / h
        val top = 1f - 2f * viewport.top / h
        val availableX = (right - left) * (1f - 2f * margin)
        val availableY = (top - bottom) * (1f - 2f * margin)
        val target = Vec3(0f, 0f, 0f)
        pitchDirection(pitchDegrees, direction)

        val corners = floatArrayOf(
            -halfWidth, 0f, -halfDepth, halfWidth, 0f, -halfDepth, -halfWidth, 0f, halfDepth, halfWidth, 0f, halfDepth,
            -halfWidth, height, -halfDepth, halfWidth, height, -halfDepth, -halfWidth, height, halfDepth, halfWidth, height, halfDepth,
        )
        val bounds = FloatArray(4)
        fun measure(distance: Float): Boolean {
            val eye = Vec3(-direction.x * distance, -direction.y * distance, -direction.z * distance)
            projector.configure(eye, target, fovDegrees, aspect)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until 8) {
                if (!projector.project(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2], ndc)) return false
                minX = minOf(minX, ndc.x)
                maxX = maxOf(maxX, ndc.x)
                minY = minOf(minY, ndc.y)
                maxY = maxOf(maxY, ndc.y)
            }
            bounds[0] = minX
            bounds[1] = maxX
            bounds[2] = minY
            bounds[3] = maxY
            return maxX - minX <= availableX && maxY - minY <= availableY
        }

        var near = 0.5f
        var far = 400f
        repeat(40) {
            val mid = (near + far) / 2f
            if (measure(mid)) far = mid else near = mid
        }
        measure(far)
        val eye = Vec3(-direction.x * far, -direction.y * far, -direction.z * far)
        val shiftX = (left + right) / 2f - (bounds[0] + bounds[1]) / 2f
        val shiftY = (bottom + top) / 2f - (bounds[2] + bounds[3]) / 2f
        return CameraPose(eye, target, shiftX, shiftY)
    }
}

/**
 * Animated camera state: idle sway, a two-frame shake when a piece stops hard, the level-start dolly
 * and the win follow. Writes into preallocated vectors; [update] allocates nothing.
 */
class CameraAnimator {
    val eye = Vec3()
    val target = Vec3()
    var shiftX = 0f
        private set
    var shiftY = 0f
        private set

    private val restEye = Vec3()
    private val restTarget = Vec3()
    private val fromEye = Vec3()
    private val fromTarget = Vec3()
    private val focus = Vec3()
    private var time = 0f
    private var dollyStart = -1f
    private var dollyDuration = 1.2f
    private var shakeFrames = 0
    private var shakeStrength = 0f
    private var followStart = -1f
    var reducedMotion = false

    fun setRest(pose: CameraPose) {
        restEye.set(pose.eye)
        restTarget.set(pose.target)
        shiftX = pose.shiftX
        shiftY = pose.shiftY
        if (dollyStart < 0f && followStart < 0f) {
            eye.set(restEye)
            target.set(restTarget)
        }
    }

    /** Level start: 1.2 s dolly from a wide shot over the ocean down to the board. */
    fun startDolly(oceanDistance: Float) {
        fromEye.set(restEye.x, restEye.y + oceanDistance * 0.6f, restEye.z - oceanDistance * 0.4f)
        fromTarget.set(0f, 0f, restTarget.z - oceanDistance)
        dollyStart = time
        followStart = -1f
    }

    /** Win: follow the last saved hatchling into the surf, then tilt toward the horizon. */
    fun follow(x: Float, z: Float) {
        focus.set(x, 0f, z)
        followStart = time
    }

    fun shake(strength: Float = 0.06f) {
        if (reducedMotion) return
        shakeFrames = 2
        shakeStrength = strength
    }

    fun update(dt: Float) {
        time += dt
        eye.set(restEye)
        target.set(restTarget)
        if (dollyStart >= 0f) {
            val t = ((time - dollyStart) / dollyDuration).coerceIn(0f, 1f)
            val e = easeInOutCubic(t)
            lerp(eye, fromEye, restEye, e)
            lerp(target, fromTarget, restTarget, e)
            if (t >= 1f) dollyStart = -1f
        }
        if (followStart >= 0f) {
            val t = ((time - followStart) / 2.2f).coerceIn(0f, 1f)
            val toFocus = easeInOutCubic((t / 0.45f).coerceAtMost(1f))
            val tilt = easeInOutCubic(((t - 0.45f) / 0.55f).coerceIn(0f, 1f))
            target.set(
                lerp(restTarget.x, focus.x, toFocus),
                lerp(restTarget.y, 0f, toFocus) + tilt * 2.5f,
                lerp(restTarget.z, focus.z, toFocus) - tilt * 12f,
            )
            eye.set(lerp(restEye.x, focus.x * 0.5f, toFocus), restEye.y - tilt * restEye.y * 0.45f, restEye.z - toFocus * 2f)
        }
        if (!reducedMotion) {
            // Idle sway of about +/-0.6 degrees, applied as a tiny lateral drift of the eye.
            val distance = restEye.length()
            val sway = 0.0105f * distance
            eye.x += sin(time * 0.35f) * sway
            eye.y += sin(time * 0.27f + 1.3f) * sway * 0.5f
        }
        if (shakeFrames > 0) {
            val sign = if (shakeFrames % 2 == 0) 1f else -1f
            eye.x += sign * shakeStrength
            eye.y += sign * shakeStrength * 0.5f
            shakeFrames--
        }
    }

    val isAnimating: Boolean get() = dollyStart >= 0f || followStart >= 0f || shakeFrames > 0
}
