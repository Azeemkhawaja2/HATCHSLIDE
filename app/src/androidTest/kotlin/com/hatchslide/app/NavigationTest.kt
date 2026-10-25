package com.hatchslide.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Every screen of spec section 5 opens and closes through the real UI. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationTest {
    private val ui = GameUi()

    @Before
    fun freshInstall() = ui.resetSaves()

    @Test
    fun menuScreensOpenAndGoBack() {
        ui.launch().use {
            ui.startToMenu()
            ui.tap(By.text("Settings"))
            ui.toggleRow("Reduced motion")
            ui.tap(By.text("Credits and licences"))
            ui.waitFor(By.text("Fonts"))
            ui.back()
            ui.tap(By.text("Privacy policy"))
            ui.waitFor(By.textContains("does not collect"))
            ui.back()
            ui.back()
            ui.tap(By.text("Achievements"))
            ui.waitFor(By.text("Achievements"))
            ui.back()
            ui.tap(By.text("Turtle Journal"))
            ui.waitFor(By.textContains("Green"))
            ui.back()
            ui.tap(By.text("Daily Tide"))
            ui.waitFor(By.text("Play"))
            ui.back()
            ui.waitFor(By.text("Play"))
        }
    }

    @Test
    fun levelCanBeSolvedAndShowsTheCompleteScreen() {
        val level = ui.level(GameUi.FIRST_LEVEL)
        ui.launch().use {
            ui.startToMenu()
            ui.openFirstLevel()
            // Pause overlay, then back to the board.
            ui.back()
            ui.tap(By.text("Resume"))
            for (direction in ui.solution(level)) ui.swipe(direction)
            ui.waitFor(By.text("All safe in the surf!"))
            ui.tap(By.text("Levels"))
            ui.waitFor(By.descStartsWith("Level 2."))
        }
    }

    @Test
    fun runningOutOfMovesShowsTheGentleFailScreen() {
        val level = ui.level(GameUi.FIRST_LEVEL)
        ui.launch().use {
            ui.startToMenu()
            ui.openFirstLevel()
            for (direction in ui.losingMoves(level)) ui.swipe(direction)
            ui.waitFor(By.text("The seagull swooped in!"))
            ui.tap(By.text("Retry"))
            ui.waitFor(By.desc("Pause"))
        }
    }
}
