package com.hatchslide.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendering smoke test (spec section 12): the title beach and a level render, and the screenshots
 * (saved to the app's external files directory, `smoke_*.png`) show a real scene, not a blank surface.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RenderSmokeTest {
    private val ui = GameUi()
    private val out = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    @Before
    fun freshInstall() = ui.resetSaves()

    private fun capture(name: String): Double {
        val file = File(out, "smoke_$name.png")
        assertTrue("screenshot $name", ui.device.takeScreenshot(file))
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        // Luminance spread over a coarse grid: a blank or single-colour surface has almost none.
        val values = ArrayList<Double>()
        for (y in 0 until bitmap.height step bitmap.height / 40) {
            for (x in 0 until bitmap.width step bitmap.width / 24) {
                val c = bitmap.getPixel(x, y)
                values += 0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)
            }
        }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun titleAndLevelRender() {
        ui.launch().use { scenario ->
            ui.waitFor(By.desc("Tap to start"))
            Thread.sleep(3_000)
            var frames = 0L
            scenario.onActivity { frames = it.director.engine.presentedFrames }
            assertTrue("title frames presented: $frames", frames > 60)
            assertTrue("title shows a scene", capture("title") > 12.0)
            ui.startToMenu()
            ui.openFirstLevel()
            Thread.sleep(2_500)
            assertTrue("level shows a scene", capture("level") > 12.0)
        }
    }
}
