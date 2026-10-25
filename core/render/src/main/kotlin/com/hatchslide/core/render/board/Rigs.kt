package com.hatchslide.core.render.board

import com.google.android.filament.TransformManager
import com.hatchslide.core.render.assets.ModelInstance
import com.hatchslide.core.render.math.Transforms
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAU = (2 * PI).toFloat()

/** A model node with its rest translation, animated by rotating in place. */
private class Joint(private val tm: TransformManager, val entity: Int) {
    private val instance = tm.getInstance(entity)
    val x: Float
    val y: Float
    val z: Float

    init {
        val m = FloatArray(16)
        tm.getTransform(instance, m)
        x = m[12]
        y = m[13]
        z = m[14]
    }

    fun pose(
        scratch: FloatArray,
        yaw: Float,
        pitch: Float,
        roll: Float,
        dx: Float = 0f,
        dy: Float = 0f,
        dz: Float = 0f,
        scale: Float = 1f,
    ) {
        tm.setTransform(instance, Transforms.trsEuler(scratch, x + dx, y + dy, z + dz, yaw, pitch, roll, scale))
    }
}

/**
 * Procedural hatchling animation (spec section 6): flippers paddle at the slide's 2.5 Hz rhythm, the
 * head leads turns, the tail wags, and the eyelids blink every 2 to 5 seconds. Lean and squash are
 * applied by the board on the model root.
 */
class HatchlingRig(tm: TransformManager, model: ModelInstance, seed: Int) {
    private val scratch = FloatArray(16)
    private val head = Joint(tm, model.node("head"))
    private val tail = Joint(tm, model.node("tail"))
    private val flippers = arrayOf("flipper_fl", "flipper_fr", "flipper_bl", "flipper_br").map { Joint(tm, model.node(it)) }
    private val lids = arrayOf(Joint(tm, model.node("lid_l")), Joint(tm, model.node("lid_r")))
    private var random = seed * 1103515245 + 12345
    private var untilBlink = nextBlink()
    private var blink = -1f

    private fun nextRandom(): Float {
        random = random * 1103515245 + 12345
        return ((random ushr 8) and 0xFFFF) / 65535f
    }

    private fun nextBlink() = 2f + 3f * nextRandom()

    /**
     * [paddle] is the 0..1 stroke phase, [effort] 0 (resting) to 1 (sliding), [headYaw] the head's
     * turn relative to the body, [time] a running clock for idle motion.
     */
    fun animate(dt: Float, paddle: Float, effort: Float, headYaw: Float, time: Float, reducedMotion: Boolean) {
        val idle = if (reducedMotion) 0f else 0.12f
        val stroke = paddle * TAU
        val amount = maxOf(effort, idle)
        val phase = if (effort > 0f) stroke else time * 1.7f
        val sweep = sin(phase) * 0.55f * amount
        val lift = cos(phase) * 0.35f * amount
        flippers[0].pose(scratch, sweep, 0f, -lift)
        flippers[1].pose(scratch, -sweep, 0f, lift)
        flippers[2].pose(scratch, -sweep * 0.5f, 0f, lift * 0.5f)
        flippers[3].pose(scratch, sweep * 0.5f, 0f, -lift * 0.5f)
        val bob = if (reducedMotion) 0f else sin(time * 2.1f) * 0.04f
        head.pose(scratch, headYaw, bob - 0.06f * effort, 0f)
        tail.pose(scratch, sin(time * 3.2f) * (0.12f + 0.25f * effort), 0f, 0f)

        // Blink: close and reopen over 0.16 s.
        if (blink < 0f) {
            untilBlink -= dt
            if (untilBlink <= 0f) blink = 0f
        } else {
            blink += dt / 0.16f
            if (blink >= 1f) {
                blink = -1f
                untilBlink = nextBlink()
            }
        }
        val closed = if (blink < 0f) 0f else sin(blink * PI.toFloat())
        val angle = LID_OPEN + (LID_CLOSED - LID_OPEN) * closed
        for (lid in lids) lid.pose(scratch, 0f, angle, 0f)
    }

    companion object {
        /** Eyelid hinge angles (tools/assetgen/models/library.py LID_OPEN and LID_CLOSED). */
        const val LID_OPEN = 1.35f
        const val LID_CLOSED = -1.5f
    }
}

/** Crab claws snip and pincers open and close. */
class CrabRig(tm: TransformManager, model: ModelInstance) {
    private val scratch = FloatArray(16)
    private val claws = arrayOf(Joint(tm, model.node("claw_l")), Joint(tm, model.node("claw_r")))
    private val pincers = arrayOf(Joint(tm, model.node("pincer_l")), Joint(tm, model.node("pincer_r")))

    /** [snip] 0..1 claw raise, [open] 0..1 pincer gape. */
    fun animate(snip: Float, open: Float) {
        claws[0].pose(scratch, 0.25f * snip, 0.2f * snip, 0f)
        claws[1].pose(scratch, -0.25f * snip, 0.2f * snip, 0f)
        pincers[0].pose(scratch, 0f, 0.45f * open, 0f)
        pincers[1].pose(scratch, 0f, 0.45f * open, 0f)
    }
}

/** Seagull flight: bursts of flapping between long glides. */
class GullRig(tm: TransformManager, model: ModelInstance) {
    private val scratch = FloatArray(16)
    private val wings = arrayOf(Joint(tm, model.node("wing_l")), Joint(tm, model.node("wing_r")))

    fun animate(time: Float) {
        val cycle = time % 4f
        val angle = if (cycle < 1.2f) {
            sin(cycle / 1.2f * 3f * TAU) * 0.7f * sin(cycle / 1.2f * PI.toFloat())
        } else {
            0.12f + 0.04f * sin(time * 1.3f)
        }
        wings[0].pose(scratch, 0f, 0f, -angle)
        wings[1].pose(scratch, 0f, 0f, angle)
    }
}

/** Egg shell: the top pops off and tumbles away when the hatchling breaks out. */
class EggRig(tm: TransformManager, model: ModelInstance) {
    private val scratch = FloatArray(16)
    private val top = Joint(tm, model.node("egg_top"))

    /** [crack] 0 (whole) to 1 (top flown off and shrunk away). */
    fun animate(crack: Float) {
        val c = crack.coerceIn(0f, 1f)
        top.pose(scratch, c * 1.5f, -c * 1.2f, c * 0.6f, dx = c * 0.25f, dy = c * 0.45f - c * c * 0.25f, scale = 1f - c * 0.9f)
    }
}
