package com.hatchslide.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.hatchslide.core.audio.AudioEngine
import com.hatchslide.core.render.PerfLog
import com.hatchslide.core.render.SceneDirector

/**
 * The single activity. It handles configuration changes itself (rotation, folding, resizing), so
 * the Filament surface is resized instead of recreated. The splash stays until saves are read.
 */
class MainActivity : ComponentActivity() {
    /** Visible to the instrumented tests, which check rendering and audio across the lifecycle. */
    internal lateinit var director: SceneDirector
    private lateinit var container: AppContainer
    internal lateinit var audio: AudioEngine
    private lateinit var quality: QualityController
    private lateinit var performance: PerformanceController
    private var foregroundSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        container = (application as HatchslideApp).container
        installSplashScreen().setKeepOnScreenCondition { !container.player.loaded.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PerfLog.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        director = SceneDirector(this)
        audio = AudioEngine(this)
        quality = QualityController(this, container.player, lifecycleScope)
        quality.attach(director.engine)
        quality.detectIfNeeded()
        performance = PerformanceController(this, lifecycleScope)
        performance.attach(director.engine)
        setContent { HatchslideRoot(container, director, audio, quality.autoTier, performance.thermalSteps) }
    }

    override fun onStart() {
        super.onStart()
        foregroundSince = SystemClock.elapsedRealtime()
        audio.onForeground()
        performance.onForeground()
    }

    override fun onResume() {
        super.onResume()
        quality.refreshGameMode()
        director.resume()
    }

    override fun onPause() {
        director.pause()
        super.onPause()
    }

    override fun onStop() {
        // Playtime counts only while the game is on screen.
        container.player.addPlaytime((SystemClock.elapsedRealtime() - foregroundSince) / 1000)
        audio.onBackground()
        performance.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        audio.release()
        quality.detach()
        performance.detach()
        director.destroy()
        super.onDestroy()
    }
}
