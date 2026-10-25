package com.hatchslide.core.render.quality

import com.hatchslide.core.render.QualityTier
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What Android's Game Mode asks of the game (API 31+; STANDARD otherwise). */
enum class GameModeHint { STANDARD, PERFORMANCE, BATTERY }

/**
 * First-launch tier choice (spec section 6, auto tier): from the GPU renderer string and the
 * device's memory. Unknown GPUs get Medium; the frame monitor drops it if the device cannot keep up.
 */
object AutoQuality {
    private const val GIB = 1024L * 1024L * 1024L

    fun choose(renderer: String, totalRamBytes: Long, lowRamDevice: Boolean): QualityTier {
        val gpu = gpuTier(renderer.lowercase())
        return when {
            // Phones sold with 3 GB or less report under 2.6 GiB.
            lowRamDevice || totalRamBytes in 1 until (26 * GIB / 10) -> QualityTier.LOW

            // 4 GB phones: never High.
            totalRamBytes in 1 until 5 * GIB -> minOf(gpu, QualityTier.MEDIUM)

            else -> gpu
        }
    }

    /** Game Mode moves the chosen tier one step: Performance up, Battery down. */
    fun adjust(base: QualityTier, mode: GameModeHint): QualityTier = when (mode) {
        GameModeHint.STANDARD -> base
        GameModeHint.PERFORMANCE -> QualityTier.entries[min(base.ordinal + 1, QualityTier.entries.size - 1)]
        GameModeHint.BATTERY -> QualityTier.entries[max(base.ordinal - 1, 0)]
    }

    /** One tier lower, or null at Low. */
    fun lower(tier: QualityTier): QualityTier? = if (tier.ordinal == 0) null else QualityTier.entries[tier.ordinal - 1]

    private fun number(text: String, after: String): Int? {
        val start = text.indexOf(after)
        if (start < 0) return null
        val digits = text.substring(start + after.length).dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    internal fun gpuTier(r: String): QualityTier = when {
        "adreno" in r -> adreno(number(r, "adreno") ?: 0)

        "immortalis" in r || "xclipse" in r -> QualityTier.HIGH

        "mali-g" in r -> maliG(number(r, "mali-g") ?: 0)

        "mali" in r -> QualityTier.LOW

        "powervr" in r -> when {
            "dxt" in r -> QualityTier.HIGH
            "bxm" in r || "cxt" in r -> QualityTier.MEDIUM
            else -> QualityTier.LOW
        }

        "maleoon" in r -> QualityTier.MEDIUM

        else -> QualityTier.MEDIUM
    }

    private fun adreno(n: Int): QualityTier = when {
        n >= 730 -> QualityTier.HIGH
        n >= 700 -> QualityTier.MEDIUM
        n >= 640 -> QualityTier.HIGH
        n >= 616 -> QualityTier.MEDIUM
        n >= 600 -> QualityTier.LOW
        n >= 540 -> QualityTier.MEDIUM
        else -> QualityTier.LOW
    }

    private fun maliG(n: Int): QualityTier = when {
        // Three-digit names (2021 on): G710 and later flagship, G510 to G6xx mid-range, G310 entry.
        n >= 700 -> QualityTier.HIGH

        n >= 500 -> QualityTier.MEDIUM

        n >= 100 -> QualityTier.LOW

        // Two-digit names: G78 flagship, G57 to G77 mid-range, G31 to G52 entry.
        n >= 78 -> QualityTier.HIGH

        n >= 57 -> QualityTier.MEDIUM

        else -> QualityTier.LOW
    }
}

/**
 * Watches presented-frame intervals (spec section 6): when the 95th percentile of a one-second window
 * exceeds the frame budget by 20% for [sustainedWindows] windows in a row, [onFrame] returns true once
 * and the monitor waits [cooldownNanos] before judging again. Single hitches longer than half a second
 * (loading, resuming) are ignored. Allocation-free.
 */
class FrameBudgetMonitor(
    private val windowNanos: Long = 1_000_000_000L,
    private val sustainedWindows: Int = 5,
    private val cooldownNanos: Long = 8_000_000_000L,
) {
    private val samples = LongArray(512)
    private var n = 0
    private var elapsed = 0L
    private var bad = 0
    private var holdNanos = 0L

    /** Ignores frames for [seconds] (after a stage change or tier switch). */
    fun hold(seconds: Float) {
        holdNanos = max(holdNanos, (seconds * 1e9f).toLong())
        n = 0
        elapsed = 0
        bad = 0
    }

    fun onFrame(intervalNanos: Long, budgetNanos: Long): Boolean {
        if (intervalNanos <= 0L || budgetNanos <= 0L) return false
        if (holdNanos > 0L) {
            holdNanos -= intervalNanos
            return false
        }
        if (intervalNanos > HITCH_NANOS) return false
        if (n < samples.size) samples[n++] = intervalNanos
        elapsed += intervalNanos
        if (elapsed < windowNanos) return false
        Arrays.sort(samples, 0, n)
        val p95 = samples[(ceil(n * 0.95).toInt() - 1).coerceIn(0, n - 1)]
        n = 0
        elapsed = 0
        bad = if (p95 * 10 > budgetNanos * 12) bad + 1 else 0
        if (bad < sustainedWindows) return false
        bad = 0
        holdNanos = cooldownNanos
        return true
    }

    private companion object {
        const val HITCH_NANOS = 500_000_000L
    }
}

/** This tier lowered by [steps] (thermal stepping), never below Low. */
fun QualityTier.lowered(steps: Int): QualityTier = QualityTier.entries[(ordinal - steps).coerceIn(0, ordinal)]

/**
 * Thermal stepping (spec section 11): steps quality down before the device throttles and back up
 * once it has cooled. [onSample] takes the thermal headroom forecast (1.0 = throttling starts; NaN
 * when unavailable) and the current thermal status (PowerManager.THERMAL_STATUS_*, 0 = none) and
 * returns how many tiers to step down (0 to [MAX_STEPS]).
 */
class ThermalStepper {
    var steps = 0
        private set
    private var lastChange = Long.MIN_VALUE / 2
    private var coolSince = -1L

    fun onSample(headroom: Float, status: Int, nowMillis: Long): Int {
        if (status >= STATUS_SEVERE) {
            change(MAX_STEPS, nowMillis)
            return steps
        }
        val hot = status >= STATUS_MODERATE || (!headroom.isNaN() && headroom >= HOT)
        val cool = status <= STATUS_LIGHT && !headroom.isNaN() && headroom < COOL
        when {
            hot -> {
                coolSince = -1L
                if (steps < MAX_STEPS && nowMillis - lastChange >= STEP_DOWN_GAP_MS) change(steps + 1, nowMillis)
            }

            cool && steps > 0 -> {
                if (coolSince < 0L) coolSince = nowMillis
                if (nowMillis - coolSince >= RECOVER_MS) {
                    change(steps - 1, nowMillis)
                    coolSince = nowMillis
                }
            }

            else -> coolSince = -1L
        }
        return steps
    }

    private fun change(to: Int, nowMillis: Long) {
        if (to == steps) return
        steps = to
        lastChange = nowMillis
    }

    companion object {
        const val MAX_STEPS = 2
        private const val HOT = 0.85f
        private const val COOL = 0.6f
        private const val STEP_DOWN_GAP_MS = 10_000L
        private const val RECOVER_MS = 30_000L
        private const val STATUS_LIGHT = 1
        private const val STATUS_MODERATE = 2
        private const val STATUS_SEVERE = 3
    }
}
