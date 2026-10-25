package com.hatchslide.app

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec section 12: settings persist, rotation keeps the board, runs resume, the background goes quiet. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LifecycleTest {
    private val ui = GameUi()

    @Before
    fun freshInstall() = ui.resetSaves()

    @After
    fun naturalOrientation() = ui.device.setOrientationNatural()

    @Test
    fun settingsPersistAcrossRestart() {
        ui.launch().use {
            ui.startToMenu()
            ui.tap(By.text("Settings"))
            ui.toggleRow("Reduced motion").click()
        }
        // DataStore writes asynchronously; give it a moment.
        val deadline = System.currentTimeMillis() + 3_000
        while (!ui.container.player.settings.value.reducedMotion && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(ui.container.player.settings.value.reducedMotion)
        ui.launch().use {
            ui.startToMenu()
            ui.tap(By.text("Settings"))
            assertTrue(ui.toggleRow("Reduced motion").isChecked)
        }
    }

    @Test
    fun rotationMidSlideKeepsTheBoardAndUndo() {
        val level = ui.level(GameUi.FIRST_LEVEL)
        val losing = ui.losingMoves(level).first()
        ui.launch().use {
            ui.startToMenu()
            ui.openFirstLevel()
            // Rotate while the hatchling is still sliding.
            ui.press(losing)
            ui.device.setOrientationLeft()
            ui.waitFor(By.textStartsWith("Moves 1 "))
            ui.device.setOrientationNatural()
            ui.waitFor(By.textStartsWith("Moves 1 "))
            ui.tap(By.desc("Undo"))
            ui.waitFor(By.textStartsWith("Moves 0 "))
        }
    }

    @Test
    fun savedRunResumesAfterTheAppCloses() {
        val level = ui.level(GameUi.FIRST_LEVEL)
        ui.launch().use {
            ui.startToMenu()
            ui.openFirstLevel()
            ui.swipe(ui.losingMoves(level).first())
            ui.waitFor(By.textStartsWith("Moves 1 "))
        }
        ui.launch().use {
            ui.startToMenu()
            ui.openFirstLevel()
            ui.waitFor(By.textStartsWith("Moves 1 "))
        }
    }

    @Test
    fun backgroundStopsRenderingAndAudio() {
        ui.launch().use { scenario ->
            ui.waitFor(By.desc("Tap to start"))
            var frames = 0L
            var audible = false
            scenario.onActivity { frames = it.director.engine.presentedFrames }
            Thread.sleep(500)
            scenario.onActivity { assertTrue("renders in the foreground", it.director.engine.presentedFrames > frames) }

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                frames = it.director.engine.presentedFrames
                audible = it.audio.isAudible
            }
            assertFalse("audio stops in the background", audible)
            Thread.sleep(800)
            scenario.onActivity { assertEquals("no frames in the background", frames, it.director.engine.presentedFrames) }

            scenario.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(800)
            scenario.onActivity {
                assertTrue("rendering resumes", it.director.engine.presentedFrames > frames)
                assertTrue("audio resumes", it.audio.isAudible)
            }
        }
    }
}
