package com.hatchslide.app

import android.app.ActivityManager
import android.app.GameManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.hatchslide.core.data.GraphicsQuality
import com.hatchslide.core.data.PlayerRepository
import com.hatchslide.core.render.FrameObserver
import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.quality.AutoQuality
import com.hatchslide.core.render.quality.FrameBudgetMonitor
import com.hatchslide.core.render.quality.GameModeHint
import com.hatchslide.core.render.quality.GpuInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Auto graphics setting (spec section 6). The first launch reads the GPU renderer string, total
 * memory and the low-RAM flag and stores the chosen tier; every launch applies Android's Game Mode on
 * top. While quality is Auto, a sustained frame-time overrun (95th percentile over budget by 20% for
 * 5 s) drops one tier. The drop is saved, unless the device is thermally throttled: then it lasts for
 * this session only, since the slowdown is temporary.
 */
class QualityController(
    private val context: Context,
    private val player: PlayerRepository,
    private val scope: CoroutineScope,
) : FrameObserver {
    private val monitor = FrameBudgetMonitor()
    private val gameMode = MutableStateFlow(GameModeHint.STANDARD)
    private val sessionCap = MutableStateFlow<QualityTier?>(null)
    private var engine: RenderEngine? = null
    private var auto = false

    /** The tier that Auto stands for right now. */
    val autoTier: StateFlow<QualityTier> = combine(player.settings, gameMode, sessionCap) { settings, mode, cap ->
        val tier = AutoQuality.adjust(detected(settings.detectedTier), mode)
        if (cap != null && cap < tier) cap else tier
    }.stateIn(scope, SharingStarted.Eagerly, QualityTier.MEDIUM)

    init {
        scope.launch { player.settings.collect { auto = it.quality == GraphicsQuality.AUTO } }
    }

    fun attach(engine: RenderEngine) {
        this.engine = engine
        engine.frameObservers += this
    }

    fun detach() {
        engine?.frameObservers?.remove(this)
        engine = null
    }

    /** Runs the first-launch device check once the saved settings are read. */
    fun detectIfNeeded() {
        scope.launch {
            player.loaded.first { it }
            if (player.settings.value.detectedTier >= 0) return@launch
            val renderer = withContext(Dispatchers.Default) { runCatching { GpuInfo.renderer() }.getOrDefault("") }
            val activity = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
            val tier = AutoQuality.choose(renderer, memory.totalMem, activity?.isLowRamDevice == true)
            player.updateSettings { if (it.detectedTier < 0) it.copy(detectedTier = tier.ordinal) else it }
        }
    }

    /** Game Mode can change from the system game dashboard while the game is in the background. */
    fun refreshGameMode() {
        gameMode.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (context.getSystemService(GameManager::class.java)?.gameMode) {
                GameManager.GAME_MODE_PERFORMANCE -> GameModeHint.PERFORMANCE
                GameManager.GAME_MODE_BATTERY -> GameModeHint.BATTERY
                else -> GameModeHint.STANDARD
            }
        } else {
            GameModeHint.STANDARD
        }
    }

    override fun onFrame(intervalNanos: Long, workNanos: Long) {
        if (!auto) return
        val e = engine ?: return
        if (monitor.onFrame(intervalNanos, e.frameBudgetNanos())) drop()
    }

    override fun onStageChanged() = monitor.hold(2f)

    private fun drop() {
        val lower = AutoQuality.lower(autoTier.value) ?: return
        sessionCap.value = lower
        if (throttled()) return
        val mode = gameMode.value
        player.updateSettings { settings ->
            // The highest stored tier that, after Game Mode, is no higher than the new tier.
            val stored = QualityTier.entries.lastOrNull { AutoQuality.adjust(it, mode) <= lower } ?: QualityTier.LOW
            settings.copy(detectedTier = minOf(detected(settings.detectedTier), stored).ordinal)
        }
    }

    private fun throttled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun detected(ordinal: Int): QualityTier = QualityTier.entries.getOrElse(ordinal) { QualityTier.MEDIUM }
}
