package com.hatchslide.core.render.quality

import com.hatchslide.core.render.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalStepperTest {
    @Test
    fun stepsDownBeforeThrottlingAtMostEveryTenSeconds() {
        val t = ThermalStepper()
        assertEquals(0, t.onSample(0.5f, 0, 0))
        assertEquals(1, t.onSample(0.9f, 0, 2_000))
        assertEquals(1, t.onSample(0.95f, 0, 6_000))
        assertEquals(2, t.onSample(0.95f, 0, 12_000))
        assertEquals(2, t.onSample(0.99f, 0, 30_000))
    }

    @Test
    fun recoversOneStepAfterThirtyCoolSeconds() {
        val t = ThermalStepper()
        t.onSample(0.9f, 0, 0)
        t.onSample(0.9f, 0, 10_000)
        assertEquals(2, t.steps)
        assertEquals(2, t.onSample(0.5f, 0, 12_000))
        assertEquals(2, t.onSample(0.5f, 0, 40_000))
        assertEquals(1, t.onSample(0.5f, 0, 42_000))
        // A warm sample in between restarts the cool timer.
        assertEquals(1, t.onSample(0.7f, 0, 50_000))
        assertEquals(1, t.onSample(0.5f, 0, 52_000))
        assertEquals(1, t.onSample(0.5f, 0, 80_000))
        assertEquals(0, t.onSample(0.5f, 0, 82_000))
    }

    @Test
    fun thermalStatusOverridesMissingHeadroom() {
        val t = ThermalStepper()
        assertEquals(0, t.onSample(Float.NaN, 0, 0))
        assertEquals(1, t.onSample(Float.NaN, 2, 1_000))
        assertEquals(ThermalStepper.MAX_STEPS, t.onSample(Float.NaN, 4, 2_000))
        // Without a headroom reading it never claims to be cool.
        assertEquals(ThermalStepper.MAX_STEPS, t.onSample(Float.NaN, 0, 100_000))
    }

    @Test
    fun loweredTiersStopAtLow() {
        assertEquals(QualityTier.MEDIUM, QualityTier.HIGH.lowered(1))
        assertEquals(QualityTier.LOW, QualityTier.HIGH.lowered(2))
        assertEquals(QualityTier.LOW, QualityTier.MEDIUM.lowered(2))
        assertEquals(QualityTier.HIGH, QualityTier.HIGH.lowered(0))
    }
}
