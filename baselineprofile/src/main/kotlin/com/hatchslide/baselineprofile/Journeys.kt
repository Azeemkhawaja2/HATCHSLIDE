package com.hatchslide.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

/** Application id of the game. */
const val PACKAGE = "com.hatchslide.game"

private const val WAIT_MS = 10_000L

private fun MacrobenchmarkScope.tap(selector: BySelector) {
    check(device.wait(Until.hasObject(selector), WAIT_MS)) { "not on screen: $selector" }
    device.findObject(selector).click()
}

/** Cold start to the interactive title, then into the main menu. */
fun MacrobenchmarkScope.startToMenu() {
    tap(By.desc("Tap to start"))
    check(device.wait(Until.hasObject(By.text("Play")), WAIT_MS)) { "main menu did not open" }
}

/** Main menu, the world map, Sunrise Cove's level select, then level 1. */
fun MacrobenchmarkScope.openFirstLevel() {
    tap(By.text("Play"))
    tap(By.descStartsWith("Sunrise Cove"))
    tap(By.descStartsWith("Level 1."))
    check(device.wait(Until.hasObject(By.desc("Pause")), WAIT_MS)) { "level did not open" }
    // A first-time tip blocks swipes until dismissed.
    device.findObject(By.text("Got it"))?.click()
}

/** Swipes across the board in every direction, letting each slide finish. */
fun MacrobenchmarkScope.playMoves(rounds: Int = 2) {
    val cx = device.displayWidth / 2
    val cy = device.displayHeight / 2
    val dx = device.displayWidth / 4
    val dy = device.displayHeight / 6
    repeat(rounds) {
        for ((x, y) in listOf(-dx to 0, 0 to -dy, dx to 0, 0 to dy)) {
            device.swipe(cx, cy, cx + x, cy + y, 8)
            device.waitForIdle()
            Thread.sleep(900)
        }
    }
}

/** Back out of gameplay to the main menu through the pause overlay. */
fun MacrobenchmarkScope.leaveLevel() {
    device.pressBack()
    tap(By.text("Home"))
}
