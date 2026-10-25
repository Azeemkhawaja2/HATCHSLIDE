package com.hatchslide.core.render.quality

import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.QualityTier.HIGH
import com.hatchslide.core.render.QualityTier.LOW
import com.hatchslide.core.render.QualityTier.MEDIUM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoQualityTest {
    private val gib = 1024L * 1024L * 1024L

    @Test
    fun gpuRendererStringsMapToTiers() {
        val cases = mapOf(
            "Adreno (TM) 830" to HIGH,
            "Adreno (TM) 740" to HIGH,
            "Adreno (TM) 710" to MEDIUM,
            "Adreno (TM) 660" to HIGH,
            "Adreno (TM) 619" to MEDIUM,
            "Adreno (TM) 610" to LOW,
            "Adreno (TM) 540" to MEDIUM,
            "Adreno (TM) 506" to LOW,
            "Mali-G78 MP20" to HIGH,
            "Mali-G715-Immortalis MC11" to HIGH,
            "Mali-G710 MC10" to HIGH,
            "Mali-G615 MC2" to MEDIUM,
            "Mali-G310" to LOW,
            "Mali-G77 MC9" to MEDIUM,
            "Mali-G57 MC2" to MEDIUM,
            "Mali-G52 MC2" to LOW,
            "Mali-T830" to LOW,
            "Samsung Xclipse 940" to HIGH,
            "PowerVR Rogue GE8320" to LOW,
            "PowerVR B-Series BXM-8-256" to MEDIUM,
            "Something New 9000" to MEDIUM,
            "" to MEDIUM,
        )
        for ((renderer, tier) in cases) assertEquals(renderer, tier, AutoQuality.gpuTier(renderer.lowercase()))
    }

    @Test
    fun memoryCapsTheGpuTier() {
        assertEquals(HIGH, AutoQuality.choose("Adreno (TM) 740", 11 * gib, lowRamDevice = false))
        assertEquals(MEDIUM, AutoQuality.choose("Adreno (TM) 740", 37 * gib / 10, lowRamDevice = false))
        assertEquals(LOW, AutoQuality.choose("Adreno (TM) 740", 27 * gib / 10 - gib / 5, lowRamDevice = false))
        assertEquals(LOW, AutoQuality.choose("Adreno (TM) 740", 8 * gib, lowRamDevice = true))
        // Unknown memory (0) leaves the GPU's tier.
        assertEquals(HIGH, AutoQuality.choose("Adreno (TM) 740", 0, lowRamDevice = false))
    }

    @Test
    fun gameModeShiftsOneTierWithinRange() {
        assertEquals(HIGH, AutoQuality.adjust(MEDIUM, GameModeHint.PERFORMANCE))
        assertEquals(HIGH, AutoQuality.adjust(HIGH, GameModeHint.PERFORMANCE))
        assertEquals(LOW, AutoQuality.adjust(MEDIUM, GameModeHint.BATTERY))
        assertEquals(LOW, AutoQuality.adjust(LOW, GameModeHint.BATTERY))
        assertEquals(MEDIUM, AutoQuality.adjust(MEDIUM, GameModeHint.STANDARD))
        assertEquals(MEDIUM, AutoQuality.lower(HIGH))
        assertNull(AutoQuality.lower(LOW))
    }

    private val budget = 16_666_667L

    /** Feeds [seconds] of frames at 60 fps where every [slowEvery]th frame takes [slow] nanoseconds. */
    private fun FrameBudgetMonitor.run(seconds: Int, slowEvery: Int, slow: Long): Int {
        var drops = 0
        repeat(seconds * 60) { i -> if (onFrame(if (slowEvery > 0 && i % slowEvery == 0) slow else budget, budget)) drops++ }
        return drops
    }

    @Test
    fun steadyFramesNeverDrop() {
        assertEquals(0, FrameBudgetMonitor().run(seconds = 60, slowEvery = 0, slow = 0))
        // Under 5% of frames late: the 95th percentile stays on budget.
        assertEquals(0, FrameBudgetMonitor().run(seconds = 60, slowEvery = 25, slow = 2 * budget))
    }

    @Test
    fun sustainedOverrunDropsOnceAfterFiveSeconds() {
        val monitor = FrameBudgetMonitor()
        // One late frame in ten: the 95th percentile is two frames long.
        assertEquals(0, monitor.run(seconds = 4, slowEvery = 10, slow = 2 * budget))
        assertTrue(monitor.run(seconds = 2, slowEvery = 10, slow = 2 * budget) == 1)
        // Then it waits out the cooldown before judging again.
        assertEquals(0, monitor.run(seconds = 8, slowEvery = 10, slow = 2 * budget))
    }

    @Test
    fun goodSecondsResetTheCount() {
        val monitor = FrameBudgetMonitor()
        // Windows are one second of measured time, so slow stretches never line up with them exactly:
        // 3 slow seconds make at most 4 bad windows, and 2 good seconds always contain a good one.
        var drops = 0
        repeat(6) {
            drops += monitor.run(seconds = 3, slowEvery = 10, slow = 2 * budget)
            drops += monitor.run(seconds = 2, slowEvery = 0, slow = 0)
        }
        assertEquals(0, drops)
    }

    @Test
    fun hitchesAndHoldsAreIgnored() {
        val monitor = FrameBudgetMonitor()
        assertFalse((0 until 100).any { monitor.onFrame(800_000_000L, budget) })
        monitor.hold(3f)
        assertEquals(0, monitor.run(seconds = 3, slowEvery = 1, slow = 2 * budget))
        assertEquals(1, monitor.run(seconds = 6, slowEvery = 1, slow = 2 * budget))
    }

    @Test
    fun tiersAreOrderedLowToHigh() {
        assertTrue(QualityTier.LOW < QualityTier.MEDIUM && QualityTier.MEDIUM < QualityTier.HIGH)
    }
}
