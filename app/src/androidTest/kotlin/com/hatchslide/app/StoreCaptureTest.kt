package com.hatchslide.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.hatchslide.core.data.LevelRecord
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.game.Direction
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PhotoMode for the store listing (spec section 13), debug builds only. Skipped unless run with
 * `-e store phone|tablet7|tablet10|feature` (see tools/store/capture.sh). It sizes the display, opens
 * real scenes of the game, holds the scene still (`timeScale` 0, which also freezes particles) and
 * saves screenshots to `<external files>/store/<set>/`. The feature graphic hides the UI.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StoreCaptureTest {
    private val ui = GameUi()
    private val set: String? = InstrumentationRegistry.getArguments().getString("store")
    private val out by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "store/$set").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Before
    fun setUp() {
        assumeTrue("store capture runs only on request", set != null)
        val (size, density) = when (set) {
            "phone" -> "1080x1920" to 420
            "tablet7" -> "1920x1200" to 240
            "tablet10" -> "2560x1600" to 320
            else -> "2048x1000" to 320
        }
        ui.device.executeShellCommand("wm size $size")
        ui.device.executeShellCommand("wm density $density")
        ui.resetSaves()
        unlockForShowcase()
    }

    @After
    fun tearDown() {
        if (set == null) return
        runOnMain { CaptureHooks.uiHidden.value = false }
        ui.device.executeShellCommand("wm size reset")
        ui.device.executeShellCommand("wm density reset")
    }

    /** Worlds 1 to 4 completed with stars, so every island and five species are open; no tutorial or tips. */
    private fun unlockForShowcase() = runBlocking {
        val records = buildMap {
            for (world in 1..4) {
                for (index in 1..ProgressRules.LEVELS_PER_WORLD) {
                    put(ProgressRules.levelId(world, index), LevelRecord(stars = if (index % 4 == 0) 2 else 3, bestMoves = 4, wins = 1))
                }
            }
        }
        ui.container.player.updateProgress { Progress(levels = records, tutorialMoves = 3, skin = 0) }
        Thread.sleep(300)
    }

    private fun runOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Holds the scene still, captures it, then lets it move again. */
    private fun shot(scenario: ActivityScenario<MainActivity>, name: String, settle: Long = 2_500) {
        Thread.sleep(settle)
        scenario.onActivity { it.director.engine.timeScale = 0f }
        Thread.sleep(400)
        ui.device.takeScreenshot(File(out, "$name.png"))
        scenario.onActivity { it.director.engine.timeScale = 1f }
    }

    private fun openLevel(island: String, index: Int) {
        ui.tap(By.text("Play"))
        ui.waitFor(By.text("Islands"))
        ui.tap(By.descStartsWith(island))
        ui.tap(By.descStartsWith("Level $index."))
        ui.waitFor(By.desc("Pause"))
        ui.device.wait(androidx.test.uiautomator.Until.findObject(By.text("Got it")), 3_000)?.click()
    }

    private fun toMenu() {
        repeat(4) {
            if (ui.device.hasObject(By.text("Daily Tide"))) return
            ui.back()
            ui.device.wait(androidx.test.uiautomator.Until.findObject(By.text("Home")), 1_500)?.click()
            Thread.sleep(800)
        }
    }

    @Test
    fun capture() {
        ui.launch().use { scenario ->
            when (set) {
                "feature" -> {
                    ui.waitFor(By.desc("Tap to start"))
                    runOnMain { CaptureHooks.uiHidden.value = true }
                    shot(scenario, "feature_raw", settle = 4_500)
                }

                "phone" -> {
                    ui.waitFor(By.desc("Tap to start"))
                    shot(scenario, "01_title", settle = 4_500)
                    ui.startToMenu()
                    openLevel("Sunrise Cove", 7)
                    ui.swipe(Direction.UP)
                    shot(scenario, "02_sunrise_cove", settle = 300)
                    toMenu()
                    openLevel("Coral Coast", 9)
                    shot(scenario, "03_coral_coast")
                    toMenu()
                    openLevel("Moonlit Bay", 12)
                    shot(scenario, "04_moonlit_bay")
                    toMenu()
                    openLevel("Crab Reef", 8)
                    shot(scenario, "05_crab_reef")
                    toMenu()
                    openLevel("Volcanic Isle", 3)
                    shot(scenario, "06_volcanic_isle")
                    toMenu()
                    ui.tap(By.text("Play"))
                    shot(scenario, "07_islands", settle = 3_500)
                    ui.back()
                    ui.tap(By.text("Turtle Journal"))
                    shot(scenario, "08_journal")
                }

                else -> {
                    ui.startToMenu()
                    shot(scenario, "01_menu")
                    ui.tap(By.text("Play"))
                    shot(scenario, "02_islands", settle = 3_500)
                    ui.back()
                    openLevel("Coral Coast", 14)
                    shot(scenario, "03_coral_coast")
                    toMenu()
                    openLevel("Moonlit Bay", 5)
                    shot(scenario, "04_moonlit_bay")
                }
            }
        }
    }
}
