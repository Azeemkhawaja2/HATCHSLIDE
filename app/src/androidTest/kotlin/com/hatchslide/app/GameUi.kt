package com.hatchslide.app

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.Settings
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.Simulator
import com.hatchslide.core.game.SolveResult
import com.hatchslide.core.game.Solver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull

/**
 * Drives the real game through its accessibility tree with UiAutomator. Compose test idling is not
 * used: the 3D view renders continuously and the world map tracks island positions every frame.
 */
class GameUi {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    val container get() = (app as HatchslideApp).container

    /** Fresh progress and settings, as on a first install. */
    fun resetSaves() = runBlocking {
        container.player.loaded.first { it }
        container.player.updateProgress { Progress() }
        container.player.updateSettings { Settings() }
        // Let the writes land before the activity reads them.
        Thread.sleep(300)
    }

    fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    fun waitFor(selector: BySelector, timeout: Long = TIMEOUT): UiObject2 {
        val found = device.wait(Until.findObject(selector), timeout)
        assertNotNull("not on screen: $selector", found)
        return found
    }

    fun tap(selector: BySelector) = waitFor(selector).click()

    fun gone(selector: BySelector, timeout: Long = TIMEOUT) = device.wait(Until.gone(selector), timeout)

    fun back() = device.pressBack()

    /** From a cold start to the main menu. */
    fun startToMenu() {
        tap(By.desc("Tap to start"))
        waitFor(By.text("Play"))
    }

    /** From the main menu into Sunrise Cove's level 1, dismissing the first-time tip. */
    fun openFirstLevel() {
        tap(By.text("Play"))
        waitFor(By.text("Islands"))
        tap(By.descStartsWith("Sunrise Cove"))
        tap(By.descStartsWith("Level 1."))
        waitFor(By.desc("Pause"))
        device.wait(Until.findObject(By.text("Got it")), 3_000)?.click()
    }

    /** Sends one swipe as a D-pad press, without waiting for the slide. */
    fun press(direction: Direction) {
        when (direction) {
            Direction.UP -> device.pressDPadUp()
            Direction.DOWN -> device.pressDPadDown()
            Direction.LEFT -> device.pressDPadLeft()
            Direction.RIGHT -> device.pressDPadRight()
        }
    }

    /** Sends one swipe and waits for the slide to finish. */
    fun swipe(direction: Direction) {
        press(direction)
        Thread.sleep(SLIDE_MS)
    }

    /** A settings switch row: one checkable node whose text starts with its label. */
    fun toggleRow(label: String): UiObject2 = waitFor(By.checkable(true).textStartsWith(label))

    fun level(id: String): Level = runBlocking { requireNotNull(container.levels.level(id)) { "no level $id" } }

    /** An optimal solution from the solver. */
    fun solution(level: Level): List<Direction> = (Solver(level).solve() as SolveResult.Solved).path

    /** A deterministic sequence of counted moves that runs out of moves without winning. */
    fun losingMoves(level: Level): List<Direction> {
        val simulator = Simulator(level)
        val random = java.util.Random(7)
        repeat(200) {
            var state = GameState.initial(level)
            val moves = ArrayList<Direction>()
            while (state.status == GameStatus.PLAYING && moves.size < 200) {
                val direction = Direction.entries[random.nextInt(4)]
                val outcome = simulator.apply(state, direction)
                if (outcome.result.noOp || outcome.state.status == GameStatus.WON) continue
                moves += direction
                state = outcome.state
            }
            if (state.status.isLost) return moves
        }
        error("no losing sequence found for ${level.id}")
    }

    companion object {
        const val TIMEOUT = 15_000L
        const val SLIDE_MS = 1_400L
        const val FIRST_LEVEL = "w1_l01"
    }
}
