package com.hatchslide.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gameplay frame timing (spec section 11): 60 fps with under 2% janky frames on the Medium tier, and
 * a steady 30 fps on the Low tier when run on a low-RAM emulator profile.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GameplayBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun slidingHatchlings() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            startToMenu()
            openFirstLevel()
        },
    ) {
        playMoves(rounds = 2)
    }
}
