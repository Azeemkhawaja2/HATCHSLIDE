package com.hatchslide.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.SceneDirector

/**
 * The single activity. It handles configuration changes itself (rotation, folding, resizing), so
 * the Filament surface is resized instead of recreated. The splash stays until saves are read.
 */
class MainActivity : ComponentActivity() {
    private lateinit var director: SceneDirector
    private lateinit var container: AppContainer
    private var foregroundSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        container = (application as HatchslideApp).container
        installSplashScreen().setKeepOnScreenCondition { !container.player.loaded.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        director = SceneDirector(this)
        setContent { HatchslideRoot(container, director, QualityTier.MEDIUM) }
    }

    override fun onStart() {
        super.onStart()
        foregroundSince = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        director.resume()
    }

    override fun onPause() {
        director.pause()
        super.onPause()
    }

    override fun onStop() {
        // Playtime counts only while the game is on screen.
        container.player.addPlaytime((SystemClock.elapsedRealtime() - foregroundSince) / 1000)
        super.onStop()
    }

    override fun onDestroy() {
        director.destroy()
        super.onDestroy()
    }
}
