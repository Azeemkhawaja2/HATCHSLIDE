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

    /** 0 out, 1 head, flippers and tail tucked into the shell (the gentle lose pose). */
    var duck = 0f

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
        val sweep = sin(phase) * 0.55f * amount * (1f - duck)
        val lift = cos(phase) * 0.35f * amount * (1f - duck)
        val tuck = 1f - 0.75f * duck
        flippers[0].tucked(sweep, -lift, tuck)
        flippers[1].tucked(-sweep, lift, tuck)
        flippers[2].tucked(-sweep * 0.5f, lift * 0.5f, tuck)
        flippers[3].tucked(sweep * 0.5f, -lift * 0.5f, tuck)
        val bob = if (reducedMotion) 0f else sin(time * 2.1f) * 0.04f * (1f - duck)
        head.pose(scratch, headYaw * (1f - duck), bob - 0.06f * effort, 0f, dy = -0.05f * duck, dz = -head.z * 0.55f * duck, scale = tuck)
        tail.pose(scratch, sin(time * 3.2f) * (0.12f + 0.25f * effort) * (1f - duck), 0f, 0f, dz = -tail.z * 0.5f * duck, scale = tuck)

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
        val closed = maxOf(duck, if (blink < 0f) 0f else sin(blink * PI.toFloat()))
        val angle = LID_OPEN + (LID_CLOSED - LID_OPEN) * closed
        for (lid in lids) lid.pose(scratch, 0f, angle, 0f)
    }

    /** Poses a flipper pulled toward the body centre by [tuck] (1 = rest position). */
    private fun Joint.tucked(yaw: Float, roll: Float, tuck: Float) =
        pose(scratch, yaw, 0f, roll, dx = -x * (1f - tuck) * 0.6f, dz = -z * (1f - tuck) * 0.6f, scale = tuck)

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

    /** Flight cycle; [perched] folds the wings back along the body with an occasional ruffle. */
    fun animate(time: Float, perched: Boolean = false) {
        if (perched) {
            val ruffle = if (time % 3.5f < 0.4f) sin(time % 3.5f / 0.4f * PI.toFloat()) * 0.5f else 0f
            wings[0].pose(scratch, 1.3f - ruffle, 0f, -0.15f - ruffle)
            wings[1].pose(scratch, -1.3f + ruffle, 0f, 0.15f + ruffle)
            return
        }
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
