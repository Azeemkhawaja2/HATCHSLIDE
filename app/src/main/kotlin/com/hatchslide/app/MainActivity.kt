package com.hatchslide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hatchslide.core.render.SceneDirector

/**
 * The single activity. It handles configuration changes itself (rotation, folding, resizing), so
 * the Filament surface is resized instead of recreated.
 */
class MainActivity : ComponentActivity() {
    private lateinit var director: SceneDirector

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        director = SceneDirector(this)
        val container = (application as HatchslideApp).container
        setContent { HatchslideRoot(container, director) }
    }

    override fun onResume() {
        super.onResume()
        director.resume()
    }

    override fun onPause() {
        director.pause()
        super.onPause()
    }

    override fun onDestroy() {
        director.destroy()
        super.onDestroy()
    }
}
